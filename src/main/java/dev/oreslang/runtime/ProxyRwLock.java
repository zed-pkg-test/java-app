package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fair logical read/write lock for scheduler-owned capabilities.
 *
 * <p>Unlike {@code ReentrantReadWriteLock}, ownership is represented by an
 * explicit lease rather than JVM-thread identity. A pending acquisition is an
 * {@link OresFuture}; therefore actor/source schedulers can suspend the logical
 * task and release the physical carrier while waiting.</p>
 *
 * <p>Fairness is FIFO at the waiter boundary. Consecutive readers at the head
 * are granted as a batch; a queued writer prevents later readers from barging.
 * Future cancellation removes a queued waiter. Completing a grant never runs
 * guest code: it only settles the runtime future.</p>
 */
final class ProxyRwLock {
    final class Lease implements AutoCloseable {
        private final boolean write;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(boolean write) {
            this.write = write;
        }

        boolean write() {
            return write;
        }

        boolean released() {
            return released.get();
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release(write);
            }
        }
    }

    private final class Waiter {
        private final boolean write;
        private final OresFuture<Lease> future;

        private Waiter(boolean write, OresFuture<Lease> future) {
            this.write = write;
            this.future = future;
        }
    }

    private record Grant(Waiter waiter, Lease lease) { }

    private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
    private int activeReaders;
    private boolean writerActive;

    OresFuture<Lease> acquireAsync(boolean write) {
        AtomicReference<Waiter> waiterRef = new AtomicReference<>();
        OresFuture<Lease> future = new OresFuture<>(() -> {
            Waiter waiter = waiterRef.get();
            if (waiter != null) cancelWaiter(waiter);
        });
        Waiter waiter = new Waiter(write, future);
        waiterRef.set(waiter);

        Lease immediate = null;
        synchronized (this) {
            if (waiters.isEmpty() && canGrantNow(write)) {
                markGranted(write);
                immediate = new Lease(write);
            } else {
                waiters.addLast(waiter);
            }
        }

        if (immediate != null && !future.completeFromRuntime(immediate)) {
            immediate.close();
        }
        return future;
    }

    int queuedWaiters() {
        synchronized (this) {
            return waiters.size();
        }
    }

    int activeReaders() {
        synchronized (this) {
            return activeReaders;
        }
    }

    boolean writerActive() {
        synchronized (this) {
            return writerActive;
        }
    }

    /**
     * Fail all waiters that have not yet been granted. Active lease holders are
     * not force-revoked; their normal finally/close path still releases state.
     */
    void failWaiters(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        List<Waiter> failed;
        synchronized (this) {
            failed = new ArrayList<>(waiters);
            waiters.clear();
        }
        for (Waiter waiter : failed) {
            waiter.future.failFromRuntime(failure);
        }
    }

    private boolean canGrantNow(boolean write) {
        return write
                ? !writerActive && activeReaders == 0
                : !writerActive;
    }

    private void markGranted(boolean write) {
        if (write) {
            if (writerActive || activeReaders != 0) {
                throw new IllegalStateException(
                        "proxy rw-lock granted writer while lock was active");
            }
            writerActive = true;
        } else {
            if (writerActive) {
                throw new IllegalStateException(
                        "proxy rw-lock granted reader while writer was active");
            }
            activeReaders++;
        }
    }

    private void cancelWaiter(Waiter waiter) {
        List<Grant> grants = List.of();
        synchronized (this) {
            if (waiters.remove(waiter)) {
                grants = drainLocked();
            }
        }
        settleGrants(grants);
    }

    private void release(boolean write) {
        List<Grant> grants;
        synchronized (this) {
            if (write) {
                if (!writerActive) {
                    throw new IllegalStateException(
                            "proxy rw-lock writer release without active writer");
                }
                writerActive = false;
            } else {
                if (activeReaders <= 0) {
                    throw new IllegalStateException(
                            "proxy rw-lock reader accounting underflow");
                }
                activeReaders--;
            }
            grants = drainLocked();
        }
        settleGrants(grants);
    }

    /**
     * Reserve grants while holding the monitor. Future settlement happens after
     * the monitor is released so scheduler enqueue callbacks cannot re-enter
     * the lock state machine.
     */
    private List<Grant> drainLocked() {
        if (writerActive || waiters.isEmpty()) return List.of();

        ArrayList<Grant> grants = new ArrayList<>();
        Waiter first = waiters.peekFirst();

        if (first.write) {
            if (activeReaders != 0) return List.of();
            waiters.removeFirst();
            markGranted(true);
            grants.add(new Grant(first, new Lease(true)));
            return grants;
        }

        // Readers at the FIFO head may run concurrently. Stop at the first
        // writer so readers arriving behind it cannot starve that writer.
        while (!waiters.isEmpty() && !waiters.peekFirst().write) {
            Waiter reader = waiters.removeFirst();
            markGranted(false);
            grants.add(new Grant(reader, new Lease(false)));
        }
        return grants;
    }

    private void settleGrants(List<Grant> grants) {
        for (Grant grant : grants) {
            if (!grant.waiter.future.completeFromRuntime(grant.lease)) {
                // Cancellation won after dequeue/reservation but before future
                // settlement. Return the logical lease and continue draining.
                grant.lease.close();
            }
        }
    }
}
