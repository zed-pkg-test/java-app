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

    private boolean cancelled;
    private boolean terminal;
    private boolean pulling;
    private boolean runtimeCancelIssued;
    private OresFuture<OresNotification<T>> active;

    /**
     * Request exactly one next stream notification.
     *
     * <p>Calling next concurrently is a programming error. After cancellation
     * or terminal completion, next returns COMPLETE. Cancelling the returned
     * pull Future is terminal for this subscription: cancelled demand is not
     * silently retried or replaced.</p>
     */
    public final OresFuture<OresNotification<T>> next() {
        synchronized (gate) {
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
            synchronized (gate) {
                pulling = false;
                terminal = true;
            }
            cancelRuntimeOnce();
            return OresFuture.failed(failure);
        }

        OresFuture<OresNotification<T>> exposed =
                new OresFuture<>(() -> source.cancel(true));

        boolean rejectPull;
        synchronized (gate) {
            rejectPull = cancelled || terminal;
            if (rejectPull) {
                pulling = false;
            } else {
                active = exposed;
            }
        }

        if (rejectPull) {
            source.cancel(true);
            cancelRuntimeOnce();
            return OresFuture.completed(OresNotification.complete());
        }

        source.whenCompleteRuntime((notification, failure) -> {
            Throwable terminalFailure =
                    failure == null ? null : OresFuture.unwrap(failure);
            boolean terminalTransition = false;

            synchronized (gate) {
                // Keep active/pulling claimed until the exposed Future itself
                // is terminal. Releasing the slot before publication would let
                // another next() overlap the previous demand and could let a
                // concurrent cancel() miss the in-flight pull.
                if (terminalFailure != null) {
                    terminal = true;
                    terminalTransition = true;
                } else if (notification == null) {
                    terminal = true;
                    terminalTransition = true;
                    terminalFailure = new IllegalStateException(
                            "rx-ores source completed a pull with null notification");
                } else if (notification.isComplete()) {
                    terminal = true;
                    terminalTransition = true;
                }
            }

            if (terminalTransition) {
                cancelRuntimeOnce();
            }

            if (!exposed.isDone()) {
                if (terminalFailure == null) {
                    exposed.completeFromRuntime(notification);
                } else if (source.isCancelled()) {
                    exposed.cancel(true);
                } else {
                    exposed.failFromRuntime(terminalFailure);
                }
            }

            synchronized (gate) {
                if (active == exposed) {
                    active = null;
                }
                pulling = false;
            }
        });

        return exposed;
    }

    /**
     * Cancel this subscription and any currently outstanding pull.
     *
     * <p>Cancellation wins only while the subscription is live. Cancelling an
     * already-terminal subscription returns false. Runtime cleanup remains
     * exactly-once across cancellation, completion, failure, and races.</p>
     */
    public final boolean cancel() {
        OresFuture<OresNotification<T>> toCancel;
        synchronized (gate) {
            if (cancelled || terminal) {
                return false;
            }
            cancelled = true;
            terminal = true;
            pulling = false;
            toCancel = active;
            active = null;
        }

        if (toCancel != null) {
            toCancel.cancel(true);
        }
        cancelRuntimeOnce();
        return true;
    }

    private void cancelRuntimeOnce() {
        synchronized (gate) {
            if (runtimeCancelIssued) return;
            runtimeCancelIssued = true;
        }

        try {
            cancelFromRuntime();
        } catch (RuntimeException | Error ignored) {
            // Cancellation/terminal state is already authoritative. Runtime
            // cleanup hooks must not roll it back or execute guest code.
        }
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
     * Runtime source cleanup/cancellation hook.
     *
     * <p>The subscription substrate invokes this hook at most once. It may be
     * triggered by explicit cancellation, terminal COMPLETE, source failure,
     * invalid source output, or pull cancellation. It must not execute guest
     * code.</p>
     */
    protected void cancelFromRuntime() {
        // Default no-op.
    }
}
