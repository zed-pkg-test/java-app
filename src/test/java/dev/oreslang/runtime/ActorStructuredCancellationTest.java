package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ActorStructuredCancellationTest {
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
