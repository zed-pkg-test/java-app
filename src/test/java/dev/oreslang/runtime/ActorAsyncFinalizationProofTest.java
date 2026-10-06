package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ActorAsyncFinalizationProofTest {
    @Test
    void asyncResultRemainsPendingUntilCarrierLeavesTurnBoundary() throws Exception {
        CountDownLatch guestReturned = new CountDownLatch(1);
        CountDownLatch leaveBoundary = new CountDownLatch(1);
        ActorRuntime.TurnExecutor boundary = turn -> {
            turn.run();
            guestReturned.countDown();
            try {
                if (!leaveBoundary.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("host did not release turn boundary");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8),
                boundary)) {
            OresFuture<Integer> result = runtime.invokeAsync(
                    ActorRuntime.ActorKind.SHARED,
                    41,
                    (message, context) -> message + 1);
            try {
                assertTrue(guestReturned.await(2, TimeUnit.SECONDS));
                assertFalse(
                        result.isDone(),
                        "result must not outlive an entered guest context");
            } finally {
                leaveBoundary.countDown();
            }
            assertEquals(42, result.get(2, TimeUnit.SECONDS));
        } finally {
            leaveBoundary.countDown();
        }
    }
}
