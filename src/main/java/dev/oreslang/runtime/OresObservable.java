package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Native rx-ores observable substrate.
 *
 * <p>The initial runtime contract is intentionally pull-oriented. A subscriber
 * requests one item with {@link OresSubscription#next()}, receives an
 * {@link OresFuture}, and therefore composes with the exact same scheduler
 * suspension primitive as Oreslang {@code await}.</p>
 *
 * <p>Guest transform callbacks are exposed only through scheduler-bound
 * operators such as {@link #map(OresScheduler, Function)} and
 * {@link #filter(OresScheduler, Predicate)}. The callback itself executes as an
 * OresScheduler task turn; producer/timer/I/O/JNI completion threads only make
 * that task runnable and never execute guest transform code directly.</p>
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
     * Cold, replayable observable backed by an immutable subscription snapshot.
     */
    public static <T> OresObservable<T> fromValues(List<? extends T> values) {
        Objects.requireNonNull(values, "values");
        ArrayList<T> snapshot = new ArrayList<>(values.size());
        snapshot.addAll(values);
        List<T> immutable = Collections.unmodifiableList(snapshot);

        return new OresObservable<>() {
            @Override
            protected OresSubscription<T> subscribeFromRuntime() {
                return new OresSubscription<>() {
                    private int index;

                    @Override
                    protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        if (index >= immutable.size()) {
                            return OresFuture.completed(OresNotification.complete());
                        }
                        T value = immutable.get(index++);
                        return OresFuture.completed(OresNotification.next(value));
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
     * Transform each element on the supplied Ores scheduler.
     *
     * <p>The mapper is never invoked from an upstream completion thread. Each
     * pull awaits the upstream Future through an OresScheduler task, then runs
     * the mapper only after that task is resumed on the scheduler.</p>
     */
    public final <R> OresObservable<R> map(
            OresScheduler scheduler,
            Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(mapper, "mapper");

        OresObservable<T> upstream = this;
        return new OresObservable<>() {
            @Override
            protected OresSubscription<R> subscribeFromRuntime() {
                OresSubscription<T> inner = upstream.subscribe();
                AtomicReference<OresFuture<?>> activeUpstreamPull =
                        new AtomicReference<>();

                return new OresSubscription<>() {
                    @Override
                    protected OresFuture<OresNotification<R>> nextFromRuntime() {
                        OresFuture<OresNotification<R>> task = scheduler.start(
                                new OresScheduler.Task<>() {
                                    private boolean awaiting;

                                    @Override
                                    public OresScheduler.Step<OresNotification<R>> resume(
                                            OresScheduler.Resume resume) {
                                        if (!awaiting) {
                                            if (!resume.initial()) {
                                                throw new IllegalStateException(
                                                        "rx map task resumed before its first await");
                                            }
                                            awaiting = true;
                                            OresFuture<OresNotification<T>> pull = inner.next();
                                            activeUpstreamPull.set(pull);
                                            return OresScheduler.await(pull);
                                        }

                                        if (resume.initial()) {
                                            throw new IllegalStateException(
                                                    "rx map task resumed as initial after awaiting upstream");
                                        }
                                        if (resume.failure() != null) {
                                            throw propagate(resume.failure());
                                        }

                                        @SuppressWarnings("unchecked")
                                        OresNotification<T> notification =
                                                (OresNotification<T>) resume.value();
                                        if (notification == null) {
                                            throw new IllegalStateException(
                                                    "rx map upstream returned null notification");
                                        }
                                        if (notification.isComplete()) {
                                            return OresScheduler.done(
                                                    OresNotification.complete());
                                        }

                                        R mapped = mapper.apply(notification.value());
                                        return OresScheduler.done(
                                                OresNotification.next(mapped));
                                    }
                                });

                        return bindOperatorCancellation(
                                task,
                                inner,
                                activeUpstreamPull);
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
     * Keep only elements whose predicate returns true, evaluating the predicate
     * on the supplied Ores scheduler.
     *
     * <p>A rejected element yields through another scheduler await before the
     * next predicate evaluation. Even an immediately available cold source
     * therefore cannot recurse inline or monopolize a producer thread.</p>
     */
    public final OresObservable<T> filter(
            OresScheduler scheduler,
            Predicate<? super T> predicate) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(predicate, "predicate");

        OresObservable<T> upstream = this;
        return new OresObservable<>() {
            @Override
            protected OresSubscription<T> subscribeFromRuntime() {
                OresSubscription<T> inner = upstream.subscribe();
                AtomicReference<OresFuture<?>> activeUpstreamPull =
                        new AtomicReference<>();

                return new OresSubscription<>() {
                    @Override
                    protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        OresFuture<OresNotification<T>> task = scheduler.start(
                                new OresScheduler.Task<>() {
                                    private boolean awaiting;

                                    @Override
                                    public OresScheduler.Step<OresNotification<T>> resume(
                                            OresScheduler.Resume resume) {
                                        if (!awaiting) {
                                            if (!resume.initial()) {
                                                throw new IllegalStateException(
                                                        "rx filter task resumed before its first await");
                                            }
                                            awaiting = true;
                                            OresFuture<OresNotification<T>> pull = inner.next();
                                            activeUpstreamPull.set(pull);
                                            return OresScheduler.await(pull);
                                        }

                                        if (resume.initial()) {
                                            throw new IllegalStateException(
                                                    "rx filter task resumed as initial after awaiting upstream");
                                        }
                                        if (resume.failure() != null) {
                                            throw propagate(resume.failure());
                                        }

                                        @SuppressWarnings("unchecked")
                                        OresNotification<T> notification =
                                                (OresNotification<T>) resume.value();
                                        if (notification == null) {
                                            throw new IllegalStateException(
                                                    "rx filter upstream returned null notification");
                                        }
                                        if (notification.isComplete()) {
                                            return OresScheduler.done(notification);
                                        }
                                        if (predicate.test(notification.value())) {
                                            return OresScheduler.done(notification);
                                        }

                                        OresFuture<OresNotification<T>> pull = inner.next();
                                        activeUpstreamPull.set(pull);
                                        return OresScheduler.await(pull);
                                    }
                                });

                        return bindOperatorCancellation(
                                task,
                                inner,
                                activeUpstreamPull);
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


    private static RuntimeException propagate(Throwable failure) {
        Throwable unwrapped = OresFuture.unwrap(failure);
        if (unwrapped instanceof RuntimeException runtime) return runtime;
        if (unwrapped instanceof Error error) throw error;
        return new RuntimeException(unwrapped);
    }

    /**
     * Tie one operator task Future to its upstream subscription so cancelling a
     * pull cannot leave an outstanding upstream next() permanently armed.
     */
    private static <T> OresFuture<T> bindOperatorCancellation(
            OresFuture<T> task,
            OresSubscription<?> upstream,
            AtomicReference<OresFuture<?>> activeUpstreamPull) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(activeUpstreamPull, "activeUpstreamPull");

        AtomicReference<OresFuture.RuntimeWaiterRegistration> waiter =
                new AtomicReference<>();

        Runnable detach = () -> {
            OresFuture.RuntimeWaiterRegistration registration =
                    waiter.getAndSet(null);
            if (registration != null) registration.detach();
        };

        Runnable release = () -> {
            detach.run();
            activeUpstreamPull.set(null);
        };

        OresFuture<T> exposed = new OresFuture<>(() -> {
            release.run();
            task.cancel(true);
            upstream.cancel();
        });

        OresFuture.RuntimeWaiterRegistration registration =
                task.whenCompleteRuntimeCancellable((value, failure) -> {
                    try {
                        if (exposed.isDone()) return;
                        if (failure == null) {
                            exposed.completeFromRuntime(value);
                        } else {
                            OresFuture<?> awaited = activeUpstreamPull.get();
                            if (task.isCancelled()
                                    || (awaited != null && awaited.isCancelled())) {
                                exposed.cancel(true);
                            } else {
                                exposed.failFromRuntime(OresFuture.unwrap(failure));
                            }
                        }
                    } finally {
                        release.run();
                    }
                });

        waiter.set(registration);
        if (exposed.isDone()) {
            release.run();
        }
        return exposed;
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

        AtomicReference<OresFuture.RuntimeWaiterRegistration> waiter =
                new AtomicReference<>();

        Runnable detachOnly = () -> {
            OresFuture.RuntimeWaiterRegistration registration =
                    waiter.getAndSet(null);
            if (registration != null) registration.detach();
        };
        Runnable cancelHook = propagateCancellation
                ? () -> {
                    detachOnly.run();
                    source.cancel(true);
                }
                : detachOnly;

        OresFuture<O> result = new OresFuture<>(cancelHook);

        OresFuture.RuntimeWaiterRegistration registration =
                source.whenCompleteRuntimeCancellable((value, failure) -> {
                    try {
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
                    } finally {
                        detachOnly.run();
                    }
                });

        waiter.set(registration);
        if (result.isDone()) {
            OresFuture.RuntimeWaiterRegistration completed =
                    waiter.getAndSet(null);
            if (completed != null) completed.detach();
        }

        return result;
    }
}
