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
