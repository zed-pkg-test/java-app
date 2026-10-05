package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide runtime timer service backed by one native pthread.
 *
 * <p>The native thread owns sleeping/deadline ordering. JNI callbacks may only
 * execute runtime-owned short callbacks; guest continuations must be enqueued
 * onto their owning scheduler instead of running on the timer thread.</p>
 */
final class NativeTimerService {
    interface Ticket {
        boolean cancel();
    }

    private static final class Holder {
        private static final NativeTimerService INSTANCE = new NativeTimerService();
    }

    static NativeTimerService process() {
        return Holder.INSTANCE;
    }

    private final AtomicLong nextId = new AtomicLong(1L);
    private final ConcurrentHashMap<Long, Runnable> callbacks = new ConcurrentHashMap<>();
    private final long nativeHandle;

    private NativeTimerService() {
        NativeCarrierExecutor.ensureNativeLibraryLoaded();
        long handle = nativeCreate(this);
        if (handle == 0L) {
            throw new IllegalStateException("native timer service returned a null handle");
        }
        this.nativeHandle = handle;
    }

    Ticket schedule(Runnable callback, long delayNanos) {
        Objects.requireNonNull(callback, "callback");
        if (delayNanos < 0L) throw new IllegalArgumentException("delayNanos must be non-negative");
        long id = nextTimerId();
        callbacks.put(id, callback);
        boolean scheduled;
        try {
            scheduled = nativeSchedule(nativeHandle, id, delayNanos);
        } catch (RuntimeException | Error failure) {
            callbacks.remove(id);
            throw failure;
        }
        if (!scheduled) {
            callbacks.remove(id);
            throw new RejectedExecutionException("native timer service rejected deadline");
        }
        return () -> {
            Runnable removed = callbacks.remove(id);
            boolean nativeRemoved = nativeCancel(nativeHandle, id);
            return removed != null || nativeRemoved;
        };
    }

    Ticket scheduleWithFixedDelay(Runnable callback, long initialDelayNanos, long delayNanos) {
        Objects.requireNonNull(callback, "callback");
        if (initialDelayNanos < 0L || delayNanos <= 0L) {
            throw new IllegalArgumentException("fixed-delay timer requires non-negative initial delay and positive delay");
        }

        final class Recurring implements Ticket {
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private final AtomicReference<Ticket> active = new AtomicReference<>();

            void arm(long delay) {
                if (cancelled.get()) return;
                Ticket ticket = schedule(() -> {
                    if (cancelled.get()) return;
                    try {
                        callback.run();
                    } finally {
                        if (!cancelled.get()) arm(delayNanos);
                    }
                }, delay);
                Ticket previous = active.getAndSet(ticket);
                if (previous != null && previous != ticket) previous.cancel();
                if (cancelled.get()) ticket.cancel();
            }

            @Override
            public boolean cancel() {
                if (!cancelled.compareAndSet(false, true)) return false;
                Ticket ticket = active.getAndSet(null);
                if (ticket != null) ticket.cancel();
                return true;
            }
        }

        Recurring recurring = new Recurring();
        recurring.arm(initialDelayNanos);
        return recurring;
    }

    @SuppressWarnings("unused") // invoked from JNI
    private void nativeFire(long id) {
        Runnable callback = callbacks.remove(id);
        if (callback == null) return;
        try {
            callback.run();
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Runtime timer callbacks must isolate their own task failures.
            // Dropping an exception here keeps the process timer alive.
        }
    }

    private long nextTimerId() {
        for (;;) {
            long id = nextId.getAndIncrement();
            if (id <= 0L) {
                nextId.compareAndSet(id + 1L, 1L);
                continue;
            }
            if (!callbacks.containsKey(id)) return id;
        }
    }

    private static native long nativeCreate(NativeTimerService service);
    private static native boolean nativeSchedule(long handle, long id, long delayNanos);
    private static native boolean nativeCancel(long handle, long id);
}
