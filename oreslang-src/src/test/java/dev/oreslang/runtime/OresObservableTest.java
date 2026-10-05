package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresObservableTest {

    @Test
    void fromValuesIsColdReplayableAndPullBackpressured() {
        OresObservable<Integer> values = OresObservable.fromValues(List.of(1, 2, 3));

        OresSubscription<Integer> first = values.subscribe();
        OresSubscription<Integer> second = values.subscribe();

        assertEquals(1, first.next().join().value());
        assertEquals(2, first.next().join().value());

        assertEquals(1, second.next().join().value());
        assertEquals(3, first.next().join().value());
        assertTrue(first.next().join().isComplete());

        assertEquals(2, second.next().join().value());
        assertEquals(3, second.next().join().value());
        assertTrue(second.next().join().isComplete());
    }

    @Test
    void onlyOneNextMayBeOutstanding() {
        OresFuture<Integer> source = new OresFuture<>();
        OresSubscription<Integer> subscription =
                OresObservable.fromFuture(source).subscribe();

        OresFuture<OresNotification<Integer>> first = subscription.next();
        OresFuture<OresNotification<Integer>> duplicate = subscription.next();

        CompletionException failure =
                assertThrows(CompletionException.class, duplicate::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());

        source.completeFromRuntime(7);
        assertEquals(7, first.join().value());
        assertTrue(subscription.next().join().isComplete());
    }

    @Test
    void takeCancelsUpstreamAfterLimitAndThenCompletes() {
        OresSubscription<Integer> subscription =
                OresObservable.fromValues(List.of(10, 20, 30, 40))
                        .take(2)
                        .subscribe();

        assertEquals(10, subscription.next().join().value());
        assertEquals(20, subscription.next().join().value());
        assertTrue(subscription.next().join().isComplete());
        assertTrue(subscription.isTerminated());
    }

    @Test
    void firstBridgesObservableBackToOresFuture() {
        OresFuture<Integer> first =
                OresObservable.fromValues(List.of(4, 5, 6)).first();

        assertEquals(4, first.join());
    }

    @Test
    void firstFailsForEmptyStream() {
        OresFuture<Integer> first = OresObservable.<Integer>empty().first();

        CompletionException failure =
                assertThrows(CompletionException.class, first::join);
        assertInstanceOf(java.util.NoSuchElementException.class, failure.getCause());
    }

    @Test
    void cancellingOneSharedFutureSubscriptionDoesNotCancelProducer() {
        OresFuture<Integer> source = new OresFuture<>();
        OresObservable<Integer> observable = OresObservable.fromFuture(source);

        OresSubscription<Integer> one = observable.subscribe();
        OresSubscription<Integer> two = observable.subscribe();

        OresFuture<OresNotification<Integer>> first = one.next();
        OresFuture<OresNotification<Integer>> second = two.next();

        assertEquals(2, source.pendingRuntimeWaiterCount());

        assertTrue(one.cancel());
        assertFalse(source.isCancelled(),
                "one rx subscriber must not cancel a shared source Future");
        assertEquals(1, source.pendingRuntimeWaiterCount());

        source.completeFromRuntime(99);

        assertThrows(CancellationException.class, first::join);
        assertEquals(99, second.join().value());
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }

    @Test
    void sourceFailureIsTerminal() {
        OresFuture<Integer> source =
                OresFuture.failed(new IllegalStateException("boom"));
        OresSubscription<Integer> subscription =
                OresObservable.fromFuture(source).subscribe();

        CompletionException failure =
                assertThrows(CompletionException.class, () -> subscription.next().join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());

        assertTrue(subscription.isTerminated());
        assertTrue(subscription.next().join().isComplete());
    }

    @Test
    void publicObservableCallbacksAreSchedulerBound() {
        assertFalse(
                java.util.Arrays.stream(OresObservable.class.getMethods())
                        .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                        .anyMatch(java.util.function.Consumer.class::isAssignableFrom),
                "rx-ores must not expose push-style Consumer callbacks");

        java.util.Arrays.stream(OresObservable.class.getMethods())
                .filter(method -> java.util.Arrays.stream(method.getParameterTypes())
                        .anyMatch(type -> java.util.function.Function.class.isAssignableFrom(type)
                                || java.util.function.Predicate.class.isAssignableFrom(type)))
                .forEach(method -> {
                    Class<?>[] parameters = method.getParameterTypes();
                    assertTrue(
                            parameters.length >= 2
                                    && parameters[0] == OresScheduler.class,
                            () -> "guest transform callback must be explicitly scheduler-bound: "
                                    + method);
                });
    }
    @Test
    void foreignProducerRxCompletionResumesOnlyOnOwningScheduler() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresObservable<Integer> observable = OresObservable.fromFuture(source);
            AtomicInteger pc = new AtomicInteger();
            AtomicReference<Thread> producerThread = new AtomicReference<>();

            OresFuture<Integer> consumed = scheduler.start(resume -> {
                assertSame(scheduler, OresScheduler.current());

                if (pc.getAndIncrement() == 0) {
                    return OresScheduler.await(observable.first());
                }

                assertNull(resume.failure());
                assertEquals(77, resume.value());
                assertNotSame(
                        producerThread.get(),
                        Thread.currentThread(),
                        "rx producer/completion thread must not execute guest continuation");
                return OresScheduler.done((Integer) resume.value());
            });

            Thread producer = Thread.ofPlatform().start(() -> {
                producerThread.set(Thread.currentThread());
                source.completeFromRuntime(77);
            });
            producer.join();

            assertEquals(77, consumed.get(5, TimeUnit.SECONDS));
            assertEquals(2, pc.get());
        }
    }

    @Test
    void runtimeCancellationCleanupRunsOnceAfterTerminalFailure() {
        AtomicInteger cleanupCalls = new AtomicInteger();
        OresFuture<OresNotification<Integer>> source = new OresFuture<>();

        OresSubscription<Integer> subscription = new OresSubscription<>() {
            @Override
            protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                return source;
            }

            @Override
            protected void cancelFromRuntime() {
                cleanupCalls.incrementAndGet();
            }
        };

        OresFuture<OresNotification<Integer>> pull = subscription.next();
        source.failFromRuntime(new IllegalStateException("source-failed"));

        CompletionException failure =
                assertThrows(CompletionException.class, pull::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(1, cleanupCalls.get());

        assertTrue(subscription.cancel());
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void schedulerBoundMapNeverRunsOnForeignProducerThread() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            AtomicReference<Thread> producerThread = new AtomicReference<>();
            AtomicReference<Thread> mapperThread = new AtomicReference<>();
            AtomicReference<OresScheduler> mapperScheduler = new AtomicReference<>();

            OresSubscription<Integer> subscription =
                    OresObservable.fromFuture(source)
                            .map(scheduler, value -> {
                                mapperThread.set(Thread.currentThread());
                                mapperScheduler.set(OresScheduler.current());
                                return value + 1;
                            })
                            .subscribe();

            OresFuture<OresNotification<Integer>> pull = subscription.next();

            Thread producer = Thread.ofPlatform().start(() -> {
                producerThread.set(Thread.currentThread());
                source.completeFromRuntime(41);
            });
            producer.join();

            assertEquals(42, pull.get(5, TimeUnit.SECONDS).value());
            assertSame(scheduler, mapperScheduler.get());
            assertNotSame(producerThread.get(), mapperThread.get(),
                    "rx map callback must execute on the bound scheduler, not producer thread");
        }
    }

    @Test
    void filterYieldsBetweenRejectedImmediateItems() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            java.util.ArrayList<Long> dispatches = new java.util.ArrayList<>();

            OresSubscription<Integer> subscription =
                    OresObservable.fromValues(List.of(1, 2, 3, 4))
                            .filter(scheduler, value -> {
                                dispatches.add(OresScheduler.currentDispatchId());
                                assertSame(scheduler, OresScheduler.current());
                                return value == 4;
                            })
                            .subscribe();

            assertEquals(4, subscription.next().get(5, TimeUnit.SECONDS).value());
            assertEquals(4, dispatches.size());
            assertEquals(4, new java.util.HashSet<>(dispatches).size(),
                    "each rejected immediate item must return through a later scheduler turn");
        }
    }

    @Test
    void cancellingMappedPullDetachesSharedUpstreamWithoutCancellingProducer() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresSubscription<Integer> subscription =
                    OresObservable.fromFuture(source)
                            .map(scheduler, value -> value + 1)
                            .subscribe();

            OresFuture<OresNotification<Integer>> pull = subscription.next();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (source.pendingRuntimeWaiterCount() == 0
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, source.pendingRuntimeWaiterCount());

            assertTrue(pull.cancel(true));

            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (source.pendingRuntimeWaiterCount() != 0
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            assertEquals(0, source.pendingRuntimeWaiterCount());
            assertFalse(source.isCancelled(),
                    "operator cancellation must detach a shared source Future, not cancel it");
            assertTrue(subscription.isTerminated());
        }
    }

}
