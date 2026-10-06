package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ActorStructuredCancellationTest {
    @Test
    void gracefulStopRacingMailboxPollingDoesNotRecordClosedChannelFailure() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            for (int attempt = 0; attempt < 100; attempt++) {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
                actor.send("work");
                actor.stop();
                assertTrue(actor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
            }
        }
    }

    @Test
    void actorSpawnedFromActorTurnIsAChildAndParentStopCancelsIt() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch childCreated = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();

            ActorRuntime.ActorRef<String> parent = runtime.spawnShared(() -> (message, context) -> {
                ActorRuntime.ActorRef<String> child =
                        context.runtime().spawnPrivate(
                                factoryContext -> (childMessage, childContext) -> { });
                childRef.set(child);
                childCreated.countDown();

                assertEquals(context.self().id(), child.parentId().orElseThrow());
                assertTrue(context.self().childIds().contains(child.id()));
                context.self().stop();
            });

            parent.send("spawn");
            assertTrue(childCreated.await(2, TimeUnit.SECONDS));

            ActorRuntime.ActorRef<String> child = childRef.get();
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(child.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse(parent.isAlive());
            assertFalse(child.isAlive());
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    child.failure().orElseThrow());
        }
    }

    @Test
    void childActorRefDoesNotGrantAuthorityToCancelParent() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch denied = new CountDownLatch(1);

            ActorRuntime.ActorRef<Object> parent =
                    runtime.spawnShared(() -> (message, context) -> {
                        if ("spawn-child".equals(message)) {
                            ActorRuntime.ActorRef<Object> child =
                                    context.runtime().spawnShared(
                                            factoryContext -> (childMessage, childContext) -> {
                                                if (childMessage instanceof ActorRuntime.ActorRef<?> parentRef) {
                                                    try {
                                                        parentRef.cancel();
                                                    } catch (SecurityException expected) {
                                                        @SuppressWarnings("unchecked")
                                                        ActorRuntime.ActorRef<Object> reply =
                                                                (ActorRuntime.ActorRef<Object>) parentRef;
                                                        reply.send("denied");
                                                        childContext.self().stop();
                                                    }
                                                }
                                            });
                            child.send(context.self());
                            return;
                        }

                        if ("denied".equals(message)) {
                            denied.countDown();
                            context.self().stop();
                        }
                    });

            parent.send("spawn-child");

            assertTrue(denied.await(2, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(parent.failure().isEmpty());
        }
    }

    @Test
    void parentMayCancelStructuredChildWithoutBlockingCarrier() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch requested = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> parent =
                    runtime.spawnShared(() -> (message, context) -> {
                        ActorRuntime.ActorRef<String> child =
                                context.runtime().spawnPrivate(
                                        factoryContext -> (childMessage, childContext) -> { });
                        childRef.set(child);
                        assertTrue(child.cancel());
                        requested.countDown();
                        context.self().stop();
                    });

            parent.send("cancel-child");

            assertTrue(requested.await(2, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(childRef.get().awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    childRef.get().failure().orElseThrow());
        }
    }

    @Test
    void explicitCancellationCascadesThroughChildTree() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch childCreated = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();

            ActorRuntime.ActorRef<String> parent = runtime.spawnShared(() -> (message, context) -> {
                ActorRuntime.ActorRef<String> child =
                        context.runtime().spawnPrivate(
                                factoryContext -> (childMessage, childContext) -> { });
                childRef.set(child);
                childCreated.countDown();
            });

            parent.send("spawn");
            assertTrue(childCreated.await(2, TimeUnit.SECONDS));

            assertTrue(parent.cancel());
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(childRef.get().awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    parent.failure().orElseThrow());
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    childRef.get().failure().orElseThrow());
        }
    }

    @Test
    void forceCancellationFailsClosedWithoutIsolationAuthority() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnPrivate(() -> (message, context) -> { });

            assertThrows(IllegalStateException.class, actor::forceCancel);
            assertTrue(actor.isAlive(), "denied force cancellation must have no logical side effect");
            actor.stop();
        }
    }

    @Test
    void structuredCancellationCannotBeSwallowedAsOrdinaryRuntimeFailure() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            AtomicBoolean swallowed = new AtomicBoolean();

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                entered.countDown();
                try {
                    while (true) {
                        context.runtime().schedulerSafepoint();
                    }
                } catch (RuntimeException ordinaryFailure) {
                    swallowed.set(true);
                }
            });

            actor.send("run");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse(swallowed.get(), "actor cancellation is control flow, not a catchable guest failure");
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    actor.failure().orElseThrow());
        }
    }

    @Test
    void runtimeRejectsLocalChannelCapabilityAsMessagePayload() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> actor =
                    runtime.spawnShared(() -> (message, context) -> { });
            ChannelRuntime.Channel<String> localChannel =
                    new ChannelRuntime.Channel<>(1);

            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> actor.send(localChannel));
            assertTrue(rejected.getMessage().contains("execution-domain local"));
            actor.stop();
        }
    }

    @Test
    void actorTeardownCancelsOwnedNonBlockingChannelRegistration() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ChannelRuntime.Channel<String> channel =
                    new ChannelRuntime.Channel<>(0);
            AtomicReference<OresFuture<String>> pending = new AtomicReference<>();
            CountDownLatch registered = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        OresFuture<String> read = context.runtime()
                                .ownCurrentActorFuture(channel.readAsync());
                        pending.set(read);
                        registered.countDown();
                        context.self().stop();
                    });

            actor.send("register");
            assertTrue(registered.await(2, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));

            OresFuture<String> read = pending.get();
            assertTrue(
                    read.isCancelled(),
                    "actor teardown must cancel an abandoned nb channel waiter");
            assertFalse(
                    channel.tryWrite("orphan"),
                    "dead actor's cancelled read must not remain registered");
        }
    }

    @Test
    void continuationHeadroomDoesNotReduceConfiguredUserMailboxCapacity() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy tinyMailbox = new IsolatePolicy(
                base.capabilities(),
                base.maxHeapBytes(),
                2,
                Duration.ofSeconds(5),
                false);

        try (ActorRuntime runtime = new ActorRuntime(base)) {
            CountDownLatch userMessagesAdmitted = new CountDownLatch(1);
            CountDownLatch userMessagesDelivered = new CountDownLatch(2);
            AtomicInteger delivered = new AtomicInteger();

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(tinyMailbox, () -> (message, context) -> {
                        if (message.equals("seed")) {
                            ActorRuntime.ContinuationTarget target =
                                    context.runtime().captureCurrentContinuationTarget();

                            // These complete immediately but their continuations
                            // must queue behind the current actor turn.
                            for (int i = 0; i < 3; i++) {
                                context.runtime().enqueueOnCompletion(
                                        OresFuture.completed(i),
                                        target,
                                        (value, failure) -> { });
                            }

                            // User capacity remains exactly maxMailboxMessages=2
                            // despite the already-queued runtime continuations.
                            context.self().send("u1");
                            context.self().send("u2");
                            userMessagesAdmitted.countDown();
                            return;
                        }

                        if (message.equals("u1") || message.equals("u2")) {
                            delivered.incrementAndGet();
                            userMessagesDelivered.countDown();
                            if (delivered.get() == 2) context.self().stop();
                        }
                    });

            actor.send("seed");

            assertTrue(
                    userMessagesAdmitted.await(2, TimeUnit.SECONDS),
                    "runtime continuations must not steal user mailbox quota");
            assertTrue(userMessagesDelivered.await(2, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void carrierInterruptIsNotActorCancellationIdentity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch continuedAfterSafepoint = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        Thread.currentThread().interrupt();
                        try {
                            context.runtime().schedulerSafepoint();
                            continuedAfterSafepoint.countDown();
                        } finally {
                            // Do not leak this host-side test interrupt into a
                            // pooled carrier after the actor turn completes.
                            Thread.interrupted();
                            context.self().stop();
                        }
                    });

            actor.send("interrupt-carrier");

            assertTrue(
                    continuedAfterSafepoint.await(2, TimeUnit.SECONDS),
                    "carrier interrupt must not be interpreted as actor cancellation");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(
                    actor.failure().isEmpty(),
                    "carrier interrupt must not record an actor failure/cancellation");
        }
    }

    @Test
    void ordinaryActorCannotInvokeForceCancellationAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicBoolean revokerInvoked = new AtomicBoolean();
            CountDownLatch denied = new CountDownLatch(1);

            runtime.setForceCancellationHook((actorId, executionDomain) -> {
                revokerInvoked.set(true);
                return true;
            });

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            context.self().forceCancel();
                        } catch (SecurityException expected) {
                            denied.countDown();
                            context.self().stop();
                        }
                    });

            actor.send("attempt-force-cancel");

            assertTrue(denied.await(2, TimeUnit.SECONDS));
            assertFalse(
                    revokerInvoked.get(),
                    "ordinary actor code must never reach host isolate-revocation authority");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void forceCancellationDelegatesToOuterIsolationRevoker() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicBoolean invoked = new AtomicBoolean();
            runtime.setForceCancellationHook((actorId, executionDomain) -> {
                invoked.set(true);
                return true;
            });

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnPrivate(() -> (message, context) -> { });

            assertTrue(actor.forceCancel());
            assertTrue(invoked.get());
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
