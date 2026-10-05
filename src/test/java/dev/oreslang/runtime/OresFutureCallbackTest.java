package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresFutureCallbackTest {

    @Test
    void fromCallbackMaySettleSynchronouslyButRemainsSingleShot() throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> completion = new AtomicReference<>();

        OresFuture<Integer> future = OresFuture.fromCallback(callback -> {
            completion.set(callback);
            callback.resolve(41);
        });

        assertEquals(41, future.get(5, TimeUnit.SECONDS));
        assertTrue(completion.get().isDone());
        assertThrows(
                OresFuture.AlreadySettledException.class,
                () -> completion.get().resolve(42));
        assertEquals(41, future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void lateRuntimeWaiterOnSettledFutureIsDeliveredAndReleased() {
        OresFuture<Integer> future = OresFuture.completed(42);
        AtomicReference<Integer> observed = new AtomicReference<>();

        future.whenCompleteRuntime((value, failure) -> {
            assertNull(failure);
            observed.set(value);
        });

        assertEquals(42, observed.get());
        assertEquals(
                0,
                future.pendingRuntimeWaiterCount(),
                "late terminal registrations must not remain retained in the waiter queue");
    }

    @Test
    void registrarThrowRejectsFutureWhenCallbackHasNotSettled() {
        OresFuture<Integer> future = OresFuture.fromCallback(callback -> {
            throw new IllegalStateException("registration failed");
        });

        var failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("registration failed", failure.getCause().getMessage());
    }

    @Test
    void actorAwaitOfPreSettledFutureStillResumesOnLaterTurn() throws Exception {
        OresFuture<Integer> ready = OresFuture.completed(42);
        AtomicBoolean insideOriginalBehavior = new AtomicBoolean();
        AtomicBoolean resumedInline = new AtomicBoolean();
        java.util.concurrent.CountDownLatch resumed =
                new java.util.concurrent.CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawn(() -> (message, context) -> {
                insideOriginalBehavior.set(true);
                try {
                    context.suspendOn(
                            ready,
                            (value, failure, resumedContext) -> {
                                resumedInline.set(insideOriginalBehavior.get());
                                assertNull(failure);
                                assertEquals(42, value);
                                resumed.countDown();
                                resumedContext.self().stop();
                            });
                } finally {
                    insideOriginalBehavior.set(false);
                }
            });

            actor.send("await-ready");

            assertTrue(resumed.await(2, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse(
                    resumedInline.get(),
                    "pre-settled await must unwind the original actor turn before resumption");
            assertEquals(0, ready.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void stoppingSuspendedActorDetachesNeverSettlingFutureWaiter() throws Exception {
        OresFuture<Integer> never = new OresFuture<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawn(() -> (message, context) ->
                    context.suspendOn(
                            never,
                            (value, failure, resumedContext) ->
                                    fail("stopped actor continuation must never resume")));

            actor.send("wait");

            long registrationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (never.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < registrationDeadline) {
                Thread.sleep(2);
            }
            assertEquals(
                    1,
                    never.pendingRuntimeWaiterCount(),
                    "suspended actor must own exactly one detachable Future waiter");

            actor.stop();
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));

            long detachDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (never.pendingRuntimeWaiterCount() != 0
                    && System.nanoTime() < detachDeadline) {
                Thread.sleep(2);
            }
            assertEquals(
                    0,
                    never.pendingRuntimeWaiterCount(),
                    "actor teardown must detach its pending Future waiter");
        }
    }

    @Test
    void synchronousAttachedCallbackCannotCompleteChainOnRegistrarStack() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicBoolean insideRegistrar = new AtomicBoolean();
            AtomicBoolean completedInsideRegistrar = new AtomicBoolean();

            OresFuture<Integer> source = OresFuture.completed(40);
            OresFuture<Integer> chained = source.attachCallback(
                    scheduler,
                    (value, callback) -> {
                        insideRegistrar.set(true);
                        try {
                            callback.resolve(value + 2);
                        } finally {
                            insideRegistrar.set(false);
                        }
                    });

            chained.whenCompleteRuntime(
                    (value, failure) ->
                            completedInsideRegistrar.set(insideRegistrar.get()));

            assertEquals(42, chained.get(5, TimeUnit.SECONDS));
            assertFalse(
                    completedInsideRegistrar.get(),
                    "synchronous callback settlement must not recursively resume "
                            + "the dependent Future on the registrar stack");
        }
    }
}
