package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded actor-carrier executor backed by native OS threads.
 *
 * <p>The OS carriers are created by liboresthread (pthread on Linux/macOS),
 * attached to the host VM exactly once with JNI AttachCurrentThreadAsDaemon,
 * and then repeatedly execute ordinary Runnable actor turns from this bounded
 * ready queue. Logical actors are therefore multiplexed over a much smaller
 * native carrier set; no actor owns a carrier and carrier identity is never
 * actor identity.</p>
 *
 * <p>All max carriers are created up front so watchdog compensation never has
 * to allocate an OS thread while the runtime is already under pressure. Only
 * {@code corePoolSize} carrier slots are enabled at a time. Extra carriers are
 * parked in native code and are enabled/disabled by the bounded compensation
 * policy in {@link ActorRuntime}.</p>
 */
public final class NativeCarrierExecutor extends AbstractExecutorService implements AutoCloseable {
    private static final String LIBRARY = "oresthread";
    private static final ThreadLocal<NativeCarrierExecutor> CURRENT_EXECUTOR = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CURRENT_SLOT = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CURRENT_AFFINITY_TARGET = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_NATIVE_THREAD_ID = new ThreadLocal<>();

    private static final Object NATIVE_LIBRARY_LOCK = new Object();
    private static volatile boolean nativeLibraryLoaded;

    private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private final ArrayBlockingQueue<Runnable> queue;
    private final List<ArrayBlockingQueue<Runnable>> affinityQueues;
    private final int queueCapacity;
    private final int maximumPoolSize;
    private final long nativeStackBytes;
    private final AtomicInteger corePoolSize;
    private final AtomicInteger largestPoolSize;
    private final AtomicInteger activeCount = new AtomicInteger();
    private final AtomicInteger queuedTaskCount = new AtomicInteger();
    private final AtomicInteger nextWakeSlot = new AtomicInteger();
    private final AtomicLong completedTaskCount = new AtomicLong();
    private final AtomicReferenceArray<Thread> carrierThreads;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    /** Serializes Java->native pool calls against asynchronous native retirement. */
    private final Object nativeLifecycleLock = new Object();
    private final long nativeHandle;

    public NativeCarrierExecutor(
            int corePoolSize,
            int maximumPoolSize,
            int queueCapacity,
            String threadPrefix) {
        if (corePoolSize <= 0) throw new IllegalArgumentException("corePoolSize must be > 0");
        if (maximumPoolSize < corePoolSize) {
            throw new IllegalArgumentException("maximumPoolSize must be >= corePoolSize");
        }
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity must be > 0");
        Objects.requireNonNull(threadPrefix, "threadPrefix");

        ensureNativeLibraryLoaded();

        this.queue = new ArrayBlockingQueue<>(queueCapacity, true);
        ArrayList<ArrayBlockingQueue<Runnable>> lanes = new ArrayList<>(maximumPoolSize);
        for (int slot = 0; slot < maximumPoolSize; slot++) {
            lanes.add(new ArrayBlockingQueue<>(queueCapacity, true));
        }
        this.affinityQueues = List.copyOf(lanes);
        this.queueCapacity = queueCapacity;
        this.maximumPoolSize = maximumPoolSize;
        this.nativeStackBytes = configuredCarrierStackBytes();
        this.carrierThreads = new AtomicReferenceArray<>(maximumPoolSize);
        this.corePoolSize = new AtomicInteger(corePoolSize);
        this.largestPoolSize = new AtomicInteger(corePoolSize);

        long handle = nativeCreate(
                this, maximumPoolSize, corePoolSize, threadPrefix, nativeStackBytes);
        if (handle == 0L) throw new IllegalStateException("native carrier pool returned a null handle");
        this.nativeHandle = handle;

        boolean started = false;
        try {
            nativeStart(handle);
            started = true;
        } finally {
            if (!started) nativeShutdown(handle);
        }
    }

    static void ensureNativeLibraryLoaded() {
        if (nativeLibraryLoaded) return;
        synchronized (NATIVE_LIBRARY_LOCK) {
            if (nativeLibraryLoaded) return;
            loadNativeLibrary();
            nativeLibraryLoaded = true;
        }
    }

