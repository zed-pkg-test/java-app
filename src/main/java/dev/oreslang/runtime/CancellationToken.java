package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime-owned, thread-safe cancellation readiness token.
 *
 * <p>The token is a broadcast signal: once cancelled it remains cancelled.
 * Select registrations attach runtime-only waiters and detach them when another
 * arm wins. Guest code may request cancellation and observe state, but callbacks
 * are strictly runtime plumbing and never execute Oreslang guest code inline.</p>
 */
public final class CancellationToken {
    static final class Registration {
        private final CancellationToken owner;
        private final Runnable callback;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private Registration(CancellationToken owner, Runnable callback) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.callback = Objects.requireNonNull(callback, "callback");
        }

        boolean detach() {
            if (!claimed.compareAndSet(false, true)) return false;
            owner.waiters.remove(this);
            return true;
        }

        private void signal() {
            if (!claimed.compareAndSet(false, true)) return;
            callback.run();
        }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Registration> waiters =
            new ConcurrentLinkedQueue<>();

    /** Returns true only for the transition that first cancels this token. */
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        Registration registration;
        while ((registration = waiters.poll()) != null) {
            registration.signal();
        }
        return true;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    Registration whenCancelledRuntime(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        Registration registration = new Registration(this, callback);
        waiters.add(registration);
        if (cancelled.get()) {
            waiters.remove(registration);
            registration.signal();
        }
        return registration;
    }

    int pendingRuntimeWaiterCount() {
        return waiters.size();
    }
}
