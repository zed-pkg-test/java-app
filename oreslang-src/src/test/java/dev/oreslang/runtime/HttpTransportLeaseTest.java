package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class HttpTransportLeaseTest {

    @Test
    void transportLeaseCannotBeFrozenOrSentAsData() {
        HttpTransportLease lease = new HttpTransportLease();
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(lease));

        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<Object>spawnPrivate(() -> (message, turn) -> { });
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> target.send(lease));
            assertTrue(error.getMessage().contains("capabil")
                    || error.getMessage().contains("not Sendable"));
        }
    }

    @Test
    void leaseHasExactlyOneControllingActorAndCannotBeTransferredTwice() {
        HttpTransportLease lease = new HttpTransportLease();
        ActorRuntime.ActorId first = ActorRuntime.ActorId.create();
        ActorRuntime.ActorId second = ActorRuntime.ActorId.create();

        assertEquals(HttpTransportLease.OwnerKind.SUPERVISOR, lease.owner().kind());

        lease.transferToActor(first);

        assertEquals(HttpTransportLease.OwnerKind.ACTOR, lease.owner().kind());
        assertEquals(first, lease.actorOwner().orElseThrow());
        assertDoesNotThrow(() ->
                lease.withActorOperation(first, "metadata", () -> "ok"));

        SecurityException wrongOwner = assertThrows(
                SecurityException.class,
                () -> lease.withActorOperation(second, "metadata", () -> "no"));
        assertTrue(wrongOwner.getMessage().contains("current owner"));

        IllegalStateException secondTransfer = assertThrows(
                IllegalStateException.class,
                () -> lease.transferToActor(second));
        assertTrue(secondTransfer.getMessage().contains("not supervisor-owned"));
    }

    @Test
    void ownershipTransferWaitsForInflightIo() throws Exception {
        HttpTransportLease lease = new HttpTransportLease();
        ActorRuntime.ActorId owner = ActorRuntime.ActorId.create();
        lease.transferToActor(owner);

        CountDownLatch ioEntered = new CountDownLatch(1);
        CountDownLatch allowIoExit = new CountDownLatch(1);
        CountDownLatch revokeReturned = new CountDownLatch(1);

        Thread io = new Thread(() -> {
            try {
                lease.withActorIo(owner, "read", () -> {
                    ioEntered.countDown();
                    try {
                        assertTrue(allowIoExit.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return 1;
                });
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });

        Thread revoke = new Thread(() -> {
            try {
                assertTrue(ioEntered.await(2, TimeUnit.SECONDS));
                lease.revokeFromActor(owner);
                revokeReturned.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });

        io.start();
        revoke.start();

        assertTrue(ioEntered.await(2, TimeUnit.SECONDS));
        assertFalse(revokeReturned.await(50, TimeUnit.MILLISECONDS),
                "revocation must wait for in-flight I/O");
        allowIoExit.countDown();

        io.join(2000);
        revoke.join(2000);
        assertEquals(HttpTransportLease.OwnerKind.RELEASED, lease.owner().kind());
    }

    @Test
    void actorTeardownRevokesTransportLeaseAndPreventsReuse() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            HttpTransportLease lease = new HttpTransportLease();
            AtomicInteger bytes = new AtomicInteger();
            ActorRuntime.HttpResponseTransport response = new ActorRuntime.HttpResponseTransport() {
                @Override
                public int write(ByteBuffer source) {
                    int count = source.remaining();
                    source.position(source.limit());
                    bytes.addAndGet(count);
                    return count;
                }
            };

            ActorRuntime.UntrustedActorLimits limits =
                    new ActorRuntime.UntrustedActorLimits(
                            Duration.ofSeconds(2), 100, 1024, 1024, 1024);

            var actor = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    limits,
                    null,
                    response,
                    lease,
                    ignored -> (message, turn) -> {
                        turn.httpResponse().orElseThrow()
                                .write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
                        turn.self().stop();
                    });

            actor.send("go");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
            assertEquals(3, bytes.get());
            assertEquals(HttpTransportLease.OwnerKind.RELEASED, lease.owner().kind());
            assertTrue(lease.actorOwner().isEmpty());

            IllegalStateException reuse = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.<String>spawnUntrusted(
                            IsolatePolicy.untrustedActor(),
                            limits,
                            null,
                            response,
                            lease,
                            ignored -> (message, turn) -> turn.self().stop()));
            assertTrue(reuse.getMessage().contains("not supervisor-owned"));
        }
    }

    @Test
    void privateActorCanOwnAndStreamDirectlyThroughTransportLease() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            HttpTransportLease lease = new HttpTransportLease();
            AtomicInteger bytes = new AtomicInteger();
            ActorRuntime.HttpResponseTransport response = new ActorRuntime.HttpResponseTransport() {
                @Override
                public int write(ByteBuffer source) {
                    int count = source.remaining();
                    source.position(source.limit());
                    bytes.addAndGet(count);
                    return count;
                }
            };

            var actor = runtime.<String>spawnPrivateHttp(
                    IsolatePolicy.developer(),
                    new ActorRuntime.HttpTransportLimits(1024, 1024),
                    null,
                    response,
                    lease,
                    ignored -> (message, turn) -> {
                        turn.httpResponse().orElseThrow()
                                .write(ByteBuffer.wrap(new byte[]{4, 5, 6, 7}));
                        turn.self().stop();
                    });

            actor.send("go");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
            assertEquals(4, bytes.get());
            assertEquals(HttpTransportLease.OwnerKind.RELEASED, lease.owner().kind());
        }
    }

    @Test
    void failedResponseCompletionAbortsAndRevokesTransport() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            HttpTransportLease lease = new HttpTransportLease();
            java.util.concurrent.atomic.AtomicBoolean aborted =
                    new java.util.concurrent.atomic.AtomicBoolean();
            ActorRuntime.HttpResponseTransport response = new ActorRuntime.HttpResponseTransport() {
                @Override
                public int write(ByteBuffer source) {
                    int count = source.remaining();
                    source.position(source.limit());
                    return count;
                }

                @Override
                public void complete() throws java.io.IOException {
                    throw new java.io.IOException("finish failed");
                }

                @Override
                public void abort(Throwable cause) {
                    aborted.set(true);
                }
            };

            var actor = runtime.<String>spawnPrivateHttp(
                    IsolatePolicy.developer(),
                    new ActorRuntime.HttpTransportLimits(1024, 1024),
                    null,
                    response,
                    lease,
                    ignored -> (message, turn) -> {
                        var out = turn.httpResponse().orElseThrow();
                        out.write(ByteBuffer.wrap(new byte[]{1}));
                        out.complete();
                    });

            actor.send("go");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(java.io.IOException.class, actor.failure().orElseThrow());
            assertTrue(aborted.get());
            assertEquals(HttpTransportLease.OwnerKind.RELEASED, lease.owner().kind());
        }
    }

    @Test
    void guestActorCannotPerformSupervisorOwnershipTransfer() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            HttpTransportLease lease = new HttpTransportLease();
            ActorRuntime.ActorId target = ActorRuntime.ActorId.create();

            var actor = runtime.<String>spawnPrivateTrusted(
                    ignored -> (message, turn) -> lease.transferToActor(target));

            actor.send("go");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, actor.failure().orElseThrow());
            assertEquals(HttpTransportLease.OwnerKind.SUPERVISOR, lease.owner().kind());
        }
    }
}
