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
