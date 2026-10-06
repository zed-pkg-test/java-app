package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class SharedActorLocalHeapTest {

    @Test
    void sharedActorGetsLocalHeapWithoutPrivateIsolationAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch observed = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorMemorySlice> local = new AtomicReference<>();
            AtomicReference<ActorRuntime.MemoryReservation> reservation = new AtomicReference<>();

            var ref = runtime.<String>spawnSharedTrusted(factoryContext -> (message, context) -> {
                assertSame(factoryContext.localMemory(), context.localMemory());
                assertTrue(context.privateMemory().isEmpty());
                assertEquals(ActorRuntime.ActorKind.SHARED, context.localMemory().ownerKind());
                assertEquals(context.self().id(), context.localMemory().owner());
                assertEquals(
                        ActorRuntime.AllocationDomainKind.ACTOR_LOCAL,
                        context.allocationDomain().kind());

                local.set(context.localMemory());
                reservation.set(context.localMemory().reserveHeap(4096));
                observed.countDown();
            });

            ref.send("reserve");
            assertTrue(observed.await(2, TimeUnit.SECONDS));

            assertEquals(0L, runtime.privateMemoryBytes());
            assertTrue(runtime.sharedActorLocalMemoryBytes() >= 4096);
            assertEquals(runtime.sharedActorLocalMemoryBytes(), runtime.actorLocalMemoryBytes());
            assertTrue(local.get().usedBytes() >= 4096);

            ref.stop();
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(local.get().closed());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
            assertEquals(0L, runtime.actorLocalMemoryBytes());

            // Actor teardown owns the slice lifetime; a stale reservation close is idempotent.
            reservation.get().close();
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void privateAndUntrustedActorsKeepConfinedCompatibilityView() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var privateActor = runtime.<String>spawnPrivate(context -> {
                assertSame(context.localMemory(), context.privateMemory().orElseThrow());
                assertEquals(ActorRuntime.ActorKind.PRIVATE, context.localMemory().ownerKind());
                assertEquals(
                        ActorRuntime.AllocationDomainKind.ACTOR_PRIVATE,
                        context.allocationDomain().kind());
                context.localMemory().reserveHeap(32);
                return (message, turn) -> turn.self().stop();
            });

            privateActor.send("stop");
            assertTrue(privateActor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(privateActor.failure().isEmpty(), String.valueOf(privateActor.failure()));

            var untrusted = runtime.<String>spawnUntrusted(context -> {
                assertSame(context.localMemory(), context.privateMemory().orElseThrow());
                assertEquals(ActorRuntime.ActorKind.UNTRUSTED, context.localMemory().ownerKind());
                assertEquals(
                        ActorRuntime.AllocationDomainKind.UNTRUSTED_ISOLATE,
                        context.allocationDomain().kind());
                context.localMemory().reserveHeap(32);
                return (message, turn) -> turn.self().stop();
            });

            untrusted.send("stop");
            assertTrue(untrusted.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(untrusted.failure().isEmpty(), String.valueOf(untrusted.failure()));
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }

    @Test
    void explicitSharedStateIsSeparateFromSharedActorLocalHeap() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = runtime.syncCell("shared-value");
            long explicitSharedBytes = runtime.sharedMemoryBytes();
            assertTrue(explicitSharedBytes > 0L);

            CountDownLatch observed = new CountDownLatch(1);
            var ref = runtime.<String>spawnSharedTrusted(factoryContext -> (message, context) -> {
                long sharedBeforeLocalReserve = runtime.sharedMemoryBytes();
                context.localMemory().reserveHeap(2048);
                assertEquals(
                        sharedBeforeLocalReserve,
                        runtime.sharedMemoryBytes(),
                        "ordinary shared-actor state must not add RUNTIME_SHARED bytes");
                observed.countDown();
            });

            ref.send("reserve");
            assertTrue(observed.await(2, TimeUnit.SECONDS));

            assertTrue(runtime.sharedActorLocalMemoryBytes() >= 2048);
            assertTrue(runtime.sharedMemoryBytes() >= explicitSharedBytes,
                    "explicit shared state remains live while mailbox accounting may also be present");
            assertEquals(
                    runtime.actorLocalMemoryBytes() + runtime.sharedMemoryBytes(),
                    runtime.actorMemoryBytes());

            ref.stop();
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
            assertEquals(explicitSharedBytes, runtime.sharedMemoryBytes(),
                    "retiring one actor-local heap must not reclaim explicit shared state");
            assertEquals("shared-value", shared.snapshot());
        }
    }

    @Test
    void leakedSharedLocalHeapCannotBeUsedOutsideItsOwner() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch captured = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorMemorySlice> leaked = new AtomicReference<>();

            var ref = runtime.<String>spawnSharedTrusted(factoryContext -> (message, context) -> {
                leaked.set(context.localMemory());
                captured.countDown();
            });

            ref.send("capture");
            assertTrue(captured.await(2, TimeUnit.SECONDS));

            IllegalStateException outsideOwner = assertThrows(
                    IllegalStateException.class,
                    () -> leaked.get().reserveHeap(1));
            assertTrue(outsideOwner.getMessage().contains("owning actor"));

            ref.stop();
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void sharedLocalHeapAndMailboxShareOnePerActorByteCeiling() throws Exception {
        long limit = 16L * 1024 * 1024;
        IsolatePolicy actorPolicy = new IsolatePolicy(
                Set.of(IsolatePolicy.Capability.SHARED_MEMORY),
                limit,
                16,
                Duration.ofSeconds(5));

        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch reserved = new CountDownLatch(1);
            var ref = runtime.<String>spawnSharedTrusted(actorPolicy, factoryContext -> (message, context) -> {
                if (message.equals("reserve")) {
                    context.localMemory().reserveHeap(15L * 1024 * 1024);
                    reserved.countDown();
                }
            });

            ref.send("reserve");
            assertTrue(reserved.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.sharedActorLocalMemoryBytes() >= 15L * 1024 * 1024);

            String tooLargeForRemainingActorBudget = "x".repeat(2 * 1024 * 1024);
            IllegalStateException exceeded = assertThrows(
                    IllegalStateException.class,
                    () -> ref.send(tooLargeForRemainingActorBudget));
            assertTrue(exceeded.getMessage().contains("memory limit")
                            || exceeded.getMessage().contains("heap limit"),
                    exceeded.getMessage());

            ref.stop();
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }
}
