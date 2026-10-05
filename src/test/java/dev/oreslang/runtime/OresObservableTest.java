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
    void sharedFutureCancellationRemainsCancellationForPulledDemand() {
        OresFuture<Integer> source = new OresFuture<>();
        OresSubscription<Integer> subscription =
                OresObservable.fromFuture(source).subscribe();

        OresFuture<OresNotification<Integer>> pull = subscription.next();
        assertTrue(source.cancel(true));

        assertTrue(pull.isCancelled(),
                "upstream cancellation must remain cancellation");
        assertThrows(CancellationException.class, pull::join);
        assertTrue(subscription.isTerminated());
        assertFalse(subscription.isCancelled(),
                "the producer cancelled; the subscriber did not explicitly cancel itself");
    }

    @Test
    void firstPreservesUpstreamCancellation() {
        OresFuture<Integer> source = new OresFuture<>();
        OresFuture<Integer> first = OresObservable.fromFuture(source).first();

        assertTrue(source.cancel(true));

        assertTrue(first.isCancelled());
        assertThrows(CancellationException.class, first::join);
    }

    @Test
    void cancellationExceptionFailureIsNotMisclassifiedAsCancellation() {
        OresFuture<Integer> source =
                OresFuture.failed(new CancellationException("domain failure"));
        OresFuture<OresNotification<Integer>> pull =
                OresObservable.fromFuture(source).subscribe().next();

        assertFalse(source.isCancelled());
        assertFalse(pull.isCancelled(),
                "cancellation identity comes from Future state, not exception class");

        CompletionException failure =
                assertThrows(CompletionException.class, pull::join);
        assertInstanceOf(CancellationException.class, failure.getCause());
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
        assertFalse(subscription.cancel(),
                "cancellation after terminal failure must not reopen teardown");
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

        assertFalse(subscription.cancel(),
                "terminal subscription cancellation must report no state transition");
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void subscriptionRuntimeCleanupRunsExactlyOnceAcrossCancellationCascade() {
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
        assertTrue(subscription.cancel());
        assertThrows(CancellationException.class, pull::join);
        assertFalse(subscription.cancel());
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void synchronousSourceFailureStillRunsRuntimeCleanupExactlyOnce() {
        AtomicInteger cleanupCalls = new AtomicInteger();

        OresSubscription<Integer> subscription = new OresSubscription<>() {
            @Override
            protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                throw new IllegalStateException("source construction failed");
            }

            @Override
            protected void cancelFromRuntime() {
                cleanupCalls.incrementAndGet();
            }
        };

        CompletionException failure =
                assertThrows(CompletionException.class, () -> subscription.next().join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(subscription.isTerminated());
        assertFalse(subscription.cancel());
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void naturalCompletionRunsRuntimeCleanupExactlyOnce() {
        AtomicInteger cleanupCalls = new AtomicInteger();

        OresSubscription<Integer> subscription = new OresSubscription<>() {
            @Override
            protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                return OresFuture.completed(OresNotification.complete());
            }

            @Override
            protected void cancelFromRuntime() {
                cleanupCalls.incrementAndGet();
            }
        };

        assertTrue(subscription.next().join().isComplete());
        assertTrue(subscription.isTerminated());
        assertFalse(subscription.isCancelled());
        assertFalse(subscription.cancel());
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void cancellationAndProducerCompletionRaceStillTearsDownOnce() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
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
            java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);

            Thread producer = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                source.completeFromRuntime(OresNotification.next(7));
            });
            Thread canceller = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                subscription.cancel();
            });

            start.countDown();
            producer.join();
            canceller.join();

            assertTrue(subscription.isTerminated());
            assertEquals(1, cleanupCalls.get(),
                    "producer/cancel race must not duplicate runtime cleanup");

            if (!pull.isCancelled()) {
                assertEquals(7, pull.join().value());
            }
        }
    }

    @Test
    void observableRejectsNullRuntimeSubscription() {
        OresObservable<Integer> broken = new OresObservable<>() {
            @Override
            protected OresSubscription<Integer> subscribeFromRuntime() {
                return null;
            }
        };

        assertThrows(NullPointerException.class, broken::subscribe);
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
    void schedulerBoundMapPreservesUpstreamCancellation() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresSubscription<Integer> subscription =
                    OresObservable.fromFuture(source)
                            .map(scheduler, value -> value + 1)
                            .subscribe();

            OresFuture<OresNotification<Integer>> pull = subscription.next();
            assertTrue(source.cancel(true));

            assertThrows(
                    CancellationException.class,
                    () -> pull.get(5, TimeUnit.SECONDS));
            assertTrue(pull.isCancelled(),
                    "scheduler-bound map must preserve upstream cancellation identity");
            assertTrue(subscription.isTerminated());
        }
    }

    @Test
    void schedulerBoundFilterPreservesUpstreamCancellation() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresSubscription<Integer> subscription =
                    OresObservable.fromFuture(source)
                            .filter(scheduler, value -> true)
                            .subscribe();

            OresFuture<OresNotification<Integer>> pull = subscription.next();
            assertTrue(source.cancel(true));

            assertThrows(
                    CancellationException.class,
                    () -> pull.get(5, TimeUnit.SECONDS));
            assertTrue(pull.isCancelled(),
                    "scheduler-bound filter must preserve upstream cancellation identity");
            assertTrue(subscription.isTerminated());
        }
    }

    @Test
    void schedulerBoundMapDoesNotMisclassifyCancellationExceptionFailure() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source =
                    OresFuture.failed(new CancellationException("domain failure"));
            OresFuture<OresNotification<Integer>> pull =
                    OresObservable.fromFuture(source)
                            .map(scheduler, value -> value + 1)
                            .subscribe()
                            .next();

            CompletionException failure =
                    assertThrows(CompletionException.class, pull::join);
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertFalse(pull.isCancelled(),
                    "domain failure identity comes from Future state, not exception class");
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

    @Test
    void mapRejectsNullReactiveValues() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresSubscription<String> subscription =
                    OresObservable.fromValues(List.of("x"))
                            .<String>map(scheduler, ignored -> null)
                            .subscribe();

            CompletionException failure =
                    assertThrows(CompletionException.class, () -> subscription.next().join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("Option<T>"));
            assertTrue(subscription.isTerminated());
        }
    }

}