    private static long configuredCarrierStackBytes() {
        long configured = Long.getLong("ores.actor.carrier-stack-bytes", 2L * 1024 * 1024);
        if (configured < 256L * 1024 || configured > 64L * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "ores.actor.carrier-stack-bytes must be between 262144 and 67108864");
        }
        return configured;
    }

    private static void loadNativeLibrary() {
        String explicit = System.getProperty("ores.thread.native.path");
        if (explicit != null && !explicit.isBlank()) {
            System.load(Path.of(explicit).toAbsolutePath().normalize().toString());
            return;
        }

        Path local = Path.of("target", "native", System.mapLibraryName(LIBRARY))
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(local)) {
            System.load(local.toString());
            return;
        }

        System.loadLibrary(LIBRARY);
    }

    /**
     * Native pthread entry callback. One invocation lives for one physical
     * carrier's lifetime; many unrelated actor/root Runnables pass through it.
     */
    @SuppressWarnings("unused") // invoked from JNI
    private void nativeCarrierLoop(int slot) {
        CURRENT_EXECUTOR.set(this);
        CURRENT_SLOT.set(slot);
        CURRENT_AFFINITY_TARGET.set(nativeBindCurrentThreadToCarrierSlot(slot));
        CURRENT_NATIVE_THREAD_ID.set(nativeCurrentThreadId());
        carrierThreads.set(slot, Thread.currentThread());
        try {
            while (!shutdown.get()) {
                nativeAwaitEnabled(nativeHandle, slot);
                if (shutdown.get()) break;

                Runnable task = pollReadyTask(slot);
                if (task == null) {
                    // Submission unparks the preferred carrier immediately.
                    // The bounded park is only a lost-wakeup/shutdown safety net.
                    LockSupport.parkNanos(this, IDLE_PARK_NANOS);
                    Thread.interrupted();
                    continue;
                }
                releaseReadySlot();

                activeCount.incrementAndGet();
                try {
                    task.run();
                } catch (Throwable failure) {
                    // ActorRuntime catches ordinary turn failures itself. This
                    // is the executor's final containment boundary, equivalent
                    // to a ThreadPoolExecutor worker's uncaught-exception path.
                    dispatchUncaught(failure);
                } finally {
                    activeCount.decrementAndGet();
                    completedTaskCount.incrementAndGet();
                    Thread.interrupted();
                }
            }
        } finally {
            carrierThreads.set(slot, null);
            CURRENT_NATIVE_THREAD_ID.remove();
            CURRENT_AFFINITY_TARGET.remove();
            CURRENT_SLOT.remove();
            CURRENT_EXECUTOR.remove();
        }
    }

    private Runnable pollReadyTask(int slot) {
        Runnable task = affinityQueues.get(slot).poll();
        if (task != null) return task;
        return queue.poll();
    }

    private boolean reserveReadySlot() {
        for (;;) {
            int current = queuedTaskCount.get();
            if (current >= queueCapacity) return false;
            if (queuedTaskCount.compareAndSet(current, current + 1)) return true;
        }
    }

    private void releaseReadySlot() {
        int remaining = queuedTaskCount.decrementAndGet();
        if (remaining < 0) {
            queuedTaskCount.incrementAndGet();
            throw new IllegalStateException("native carrier ready-queue accounting underflow");
        }
    }

    private int preferredSlot(int affinityKey) {
        int enabled = Math.max(1, corePoolSize.get());
        return Math.floorMod(affinityKey, enabled);
    }

    private int localBacklogEscapeThreshold() {
        return Math.max(1, queueCapacity / Math.max(1, corePoolSize.get()));
    }

    private void wakeCarrier(int preferredSlot) {
        if (preferredSlot >= 0 && preferredSlot < carrierThreads.length()) {
            Thread preferred = carrierThreads.get(preferredSlot);
            if (preferred != null) {
                LockSupport.unpark(preferred);
                return;
            }
        }

        int enabled = Math.max(1, corePoolSize.get());
        int start = Math.floorMod(nextWakeSlot.getAndIncrement(), enabled);
        for (int offset = 0; offset < enabled; offset++) {
            int slot = (start + offset) % enabled;
            Thread carrier = carrierThreads.get(slot);
            if (carrier != null) {
                LockSupport.unpark(carrier);
                return;
            }
        }
    }

    private void enqueue(Runnable task, int affinityKey, boolean affinityAware) {
        Objects.requireNonNull(task, "task");
        if (shutdown.get() || !reserveReadySlot()) {
            throw new RejectedExecutionException(
                    shutdown.get() ? "native carrier executor is shut down"
                            : "native carrier ready queue is full");
        }

        boolean offered = false;
        int slot = -1;
        try {
            if (shutdown.get()) {
                throw new RejectedExecutionException("native carrier executor is shut down");
            }

            if (affinityAware) {
                slot = preferredSlot(affinityKey);
                ArrayBlockingQueue<Runnable> lane = affinityQueues.get(slot);
                if (lane.size() < localBacklogEscapeThreshold()) {
                    offered = lane.offer(task);
                }
            }
            if (!offered) {
                slot = -1;
                offered = queue.offer(task);
            }
            if (!offered) {
                throw new RejectedExecutionException("native carrier ready queue is full");
            }
            wakeCarrier(slot);
        } finally {
            if (!offered) releaseReadySlot();
        }
    }

    private static void dispatchUncaught(Throwable failure) {
        Thread current = Thread.currentThread();
        Thread.UncaughtExceptionHandler handler = current.getUncaughtExceptionHandler();
        if (handler == null) handler = Thread.getDefaultUncaughtExceptionHandler();
        if (handler != null) {
            try {
                handler.uncaughtException(current, failure);
            } catch (Throwable ignored) {
                // Keep the native carrier alive; ActorRuntime's own watchdog
                // and fail-stop state are the authoritative control plane.
            }
        }
    }

    @Override
    public void execute(Runnable task) {
        enqueue(task, 0, false);
    }

    /**
     * Schedule one logical actor turn with a stable soft-affinity key.
     *
     * <p>The preferred native carrier owns a lane and is OS-affinity-bound to
     * one CPU/cache-affinity target where the platform supports it. If that
     * lane becomes materially backlogged, new turns spill into the global
     * queue so locality never becomes a starvation or throughput requirement.</p>
     */
    public void executeAffinity(int affinityKey, Runnable task) {
        enqueue(task, affinityKey, true);
    }

    public boolean remove(Runnable task) {
        if (queue.remove(task)) {
            releaseReadySlot();
            return true;
        }
        for (ArrayBlockingQueue<Runnable> lane : affinityQueues) {
            if (lane.remove(task)) {
                releaseReadySlot();
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Runnable> shutdownNow() {
        if (isCurrentCarrierThread()) {
            throw new IllegalStateException(
                    "native carrier executor cannot synchronously interrupt itself; use requestShutdownFromCarrier()");
        }
        return beginShutdown(true);
    }

    /**
     * Cooperative one-way shutdown for code currently executing on one of this
     * executor's own native carriers. No Java interrupt is injected into the
     * active turn; once that turn unwinds, the carrier observes the shutdown
     * flag and exits through the native reaper path.
     */
    void requestShutdownFromCarrier() {
        if (!isCurrentCarrierThread()) {
            throw new IllegalStateException(
                    "requestShutdownFromCarrier must run on this executor's native carrier");
        }
        beginShutdown(false);
    }

    boolean isCurrentCarrierThread() {
        return CURRENT_EXECUTOR.get() == this;
    }

    private List<Runnable> beginShutdown(boolean interruptActiveCarriers) {
        if (!shutdown.compareAndSet(false, true)) return List.of();
        ArrayList<Runnable> abandoned = new ArrayList<>();
        queue.drainTo(abandoned);
        for (ArrayBlockingQueue<Runnable> lane : affinityQueues) {
            lane.drainTo(abandoned);
        }
        queuedTaskCount.addAndGet(-abandoned.size());

        if (interruptActiveCarriers) {
            // Match ThreadPoolExecutor.shutdownNow(): signal any active carrier
            // before native retirement. This is cooperative Java interruption,
            // never unsafe pthread_cancel().
            for (int slot = 0; slot < carrierThreads.length(); slot++) {
                Thread carrier = carrierThreads.get(slot);
                if (carrier != null) carrier.interrupt();
            }
        }

        synchronized (nativeLifecycleLock) {
            // Native shutdown is non-blocking: it marks the pool closed and
            // hands joins/reclamation to a native reaper so an uncooperative
            // guest stack can never hold this caller hostage.
            nativeShutdown(nativeHandle);
        }
        return List.copyOf(abandoned);
    }

    /**
     * Wait until every attached pthread carrier has exited its Java loop.
     * Native shutdown itself is deliberately non-blocking; callers that own a
     * scheduler can use this bounded observation without joining arbitrary
     * guest stacks in native code.
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;
        for (;;) {
            boolean anyAttached = false;
            for (int slot = 0; slot < carrierThreads.length(); slot++) {
                if (carrierThreads.get(slot) != null) {
                    anyAttached = true;
                    break;
                }
            }
            if (!anyAttached) return true;
            if (nanos == 0L || System.nanoTime() >= deadline) return false;
            long remaining = deadline - System.nanoTime();
            long millis = Math.max(1L, Math.min(10L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            Thread.sleep(millis);
        }
    }

    public boolean isQueueEmpty() { return queuedTaskCount.get() == 0; }

    @Override
    public void close() {
        shutdownNow();
    }

    public int getActiveCount() { return activeCount.get(); }
    public int getQueueSize() { return queuedTaskCount.get(); }
    public long getCompletedTaskCount() { return completedTaskCount.get(); }
    public int getLargestPoolSize() { return largestPoolSize.get(); }
    public int getMaximumPoolSize() { return maximumPoolSize; }
    public long getNativeStackBytes() { return nativeStackBytes; }
    public int getCorePoolSize() { return corePoolSize.get(); }
    @Override
    public void shutdown() {
        shutdownNow();
    }

    @Override
    public boolean isShutdown() { return shutdown.get(); }

    @Override
    public boolean isTerminated() {
        if (!shutdown.get()) return false;
        for (int slot = 0; slot < carrierThreads.length(); slot++) {
            if (carrierThreads.get(slot) != null) return false;
        }
        return true;
    }

    public void setCorePoolSize(int value) {
        if (value <= 0 || value > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "corePoolSize must be in [1," + maximumPoolSize + "]");
        }
        if (shutdown.get()) throw new RejectedExecutionException("native carrier executor is shut down");
        synchronized (nativeLifecycleLock) {
            if (shutdown.get()) {
                throw new RejectedExecutionException("native carrier executor is shut down");
            }
            int previous = corePoolSize.getAndSet(value);
            if (value < previous) {
                for (int slot = value; slot < previous; slot++) {
                    ArrayBlockingQueue<Runnable> lane = affinityQueues.get(slot);
                    Runnable task;
                    while ((task = lane.poll()) != null) {
                        if (!queue.offer(task)) {
                            // Total ready work is already globally bounded, so
                            // this should be unreachable unless accounting is corrupt.
                            lane.offer(task);
                            throw new IllegalStateException(
                                    "failed to rebalance disabled native affinity lane");
                        }
                    }
                }
            }
            largestPoolSize.accumulateAndGet(value, Math::max);
            nativeSetDesired(nativeHandle, value);
            if (value != previous) wakeCarrier(-1);
        }
    }

    /**
     * All bounded compensation carriers are physically created at pool
     * construction and parked natively, so there is nothing to allocate here.
     */
    public boolean prestartCoreThread() {
        return false;
    }

    public static boolean isNativeCarrierThread() {
        return CURRENT_EXECUTOR.get() != null;
    }

    /** Diagnostic-only native pthread identity; never an actor identity. */
    public static long currentNativeThreadId() {
        Long id = CURRENT_NATIVE_THREAD_ID.get();
        return id == null ? 0L : id;
    }

    /** Diagnostic-only carrier slot within its pool. */
    public static int currentCarrierSlot() {
        Integer slot = CURRENT_SLOT.get();
        return slot == null ? -1 : slot;
    }

    /**
     * Diagnostic CPU/cache-affinity target chosen by the native backend.
     * Linux returns the actual allowed logical CPU. macOS returns the Mach
     * affinity tag used to bias threads sharing the same cache domain.
     */
    public static int currentCarrierAffinityTarget() {
        Integer target = CURRENT_AFFINITY_TARGET.get();
        return target == null ? -1 : target;
    }

    /** CPU consumed by the current native carrier, excluding time descheduled. */
    public static long currentCarrierCpuTimeNanos() {
        return isNativeCarrierThread() ? nativeCurrentThreadCpuNanos() : 0L;
    }

    /** CPU consumed by a specific carrier slot in this pool, for watchdog accounting. */
    public long carrierCpuTimeNanos(int slot) {
        if (slot < 0 || slot >= maximumPoolSize || shutdown.get()) return 0L;
        synchronized (nativeLifecycleLock) {
            if (shutdown.get()) return 0L;
            return nativeCarrierCpuTimeNanos(nativeHandle, slot);
        }
    }

    private static native long nativeCreate(
            NativeCarrierExecutor executor,
            int maxThreads,
            int desiredThreads,
            String threadPrefix,
            long stackBytes);
    private static native void nativeStart(long handle);
    private static native void nativeSetDesired(long handle, int desiredThreads);
    private static native void nativeAwaitEnabled(long handle, int slot);
    private static native int nativeBindCurrentThreadToCarrierSlot(int slot);
    private static native void nativeShutdown(long handle);
    private static native long nativeCurrentThreadId();
    private static native long nativeCurrentThreadCpuNanos();
    private static native long nativeCarrierCpuTimeNanos(long handle, int slot);
}
