package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresFutureTest {

    @Test
    void completionIsSingleShotAndPreservesOriginalFailure() {
        OresFuture<Integer> future = new OresFuture<>();
        IllegalStateException failure = new IllegalStateException("boom");

        assertTrue(future.failFromRuntime(failure));
        assertFalse(future.completeFromRuntime(42));

        IllegalStateException observed =
                assertThrows(IllegalStateException.class, () -> AsyncRuntime.await(future));
        assertSame(failure, observed);
    }

    @Test
    void cancellationRunsHostHookAtMostOnce() {
        java.util.concurrent.atomic.AtomicInteger hooks =
                new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> future = new OresFuture<>(hooks::incrementAndGet);

        assertTrue(future.cancel(true));
        assertFalse(future.cancel(true));
        assertEquals(1, hooks.get());
        assertTrue(future.isCancelled());
        assertThrows(CancellationException.class, () -> future.getNow(0));
    }

    @Test
    void completionStageAdapterOnlySettlesOresState() throws Exception {
        CompletableFuture<Integer> host = new CompletableFuture<>();
        OresFuture<Integer> ores = OresFuture.from(host);
        AtomicReference<String> callbackThread = new AtomicReference<>();
        CountDownLatch callback = new CountDownLatch(1);

        ores.whenCompleteRuntime((value, failure) -> {
            callbackThread.set(Thread.currentThread().getName());
            callback.countDown();
        });

        Thread producer = Thread.ofPlatform().name("foreign-producer").start(() -> host.complete(7));
        producer.join();

        assertTrue(callback.await(2, TimeUnit.SECONDS));
        assertEquals("foreign-producer", callbackThread.get());
        assertEquals(7, AsyncRuntime.await(ores));
        // The observer above is runtime-only. Guest continuations are never
        // exposed through this API and therefore cannot run on producer threads.
    }

    @Test
    void futureAllPreservesInputOrder() {
        OresFuture<Integer> a = new OresFuture<>();
        OresFuture<Integer> b = new OresFuture<>();
        OresFuture<Integer> c = new OresFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(a, b, c));
        c.completeFromRuntime(3);
        a.completeFromRuntime(1);
        b.completeFromRuntime(2);

        assertEquals(List.of(1, 2, 3), AsyncRuntime.await(all));
    }

    @Test
    void futureAllFailsFastAndCancellationFansOut() {
        OresFuture<Integer> a = new OresFuture<>();
        OresFuture<Integer> b = new OresFuture<>();
        OresFuture<List<Integer>> all = OresFutures.all(List.of(a, b));

        IllegalArgumentException failure = new IllegalArgumentException("bad");
        b.failFromRuntime(failure);

        IllegalArgumentException observed =
                assertThrows(IllegalArgumentException.class, () -> AsyncRuntime.await(all));
        assertSame(failure, observed);

        OresFuture<Integer> c = new OresFuture<>();
        OresFuture<Integer> d = new OresFuture<>();
        OresFuture<List<Integer>> cancellable = OresFutures.all(List.of(c, d));
        assertTrue(cancellable.cancel(true));
        assertTrue(c.isCancelled());
        assertTrue(d.isCancelled());
    }

    @Test
    void raceUsesFirstSettledChild() {
        OresFuture<Integer> a = new OresFuture<>();
        OresFuture<Integer> b = new OresFuture<>();
        OresFuture<Integer> race = OresFutures.race(List.of(a, b));

        b.completeFromRuntime(9);
        a.completeFromRuntime(4);

        assertEquals(9, AsyncRuntime.await(race));
    }
}
