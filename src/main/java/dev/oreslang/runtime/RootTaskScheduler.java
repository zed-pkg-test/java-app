package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VM-owned carrier pool for root/main tasks and ordinary async Oreslang
 * continuations.
 *
 * <p>This scheduler is deliberately separate from CONTROL. A root task may run,
 * suspend, or hit a watchdog boundary without consuming supervisor or
 * ActorMailman carrier capacity.</p>
 *
 * <p>It is a carrier pool, not a task/thread identity model. A logical Ores task
 * may resume on a different carrier after {@code await}, just as a Java virtual
 * thread may unmount and later remount on another carrier.</p>
 */
final class RootTaskScheduler implements AutoCloseable {
    private final int parallelism;
    private final int maxParallelism;
    private final ThreadPoolExecutor dispatcher;
    private final AtomicInteger compensatingThreads = new AtomicInteger();
    private final AtomicLong overrunTurns = new AtomicLong();
    private final AtomicLong rejectedTurns = new AtomicLong();

    RootTaskScheduler(
            ActorRuntime.DispatcherConfig config,
            String threadPrefix) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        this.parallelism = config.rootTaskParallelism();
        this.maxParallelism = config.maxRootTaskParallelism();

        int queueCapacity;
        try {
            queueCapacity = Math.addExact(config.maxActors(), maxParallelism);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "root-task ready-queue capacity overflow", overflow);
        }

        this.dispatcher = new ThreadPoolExecutor(
                parallelism,
                maxParallelism,
                50L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity, true),
                namedFactory(threadPrefix + "root-task-dispatcher-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        scaleForDemand();
        try {
            dispatcher.execute(task);
        } catch (RejectedExecutionException rejected) {
            rejectedTurns.incrementAndGet();
            throw rejected;
        }
    }

    boolean remove(Runnable task) {
        return dispatcher.remove(Objects.requireNonNull(task, "task"));
    }

    void recordOverrun() {
        overrunTurns.incrementAndGet();
    }

    boolean claimCompensatingThread() {
        int limit = maxParallelism - parallelism;
        if (limit <= 0) return false;

        while (true) {
            int current = compensatingThreads.get();
            if (current >= limit) return false;
            if (!compensatingThreads.compareAndSet(current, current + 1)) continue;

            synchronized (dispatcher) {
                int currentCore = Math.max(parallelism, dispatcher.getCorePoolSize());
                if (currentCore >= dispatcher.getMaximumPoolSize()) {
                    compensatingThreads.decrementAndGet();
                    return false;
                }
                dispatcher.setCorePoolSize(currentCore + 1);
                dispatcher.prestartCoreThread();
            }
            return true;
        }
    }

    void releaseCompensatingThread() {
        int remaining = compensatingThreads.decrementAndGet();
        if (remaining < 0) {
            compensatingThreads.incrementAndGet();
            throw new IllegalStateException(
                    "root-task dispatcher compensation accounting underflow");
        }

        synchronized (dispatcher) {
            int target = Math.max(parallelism, parallelism + remaining);
            if (dispatcher.getCorePoolSize() > target) {
                dispatcher.setCorePoolSize(target);
            }
        }
    }

    void relaxAfterQuantum() {
        synchronized (dispatcher) {
            if (!dispatcher.getQueue().isEmpty()) return;
            int floor = parallelism + compensatingThreads.get();
            int current = dispatcher.getCorePoolSize();
            if (current > floor) dispatcher.setCorePoolSize(current - 1);
        }
    }

    ActorRuntime.DispatcherStats stats() {
        return new ActorRuntime.DispatcherStats(
                parallelism,
                dispatcher.getActiveCount(),
                dispatcher.getQueue().size(),
                dispatcher.getCompletedTaskCount(),
                compensatingThreads.get(),
                overrunTurns.get(),
                rejectedTurns.get(),
                dispatcher.getLargestPoolSize());
    }

    @Override
    public void close() {
        dispatcher.shutdownNow();
    }

    private void scaleForDemand() {
        synchronized (dispatcher) {
            int current = dispatcher.getCorePoolSize();
            if (current >= maxParallelism) return;
            int queued = dispatcher.getQueue().size();
            if (queued <= current) return;
            dispatcher.setCorePoolSize(current + 1);
            dispatcher.prestartCoreThread();
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger next = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + next.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
