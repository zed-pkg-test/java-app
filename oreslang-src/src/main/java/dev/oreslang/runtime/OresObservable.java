package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Native rx-ores observable substrate.
 *
 * <p>The initial runtime contract is intentionally pull-oriented. A subscriber
 * requests one item with {@link OresSubscription#next()}, receives an
 * {@link OresFuture}, and therefore composes with the exact same scheduler
 * suspension primitive as Oreslang {@code await}.</p>
 *
 * <p>This class deliberately does not expose Consumer/Function callback-style
 * subscribe/map/filter APIs yet. Guest callbacks must execute on the owning Ores
 * scheduler domain, not on whichever timer/I/O/JNI thread happens to settle a
 * Future. Higher-order operators will be enabled once source lowering can bind
 * those lambdas to resumable Ores tasks safely.</p>
 *
 * <p><strong>Linking boundary:</strong> rx-ores is a core library, not an
 * implicit runtime dependency. Base OresVM/runtime initialization must not
 * eagerly register or instantiate this class merely to advertise RX support.
 * The standard-library linker/source lowering should make the RX runtime
 * reachable only for programs that explicitly import the RX core library.
 * This keeps closed-world AOT reachability capable of removing the entire RX
 * substrate from applications that do not use it.</p>
 */
public abstract class OresObservable<T> {

    /**
     * Create one independent subscription.
     *
     * <p>The public entrypoint is final so runtime sources cannot bypass the
     * non-null subscription invariant.</p>
     */
    public final OresSubscription<T> subscribe() {
        return Objects.requireNonNull(
                subscribeFromRuntime(),
                "subscribeFromRuntime returned null Subscription");
    }

    protected abstract OresSubscription<T> subscribeFromRuntime();

    public static <T> OresObservable<T> empty() {
        return new OresObservable<>() {
            @Override
            protected OresSubscription<T> subscribeFromRuntime() {
                return new OresSubscription<>() {
                    @Override
                    protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        return OresFuture.completed(OresNotification.complete());
                    }
                };
            }
        };
    }

    public static <T> OresObservable<T> just(T value) {
        return fromValues(Collections.singletonList(value));
    }

    /**
     * Cold, replayable observable backed by an immutable construction-time
     * snapshot.
     *
     * <p>This is the strongest finite-value bridge: later mutation of the host
     * list cannot change any subscription. Null values are rejected because
     * standalone null is not an Oreslang value.</p>
     */
    public static <T> OresObservable<T> fromValues(List<? extends T> values) {
        Objects.requireNonNull(values, "values");
        ArrayList<T> snapshot = new ArrayList<>(values.size());
        for (T value : values) {
            snapshot.add(Objects.requireNonNull(
                    value,
                    "rx-ores finite sources cannot contain null values"));
        }
        return fromIterable(Collections.unmodifiableList(snapshot));
    }

    /**
     * Adapt any pull iterable into a cold rx-ores source.
     *
     * <p>Each subscription obtains its own fresh iterator and values are read
     * lazily, one element per {@link OresSubscription#next()} demand. The
     * iterable therefore defines the snapshot policy. This is the intended
     * collection interop boundary: Oreslang List/ArrayList/Vector expose
     * {@code Symbol.iterator}; their iterator implementation may return a
     * stable collection snapshot without making rx-ores depend on a particular
     * collection class.</p>
     *
     * <p>Iterator construction/iteration failures become failed pull Futures,
     * not producer-thread guest callbacks. Null iterators and null elements are
     * rejected and terminate that subscription.</p>
     */
    public static <T> OresObservable<T> fromIterable(Iterable<? extends T> values) {
        Objects.requireNonNull(values, "values");

        return new OresObservable<>() {
            @Override
            protected OresSubscription<T> subscribeFromRuntime() {
                return new OresSubscription<>() {
                    private Iterator<? extends T> iterator;
                    private boolean iteratorInitialized;

                    @Override
                    protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        try {
                            if (!iteratorInitialized) {
                                iterator = Objects.requireNonNull(
                                        values.iterator(),
                                        "rx-ores iterable returned null iterator");
                                iteratorInitialized = true;
                            }
                            if (!iterator.hasNext()) {
                                return OresFuture.completed(OresNotification.complete());
                            }
                            T value = Objects.requireNonNull(
                                    iterator.next(),
                                    "rx-ores iterable emitted null");
                            return OresFuture.completed(OresNotification.next(value));
                        } catch (Throwable failure) {
                            return OresFuture.failed(failure);
                        }
                    }
                };
            }
        };
    }

    /**
     * Adapt one shared Future into a single-item observable.
     *
     * <p>Subscription cancellation does not cancel the supplied Future because
     * another subscriber may be observing the same operation. Use an owned
     * source primitive in a later rx-ores layer when per-subscription producer
     * lifetime is required.</p>
     */
    public static <T> OresObservable<T> fromFuture(OresFuture<? extends T> future) {
        Objects.requireNonNull(future, "future");

        return new OresObservable<>() {
            @Override
            protected OresSubscription<T> subscribeFromRuntime() {
                return new OresSubscription<>() {
                    private boolean emitted;

                    @Override
                    protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        if (emitted) {
                            return OresFuture.completed(OresNotification.complete());
                        }
                        emitted = true;
                        return mapRuntime(
                                future,
                                OresNotification::next,
                                false);
                    }
                };
            }
        };
    }

    /**
     * Emit at most {@code limit} items, then cancel the upstream subscription.
     */
    public final OresObservable<T> take(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        if (limit == 0) {
            return empty();
        }

        OresObservable<T> upstream = this;
        return new OresObservable<>() {
            @Override
            protected OresSubscription<T> subscribeFromRuntime() {
                OresSubscription<T> inner = upstream.subscribe();

                return new OresSubscription<>() {
                    private int remaining = limit;
                    private boolean cutOff;

                    @Override
                    protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        if (cutOff || remaining == 0) {
                            return OresFuture.completed(OresNotification.complete());
                        }

                        return mapRuntime(inner.next(), notification -> {
                            if (notification.isComplete()) {
                                cutOff = true;
                                return notification;
                            }

                            remaining--;
                            if (remaining == 0) {
                                cutOff = true;
                                inner.cancel();
                            }
                            return notification;
                        }, true);
                    }

                    @Override
                    protected void cancelFromRuntime() {
                        inner.cancel();
                    }
                };
            }
        };
    }

    /**
     * Bridge the first item back to the ordinary Ores Future world.
     */
    public final OresFuture<T> first() {
        OresSubscription<T> subscription = subscribe();
        OresFuture<T> result = new OresFuture<>(subscription::cancel);

        OresFuture<OresNotification<T>> pull = subscription.next();
        pull.whenCompleteRuntime((notification, failure) -> {
            if (result.isDone()) {
                return;
            }
            if (failure != null) {
                Throwable terminalFailure = OresFuture.unwrap(failure);
                if (pull.isCancelled()) {
                    result.cancel(true);
                } else {
                    result.failFromRuntime(terminalFailure);
                }
                subscription.cancel();
                return;
            }
            if (notification == null || notification.isComplete()) {
                result.failFromRuntime(new NoSuchElementException(
                        "rx-ores first() observed an empty stream"));
                subscription.cancel();
                return;
            }

            result.completeFromRuntime(notification.value());
            subscription.cancel();
        });

        return result;
    }

    /**
     * Runtime-only Future transformation. The mapper must be trusted runtime
     * plumbing, never an arbitrary Oreslang guest callback.
     */
    private static <I, O> OresFuture<O> mapRuntime(
            OresFuture<? extends I> source,
            Function<? super I, ? extends O> mapper,
            boolean propagateCancellation) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mapper, "mapper");

        OresFuture<O> result = propagateCancellation
                ? new OresFuture<>(() -> source.cancel(true))
                : new OresFuture<>();

        source.whenCompleteRuntime((value, failure) -> {
            if (result.isDone()) {
                return;
            }
            if (failure != null) {
                Throwable terminalFailure = OresFuture.unwrap(failure);
                if (source.isCancelled()) {
                    result.cancel(true);
                } else {
                    result.failFromRuntime(terminalFailure);
                }
                return;
            }
            try {
                result.completeFromRuntime(mapper.apply(value));
            } catch (Throwable mappingFailure) {
                result.failFromRuntime(mappingFailure);
            }
        });

        return result;
    }
}
