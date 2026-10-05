package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit CPU-bound actor that owns one dedicated platform thread for its
 * entire lifetime.
 *
 * <p>This is intentionally different from ordinary Oreslang actors, which are
 * multiplexed over bounded dispatchers. A HungryActor is an opt-in escape hatch
 * for sustained CPU work, thread-affine native runtimes, or workloads whose
 * progress contract requires reserving one OS carrier until the actor releases
 * it or terminates. It must therefore be used sparingly.</p>
 *
 * <p>The mailbox remains bounded and messages cross the boundary through
 * {@link ActorRuntime#freeze(Object)}. Execution is serial. Stop/release is
 * cooperative for CPU loops: code should call {@link Context#schedulerSafepoint()}
 * at bounded intervals so cancellation can be observed promptly.</p>
 */
public final class HungryActor<M> implements AutoCloseable {
    private static final int DEFAULT_MAILBOX_CAPACITY = 1024;
    private static final long CLOSE_WAIT_MILLIS = 2_000L;

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, Context<M> context) throws Exception;
    }

    public interface Context<M> {
        HungryActor<M> self();
        UUID id();
        boolean stopRequested();
        void release();
        void schedulerSafepoint();
    }

    private final UUID id = UUID.randomUUID();
    private final ArrayBlockingQueue<Object> mailbox;
    private final Behavior<M> behavior;
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CountDownLatch termination = new CountDownLatch(1);
    private final Object lifecycleLock = new Object();
    private final NativeCarrierExecutor carrier;
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private final AtomicLong nativeCarrierThreadId = new AtomicLong();
    private final String carrierThreadPrefix;
    private final Context<M> context = new Context<>() {
        @Override
        public HungryActor<M> self() {
            return HungryActor.this;
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public boolean stopRequested() {
            return stopRequested.get();
        }

        @Override
        public void release() {
            HungryActor.this.release();
        }

        @Override
        public void schedulerSafepoint() {
            HungryActor.this.schedulerSafepoint();
        }
    };

    public HungryActor(Behavior<M> behavior) {
        this("worker", DEFAULT_MAILBOX_CAPACITY, behavior);
    }

    public HungryActor(String name, int mailboxCapacity, Behavior<M> behavior) {
        if (mailboxCapacity <= 0) {
            throw new IllegalArgumentException("mailboxCapacity must be > 0");
        }
        this.mailbox = new ArrayBlockingQueue<>(mailboxCapacity);
        this.behavior = Objects.requireNonNull(behavior, "behavior");

        String safeName = sanitizeName(name);
        this.carrierThreadPrefix = "ores-hungry-" + safeName + "-";
        this.carrier = new NativeCarrierExecutor(
                1,
                1,
                1,
                carrierThreadPrefix);
        this.carrier.execute(this::runLoop);
    }

    public UUID id() {
        return id;
    }

    public String threadName() {
        Thread current = worker.get();
        return current == null ? carrierThreadPrefix + "1" : current.getName();
    }

    public boolean isVirtualCarrier() {
        Thread current = worker.get();
        return current != null && current.isVirtual();
    }

    public boolean usesNativeCarrier() {
        return true;
    }

    public long nativeCarrierThreadId() {
        return nativeCarrierThreadId.get();
    }

    public boolean isAlive() {
        return !terminated.get() && !carrier.isTerminated();
    }

    public boolean stopRequested() {
        return stopRequested.get();
    }

    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure.get());
    }

    /**
     * Enqueue one frozen message without ever blocking the caller.
     */
    @SuppressWarnings("unchecked")
    public void send(M message) {
        Object frozen = ActorRuntime.freeze(message);
        synchronized (lifecycleLock) {
            if (stopRequested.get() || terminated.get()) {
                throw new IllegalStateException("HungryActor " + id + " is terminated");
            }
            if (!mailbox.offer(frozen)) {
                throw new IllegalStateException(
                        "HungryActor mailbox limit exceeded for " + id);
            }
        }
    }

    /**
     * Relinquish the dedicated thread once the current callback unwinds.
     */
    public void release() {
        requestStop();
    }

    public void stop() {
        requestStop();
    }

    private void requestStop() {
        Thread target;
        synchronized (lifecycleLock) {
            if (!stopRequested.compareAndSet(false, true)) return;
            target = worker.get();
        }
        if (target != null && Thread.currentThread() != target) {
            // This Java Thread object is the VM bridge for a JNI-attached
            // pthread. Interrupting it wakes Java blocking primitives without
            // changing the fact that the physical carrier is native-owned.
            target.interrupt();
        }
    }

    /**
     * Cooperative cancellation point for long CPU-bound loops.
     */
    public void schedulerSafepoint() {
        if (Thread.currentThread() != worker.get()
                || !NativeCarrierExecutor.isNativeCarrierThread()) {
            throw new IllegalStateException(
                    "HungryActor schedulerSafepoint must run on its dedicated native carrier");
        }
        if (stopRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("HungryActor " + id + " is stopping");
        }
        Thread.onSpinWait();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
        return termination.await(timeout, unit);
    }

    @SuppressWarnings("unchecked")
    private void runLoop() {
        Thread current = Thread.currentThread();
        if (!NativeCarrierExecutor.isNativeCarrierThread()) {
            failure.compareAndSet(
                    null,
                    new IllegalStateException("HungryActor must execute on a native pthread carrier"));
            stopRequested.set(true);
            terminated.set(true);
            termination.countDown();
            carrier.shutdown();
            return;
        }
        nativeCarrierThreadId.set(NativeCarrierExecutor.currentNativeThreadId());
        if (nativeCarrierThreadId.get() == 0L) {
            failure.compareAndSet(
                    null,
                    new IllegalStateException("HungryActor native pthread identity is unavailable"));
            stopRequested.set(true);
            terminated.set(true);
            termination.countDown();
            carrier.shutdown();
            return;
        }
        if (!worker.compareAndSet(null, current)) {
            failure.compareAndSet(
                    null,
                    new IllegalStateException("HungryActor native carrier was published more than once"));
            stopRequested.set(true);
            terminated.set(true);
            termination.countDown();
            carrier.shutdown();
            return;
        }

        try {
            while (!stopRequested.get()) {
                final Object raw;
                try {
                    raw = mailbox.take();
                } catch (InterruptedException interrupted) {
                    if (stopRequested.get()) break;
                    Thread.currentThread().interrupt();
                    throw new CancellationException(
                            "HungryActor " + id + " carrier interrupted");
                }

                try {
                    behavior.onMessage((M) raw, context);
                } catch (CancellationException cancelled) {
                    if (!stopRequested.get()) failure.compareAndSet(null, cancelled);
                    requestStop();
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    failure.compareAndSet(null, fatal);
                    requestStop();
                    throw fatal;
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                    requestStop();
                }
            }
        } finally {
            synchronized (lifecycleLock) {
                stopRequested.set(true);
                mailbox.clear();
                terminated.set(true);
            }
            termination.countDown();
            // Graceful native shutdown can be requested by the currently
            // executing carrier. The JNI reaper joins/reclaims it after this
            // logical turn returns to NativeCarrierExecutor.
            carrier.shutdown();
        }
    }

    @Override
    public void close() {
        release();

        if (Thread.currentThread() == worker.get()) {
            // The run-loop finally block retires the native carrier after this
            // callback unwinds; waiting here would self-deadlock.
            return;
        }

        boolean interrupted = false;
        try {
            if (!termination.await(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "HungryActor " + id + " did not release its dedicated native carrier");
            }
            carrier.shutdown();
            if (!carrier.awaitTermination(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "HungryActor " + id + " native carrier did not terminate");
            }
        } catch (InterruptedException waitInterrupted) {
            interrupted = true;
            throw new IllegalStateException(
                    "interrupted while waiting for HungryActor " + id + " to terminate",
                    waitInterrupted);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static String sanitizeName(String name) {
        String raw = Objects.requireNonNullElse(name, "worker").trim();
        if (raw.isEmpty()) raw = "worker";
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "-");
        return safe.length() <= 48 ? safe : safe.substring(0, 48);
    }
}
