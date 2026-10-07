package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class SelectReadinessRuntimeTest {
    @Test
    void completedFutureIsImmediatelySelectable() {
        OresFuture<Integer> future = OresFuture.completed(42);
        ChannelRuntime.SelectSet set = new ChannelRuntime.SelectSet(List.of(
                ChannelRuntime.await(future),
                ChannelRuntime.timeout(TimeUnit.SECONDS.toNanos(5))));

        ChannelRuntime.SelectResult result =
                set.trySelect(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow();
        assertEquals(0, result.index());
        assertEquals(ChannelRuntime.SelectOperation.AWAIT, result.operation());
        assertEquals(42, result.value());
    }

    @Test
    void timeoutSettlesWithoutPollingAndDetachesFutureLoser() throws Exception {
        OresFuture<Integer> pending = new OresFuture<>();
        ChannelRuntime.SelectSet set = new ChannelRuntime.SelectSet(List.of(
                ChannelRuntime.await(pending),
                ChannelRuntime.timeout(TimeUnit.MILLISECONDS.toNanos(10))));

        OresFuture<ChannelRuntime.SelectResult> selected =
                set.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        ChannelRuntime.SelectResult result = selected.get(2, TimeUnit.SECONDS);

        assertEquals(1, result.index());
        assertEquals(ChannelRuntime.SelectOperation.TIMEOUT, result.operation());
        assertEquals(0, pending.pendingRuntimeWaiterCount());
    }

    @Test
    void cancellationTokenWakesSelectionAndLosingFutureIsDetached() throws Exception {
        OresFuture<Integer> pending = new OresFuture<>();
        CancellationToken token = new CancellationToken();
        ChannelRuntime.SelectSet set = new ChannelRuntime.SelectSet(List.of(
                ChannelRuntime.await(pending),
                ChannelRuntime.cancelled(token),
                ChannelRuntime.timeout(TimeUnit.SECONDS.toNanos(5))));

        OresFuture<ChannelRuntime.SelectResult> selected =
                set.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertEquals(1, pending.pendingRuntimeWaiterCount());
        assertEquals(1, token.pendingRuntimeWaiterCount());

        assertTrue(token.cancel());
        ChannelRuntime.SelectResult result = selected.get(2, TimeUnit.SECONDS);

        assertEquals(1, result.index());
        assertEquals(ChannelRuntime.SelectOperation.CANCELLED, result.operation());
        assertEquals(0, pending.pendingRuntimeWaiterCount());
        assertEquals(0, token.pendingRuntimeWaiterCount());
    }

    @Test
    void futureFailureWinsAndPropagates() {
        IllegalStateException boom = new IllegalStateException("boom");
        OresFuture<Integer> failed = OresFuture.failed(boom);
        ChannelRuntime.SelectSet set = new ChannelRuntime.SelectSet(List.of(
                ChannelRuntime.await(failed),
                ChannelRuntime.timeout(TimeUnit.SECONDS.toNanos(5))));

        CompletionException error = assertThrows(
                CompletionException.class,
                () -> set.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join());
        assertSame(boom, error.getCause());
    }

    @Test
    void fairPolicyRotatesAcrossReadyFutureAndZeroTimeout() {
        OresFuture<Integer> ready = OresFuture.completed(9);
        ChannelRuntime.SelectSet set = new ChannelRuntime.SelectSet(List.of(
                ChannelRuntime.await(ready),
                ChannelRuntime.timeout(0L)));

        assertEquals(0, set.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow().index());
        assertEquals(1, set.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow().index());
    }


    @Test
    void reusableSelectPlanPreservesFairnessAcrossGenerations() {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(2);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(2);
        assertTrue(first.tryWrite(10));
        assertTrue(first.tryWrite(11));
        assertTrue(second.tryWrite(20));
        assertTrue(second.tryWrite(21));

        ChannelRuntime.SelectPlan plan = new ChannelRuntime.SelectPlan(List.of(
                ChannelRuntime.read(first),
                ChannelRuntime.read(second)));

        ChannelRuntime.SelectResult a =
                plan.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
        ChannelRuntime.SelectResult b =
                plan.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();

        assertEquals(0, a.index());
        assertEquals(10, a.value());
        assertEquals(1, b.index());
        assertEquals(20, b.value());
    }

    @Test
    void reusableSelectPlanRestartsRelativeTimeoutPerInvocation() throws Exception {
        ChannelRuntime.SelectPlan plan = new ChannelRuntime.SelectPlan(List.of(
                ChannelRuntime.timeout(TimeUnit.MILLISECONDS.toNanos(40))));

        ChannelRuntime.SelectResult first =
                plan.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY)
                        .get(2, TimeUnit.SECONDS);
        assertEquals(ChannelRuntime.SelectOperation.TIMEOUT, first.operation());

        long started = System.nanoTime();
        ChannelRuntime.SelectResult second =
                plan.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY)
                        .get(2, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - started;

        assertEquals(ChannelRuntime.SelectOperation.TIMEOUT, second.operation());
        assertTrue(elapsed >= TimeUnit.MILLISECONDS.toNanos(20),
                "reused plan must start a fresh relative timeout generation");
    }


    @Test
    void cancellingSelectionDetachesAllExternalLosersAndPlanCanBeReused() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        CancellationToken token = new CancellationToken();
        ChannelRuntime.SelectPlan plan = new ChannelRuntime.SelectPlan(List.of(
                ChannelRuntime.await(source),
                ChannelRuntime.cancelled(token),
                ChannelRuntime.timeout(TimeUnit.SECONDS.toNanos(1))));

        OresFuture<ChannelRuntime.SelectResult> first =
                plan.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertEquals(1, source.pendingRuntimeWaiterCount());
        assertEquals(1, token.pendingRuntimeWaiterCount());

        assertTrue(first.cancel(false));
        assertEquals(0, source.pendingRuntimeWaiterCount());
        assertEquals(0, token.pendingRuntimeWaiterCount());

        assertTrue(source.completeFromRuntime(33));
        ChannelRuntime.SelectResult second =
                plan.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY)
                        .get(2, TimeUnit.SECONDS);

        assertEquals(0, second.index());
        assertEquals(ChannelRuntime.SelectOperation.AWAIT, second.operation());
        assertEquals(33, second.value());
        assertEquals(0, source.pendingRuntimeWaiterCount());
        assertEquals(0, token.pendingRuntimeWaiterCount());
    }

    @Test
    void losingTimeoutFromOldGenerationCannotWinReusedPlan() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        ChannelRuntime.SelectPlan plan = new ChannelRuntime.SelectPlan(List.of(
                ChannelRuntime.await(source),
                ChannelRuntime.timeout(TimeUnit.MILLISECONDS.toNanos(40))));

        OresFuture<ChannelRuntime.SelectResult> first =
                plan.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertTrue(first.cancel(false));

        assertTrue(source.completeFromRuntime(71));
        ChannelRuntime.SelectResult second =
                plan.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY)
                        .get(2, TimeUnit.SECONDS);
        assertEquals(ChannelRuntime.SelectOperation.AWAIT, second.operation());
        assertEquals(71, second.value());

        Thread.sleep(80L);
        assertEquals(ChannelRuntime.SelectOperation.AWAIT, second.operation());
        assertEquals(71, second.value());
    }

    @Test
    void preCancelledTokenParticipatesInImmediatePriorityAndFairSelection() {
        CancellationToken token = new CancellationToken();
        assertTrue(token.cancel());
        OresFuture<Integer> ready = OresFuture.completed(5);
        ChannelRuntime.SelectPlan plan = new ChannelRuntime.SelectPlan(List.of(
                ChannelRuntime.cancelled(token),
                ChannelRuntime.await(ready)));

        ChannelRuntime.SelectResult priority =
                plan.trySelect(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow();
        assertEquals(ChannelRuntime.SelectOperation.CANCELLED, priority.operation());

        ChannelRuntime.SelectResult fair =
                plan.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
        assertEquals(ChannelRuntime.SelectOperation.CANCELLED, fair.operation());
    }

    @Test
    void defaultBeatsOnlyUnreadyReadinessCases() {
        OresFuture<Integer> pending = new OresFuture<>();
        CancellationToken token = new CancellationToken();
        ChannelRuntime.SelectSet set = new ChannelRuntime.SelectSet(List.of(
                ChannelRuntime.await(pending),
                ChannelRuntime.cancelled(token),
                ChannelRuntime.timeout(TimeUnit.SECONDS.toNanos(5)),
                ChannelRuntime.defaultCase()));

        ChannelRuntime.SelectResult result =
                set.trySelect(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow();
        assertEquals(3, result.index());
        assertEquals(ChannelRuntime.SelectOperation.DEFAULT, result.operation());
        assertEquals(0, pending.pendingRuntimeWaiterCount());
        assertEquals(0, token.pendingRuntimeWaiterCount());
    }
}
