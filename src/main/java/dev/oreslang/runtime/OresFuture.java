package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Runtime-owned implementation of the Oreslang Future<T> contract.
 *
 * <p>This type deliberately does not extend CompletableFuture or expose a
 * callback surface to guest code. Host/JNI/I/O producers may settle the
 * future, but a producer thread must never inherit the right to execute a
 * suspended Oreslang continuation inline. Continuation scheduling remains an
 * Oreslang runtime responsibility.</p>
 */
public final class OresFuture<T> implements Future<T> {
    private static final Object PENDING = new Object();

    private record Success<T>(T value) { }
    private record Failure(Throwable failure) { }
    private record Cancelled(CancellationException failure) { }

    private static final class Waiter<T> {
        private final BiConsumer<? super T, ? super Throwable> callback;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private Waiter(BiConsumer<? super T, ? super Throwable> callback) {
            this.callback = callback;
        }
    }

    private final Runnable cancelHook;
    private final AtomicBoolean cancelHookRun = new AtomicBoolean();
    private final AtomicReference<Object> state = new AtomicReference<>(PENDING);
    private final ConcurrentLinkedQueue<Waiter<T>> waiters = new ConcurrentLinkedQueue<>();
    private final CountDownLatch completion = new CountDownLatch(1);

    public OresFuture() {
        this(() -> { });
    }

    OresFuture(Runnable cancelHook) {
        this.cancelHook = Objects.requireNonNull(cancelHook, "cancelHook");
    }

    public static <T> OresFuture<T> completed(T value) {
        OresFuture<T> future = new OresFuture<>();
        future.completeFromRuntime(value);
        return future;
    }

    public static <T> OresFuture<T> failed(Throwable failure) {
        OresFuture<T> future = new OresFuture<>();
        future.failFromRuntime(Objects.requireNonNull(failure, "failure"));
        return future;
    }

    /**
     * One-way host interop adapter. CompletionStage callback execution policy
     * stops at this boundary: the callback only settles Ores-owned state.
     */
    public static <T> OresFuture<T> from(CompletionStage<? extends T> stage) {
        Objects.requireNonNull(stage, "stage");
        Runnable cancelHook = stage instanceof Future<?> cancellable
                ? () -> cancellable.cancel(true)
                : () -> { };
        OresFuture<T> result = new OresFuture<>(cancelHook);
        stage.whenComplete((value, failure) -> {
            if (failure == null) result.completeFromRuntime(value);
            else result.failFromRuntime(unwrap(failure));
        });
        return result;
    }

    boolean completeFromRuntime(T value) {
        return settle(new Success<>(value));
    }

    boolean failFromRuntime(Throwable failure) {
        return settle(new Failure(Objects.requireNonNull(failure, "failure")));
    }

    private boolean settle(Object terminalState) {
        if (!state.compareAndSet(PENDING, terminalState)) return false;
        completion.countDown();
        drainWaiters(terminalState);
        return true;
    }

    /**
     * Runtime-only completion observer. The callback must enqueue/signal work;
     * it must not directly execute guest Oreslang continuation code.
     */
    void whenCompleteRuntime(BiConsumer<? super T, ? super Throwable> callback) {
        Objects.requireNonNull(callback, "callback");
        Waiter<T> waiter = new Waiter<>(callback);
        waiters.add(waiter);
        Object current = state.get();
        if (current != PENDING) drainWaiters(current);
    }

    private void drainWaiters(Object terminalState) {
        Waiter<T> waiter;
        while ((waiter = waiters.poll()) != null) {
            if (!waiter.claimed.compareAndSet(false, true)) continue;
            T value = null;
            Throwable failure = null;
            if (terminalState instanceof Success<?> success) {
                @SuppressWarnings("unchecked")
                T cast = (T) success.value();
                value = cast;
            } else if (terminalState instanceof Failure failed) {
                failure = failed.failure();
            } else if (terminalState instanceof Cancelled cancelled) {
                failure = cancelled.failure();
            } else {
                throw new AssertionError("invalid OresFuture terminal state");
            }
            try {
                waiter.callback.accept(value, failure);
            } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Runtime observation is best-effort. One faulty observer must
                // not prevent other waiters from observing terminal state.
            }
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        CancellationException cancelled =
                new CancellationException("Oreslang Future cancelled");
        if (!state.compareAndSet(PENDING, new Cancelled(cancelled))) return false;
        if (cancelHookRun.compareAndSet(false, true)) {
            try {
                cancelHook.run();
            } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
                completion.countDown();
                drainWaiters(state.get());
                throw fatal;
            } catch (Throwable ignored) {
                // Cancellation state is authoritative even if a host adapter's
                // best-effort cancellation hook fails.
            }
        }
        completion.countDown();
        drainWaiters(state.get());
        return true;
    }

    @Override
    public boolean isCancelled() {
        return state.get() instanceof Cancelled;
    }

    @Override
    public boolean isDone() {
        return state.get() != PENDING;
    }

    public boolean isCompletedExceptionally() {
        Object current = state.get();
        return current instanceof Failure || current instanceof Cancelled;
    }

    public T getNow(T fallback) {
        Object current = state.get();
        if (current == PENDING) return fallback;
        return unpackUnchecked(current);
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        completion.await();
        return unpackChecked(state.get());
    }

    @Override
    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
        if (!completion.await(timeout, unit)) {
            throw new TimeoutException("Oreslang Future did not complete before timeout");
        }
        return unpackChecked(state.get());
    }

    private T unpackChecked(Object current) throws ExecutionException {
        if (current instanceof Success<?> success) {
            @SuppressWarnings("unchecked")
            T cast = (T) success.value();
            return cast;
        }
        if (current instanceof Failure failed) {
            throw new ExecutionException(failed.failure());
        }
        if (current instanceof Cancelled cancelled) {
            throw cancelled.failure();
        }
        throw new IllegalStateException("OresFuture is not complete");
    }

    private T unpackUnchecked(Object current) {
        try {
            return unpackChecked(current);
        } catch (ExecutionException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
