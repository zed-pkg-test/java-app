package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Pull-oriented reactive subscription used by rx-ores.
 *
 * <p>Exactly one pull may be outstanding at a time. That gives the initial
 * native implementation real backpressure without an additional demand
 * protocol: one {@link #next()} call admits at most one element.</p>
 *
 * <p>The returned {@link OresFuture} is the suspension boundary. Producer
 * threads only settle Futures; they never invoke guest callbacks directly.</p>
 */
public abstract class OresSubscription<T> {
    private final Object gate = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean runtimeCancelled = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile java.util.function.Consumer<Runnable> cancellationDispatcher = Runnable::run;

    final void bindCancellationDispatcher(java.util.function.Consumer<Runnable> dispatcher) {
        cancellationDispatcher = Objects.requireNonNull(dispatcher);
    }

    private boolean cancelled;
    private boolean terminal;
    private boolean pulling;
    private OresFuture<OresNotification<T>> active;
    private Reader<T> reader;

    /**
     * Acquires an exclusive logical reader lease for this subscription.
     * Other subscriptions to an Observable are independent: this is not a
     * global or OS read/write lock.
     */
    public final Reader<T> getReader() {
        synchronized (gate) {
            if (reader != null) {
                throw new IllegalStateException("subscription already has an active reader");
            }
            if (pulling) {
                throw new IllegalStateException("cannot acquire reader while a pull is outstanding");
            }
            reader = new Reader<>(this);
            return reader;
        }
    }

    public final boolean isLocked() {
        synchronized (gate) {
            return reader != null;
        }
    }

    private void releaseReader(Reader<T> lease) {
        synchronized (gate) {
            if (reader != lease) {
                throw new IllegalStateException("reader lease has already been released");
            }
            if (pulling) {
                throw new IllegalStateException("cannot release reader with an outstanding next()");
            }
            reader = null;
        }
    }

    /** A revocable, exclusive consumer capability; release does not cancel the source. */
    public static final class Reader<T> implements AutoCloseable {
        private final OresSubscription<T> subscription;

        private Reader(OresSubscription<T> subscription) {
            this.subscription = subscription;
        }

        public OresFuture<OresNotification<T>> next() {
            return subscription.nextWithReader(this);
        }

        /** Cancel the subscription and its outstanding pull, not merely this lease. */
        public boolean cancel() {
            return subscription.cancelWithReader(this);
        }

        /** Refuses release while a read is pending; cancellation is a separate action. */
        public void releaseLock() {
            subscription.releaseReader(this);
        }

        @Override
        public void close() {
            releaseLock();
        }
    }

    /**
     * Request exactly one next stream notification.
     *
     * <p>Calling next concurrently is a programming error. After cancellation
     * or terminal completion, next returns COMPLETE.</p>
     */
    public final OresFuture<OresNotification<T>> next() {
        return nextWithReader(null);
    }

    private OresFuture<OresNotification<T>> nextWithReader(Reader<T> lease) {
        synchronized (gate) {
            if (reader != lease) {
                return OresFuture.failed(new IllegalStateException(
                        "subscription reader lock is held or the reader was released"));
            }
            if (cancelled || terminal) {
                return OresFuture.completed(OresNotification.complete());
            }
            if (pulling) {
                return OresFuture.failed(new IllegalStateException(
                        "rx-ores subscription already has an outstanding next()"));
            }
            pulling = true;
        }

        final OresFuture<OresNotification<T>> source;
        try {
            source = Objects.requireNonNull(
                    nextFromRuntime(),
                    "nextFromRuntime returned null Future");
        } catch (Throwable failure) {
            boolean wasCancelled;
            synchronized (gate) {
                wasCancelled = cancelled;
                pulling = false;
                terminal = true;
            }
            // Synchronous producer failure must also run source cleanup once.
            try {
                cancelRuntimeOnce();
            } catch (RuntimeException | Error ignored) {
                // Preserve the original source failure.
            }
            return wasCancelled
                    ? OresFuture.completed(OresNotification.complete())
                    : OresFuture.failed(failure);
        }

        OresFuture<OresNotification<T>> exposed =
                new OresFuture<>(() -> source.cancel(true));

        boolean aborted;
        synchronized (gate) {
            aborted = cancelled || terminal;
            if (aborted) {
                pulling = false;
            } else {
                active = exposed;
            }
        }
        // Never invoke a source cancellation hook while holding the gate.
        if (aborted) {
            source.cancel(true);
            return OresFuture.completed(OresNotification.complete());
        }

        source.whenCompleteRuntime((notification, failure) -> {
            boolean cleanup = failure != null || notification == null
                    || notification.isComplete() || source.isCancelled();
            Throwable effectiveFailure = failure;
            if (failure == null && notification == null) {
                effectiveFailure = new IllegalStateException(
                        "rx-ores source completed a pull with null notification");
            }

            synchronized (gate) {
                if (cancelled) {
                    // Subscription cancellation has already won.
                    if (active == exposed) active = null;
                    pulling = false;
                    return;
                }
                // Keep the outstanding-pull slot occupied while cleanup is
                // enqueued: actor shutdown must not overtake source cleanup.
                if (cleanup) terminal = true;
            }

            if (cleanup) {
                try {
                    cancelRuntimeOnce();
                } catch (RuntimeException | Error ignored) {
                    // Preserve the source's terminal value/failure.
                }
            }

            synchronized (gate) {
                if (cancelled) {
                    if (active == exposed) active = null;
                    pulling = false;
                    return;
                }
                // Settle before releasing the gate so a waking consumer
                // cannot observe a completed Future and an occupied slot.
                active = null;
                pulling = false;
                if (source.isCancelled()) {
                    exposed.cancel(false);
                } else if (effectiveFailure == null) {
                    exposed.completeFromRuntime(notification);
                } else {
                    exposed.failFromRuntime(OresFuture.unwrap(effectiveFailure));
                }
            }
        });

        return exposed;
    }

    /**
     * Cancel this subscription and any currently outstanding pull.
     */
    public final boolean cancel() {
        return cancelWithReader(null);
    }

    /** Null is the controlling subscription itself, which can always cancel. */
    private boolean cancelWithReader(Reader<T> lease) {
        OresFuture<OresNotification<T>> toCancel;
        synchronized (gate) {
            if (lease != null && reader != lease) {
                throw new IllegalStateException("reader lease has been released");
            }
            if (cancelled) return false;
            cancelled = true;
            terminal = true;
            pulling = false;
            toCancel = active;
            active = null;
        }

        if (toCancel != null) toCancel.cancel(true);
        cancelRuntimeOnce();
        return true;
    }

    private void cancelRuntimeOnce() {
        if (runtimeCancelled.compareAndSet(false, true)) cancellationDispatcher.accept(this::cancelFromRuntime);
    }

    public final boolean isCancelled() {
        synchronized (gate) {
            return cancelled;
        }
    }

    public final boolean isTerminated() {
        synchronized (gate) {
            return terminal;
        }
    }

    /**
     * Runtime source implementation. It must not execute guest code from an
     * arbitrary producer thread.
     */
    protected abstract OresFuture<OresNotification<T>> nextFromRuntime();

    /**
     * Runtime cancellation hook. Implementations should be idempotent.
     */
    protected void cancelFromRuntime() {
        // Default no-op.
    }
}
