package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

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

        assertTrue(one.cancel());
        assertFalse(source.isCancelled(),
                "one rx subscriber must not cancel a shared source Future");

        source.completeFromRuntime(99);

        assertThrows(CancellationException.class, first::join);
        assertEquals(99, second.join().value());
    }

    @Test
    void cancellingSharedFutureSubscriptionDetachesWithoutCancellingProducer()
            throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        OresSubscription<Integer> subscription =
                OresObservable.fromFuture(source).subscribe();

        OresFuture<OresNotification<Integer>> pull = subscription.next();
        assertTrue(subscription.cancel());
        assertTrue(pull.isCancelled());
        assertFalse(source.isCancelled());

        var waitersField = OresFuture.class.getDeclaredField("waiters");
        waitersField.setAccessible(true);
        var waiters = (java.util.concurrent.ConcurrentLinkedQueue<?>)
                waitersField.get(source);
        assertTrue(
                waiters.isEmpty(),
                "cancelled subscription must detach from a shared producer Future");
    }

    @Test
    void sharedFutureCancellationRemainsCancellationForPulledDemand() {
        OresFuture<Integer> source = new OresFuture<>();
        OresSubscription<Integer> subscription =
                OresObservable.fromFuture(source).subscribe();

        OresFuture<OresNotification<Integer>> pull = subscription.next();
        assertTrue(source.cancel(true));

        assertTrue(pull.isCancelled(),
                "upstream cancellation must not be converted into an ordinary stream failure");
        assertThrows(CancellationException.class, pull::join);
        assertTrue(subscription.isTerminated());
        assertFalse(subscription.isCancelled(),
                "the source cancelled; the subscriber did not explicitly cancel itself");
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
                "cancellation after terminal source failure must not reopen teardown");
    }

    @Test
    void subscriptionRuntimeCleanupRunsExactlyOnceAcrossCancellationCascade() {
        AtomicInteger cleanups = new AtomicInteger();
        OresFuture<OresNotification<Integer>> pending = new OresFuture<>();

        OresSubscription<Integer> subscription = new OresSubscription<>() {
            @Override
            protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                return pending;
            }

            @Override
            protected void cancelFromRuntime() {
                cleanups.incrementAndGet();
            }
        };

        OresFuture<OresNotification<Integer>> pull = subscription.next();
        assertTrue(subscription.cancel());
        assertThrows(CancellationException.class, pull::join);
        assertFalse(subscription.cancel());

        assertEquals(1, cleanups.get(),
                "subscription cleanup must be exactly-once even when cancelling the active pull");
    }

    @Test
    void synchronousSourceFailureStillRunsRuntimeCleanupExactlyOnce() {
        AtomicInteger cleanups = new AtomicInteger();

        OresSubscription<Integer> subscription = new OresSubscription<>() {
            @Override
            protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                throw new IllegalStateException("source construction failed");
            }

            @Override
            protected void cancelFromRuntime() {
                cleanups.incrementAndGet();
            }
        };

        CompletionException failure =
                assertThrows(CompletionException.class, () -> subscription.next().join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(subscription.isTerminated());
        assertFalse(subscription.cancel());
        assertEquals(1, cleanups.get());
    }

    @Test
    void naturalCompletionRunsRuntimeCleanupExactlyOnce() {
        AtomicInteger cleanups = new AtomicInteger();

        OresSubscription<Integer> subscription = new OresSubscription<>() {
            @Override
            protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                return OresFuture.completed(OresNotification.complete());
            }

            @Override
            protected void cancelFromRuntime() {
                cleanups.incrementAndGet();
            }
        };

        assertTrue(subscription.next().join().isComplete());
        assertTrue(subscription.isTerminated());
        assertFalse(subscription.isCancelled());
        assertFalse(subscription.cancel());
        assertEquals(1, cleanups.get());
    }

    @Test
    void cancellationAndProducerCompletionRaceStillTearsDownOnce() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            AtomicInteger cleanups = new AtomicInteger();
            OresFuture<OresNotification<Integer>> pending = new OresFuture<>();

            OresSubscription<Integer> subscription = new OresSubscription<>() {
                @Override
                protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                    return pending;
                }

                @Override
                protected void cancelFromRuntime() {
                    cleanups.incrementAndGet();
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
                pending.completeFromRuntime(OresNotification.next(7));
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
            assertEquals(1, cleanups.get(),
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
    void publicObservableSurfaceDoesNotExposeGuestCallbackSubscribe() {
        assertFalse(
                java.util.Arrays.stream(OresObservable.class.getMethods())
                        .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                        .anyMatch(type -> java.util.function.Consumer.class.isAssignableFrom(type)
                                || java.util.function.Function.class.isAssignableFrom(type)),
                "initial rx-ores surface must not run guest callbacks on producer threads");
    }
    @Test
    void cancellingSharedFutureSubscriptionDetachesProducerWaiter() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        OresSubscription<Integer> subscription =
                OresObservable.fromFuture(source).subscribe();

        OresFuture<OresNotification<Integer>> pull = subscription.next();
        assertFalse(runtimeWaiters(source).isEmpty());

        assertTrue(subscription.cancel());
        assertThrows(CancellationException.class, pull::join);
        assertFalse(source.isCancelled(),
                "detaching a subscriber must not cancel the shared producer");
        assertTrue(runtimeWaiters(source).isEmpty(),
                "cancelled subscriber must not stay rooted by a never-settling shared Future");
    }

    private static java.util.concurrent.ConcurrentLinkedQueue<?> runtimeWaiters(
            OresFuture<?> future) throws Exception {
        var field = OresFuture.class.getDeclaredField("waiters");
        field.setAccessible(true);
        return (java.util.concurrent.ConcurrentLinkedQueue<?>) field.get(future);
    }


}
