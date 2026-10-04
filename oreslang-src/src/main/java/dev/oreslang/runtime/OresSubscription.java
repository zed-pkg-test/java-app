package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final AtomicBoolean runtimeCancelHookRun = new AtomicBoolean();

    private boolean cancelled;
    private boolean terminal;
    private boolean pulling;
    private OresFuture<OresNotification<T>> active;

    /**
     * Request exactly one next stream notification.
     *
     * <p>Calling next concurrently is a programming error. After cancellation
     * or terminal completion, next returns COMPLETE. Cancelling the returned
     * pull Future is terminal for this subscription: a cancelled demand is not
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
            runCancelFromRuntimeOnce();
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
            runCancelFromRuntimeOnce();
            return OresFuture.completed(OresNotification.complete());
        }

        source.whenCompleteRuntime((notification, failure) -> {
            Throwable terminalFailure =
                    failure == null ? null : OresFuture.unwrap(failure);
            boolean terminalTransition = false;

            synchronized (gate) {
                // Keep active/pulling claimed until the exposed Future is
                // actually settled. Releasing the slot here would let a
                // concurrent next() start before the previous pull is done and
                // would let cancel() miss an in-flight exposed pull.
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
                runCancelFromRuntimeOnce();
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
     * already-terminal subscription returns false, matching ordinary Future
     * cancellation semantics. The runtime source hook is invoked at most once
     * across cancellation, completion, failure, and cancellation races.</p>
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
        runCancelFromRuntimeOnce();
        return true;
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
     * triggered by explicit cancellation, terminal COMPLETE, source failure, an
     * invalid source pull, or cancellation of the outstanding pull Future.
     * Implementations must not execute guest code here.</p>
     */
    protected void cancelFromRuntime() {
        // Default no-op.
    }

    private void runCancelFromRuntimeOnce() {
        if (!runtimeCancelHookRun.compareAndSet(false, true)) {
            return;
        }
        try {
            cancelFromRuntime();
        } catch (RuntimeException | Error ignored) {
            // Subscription terminal state is already authoritative. Runtime
            // cleanup failure cannot reopen the stream or duplicate teardown.
        }
    }
}
