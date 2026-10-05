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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Runtime-owned Oreslang Future.
 *
 * <p>This is deliberately not a CompletableFuture/CompletionStage. Java stages
 * are host adapters only; they may settle an OresFuture, but they do not define
 * Oreslang continuation scheduling or expose a callback scheduler to guest code.</p>
 */
public class OresFuture<T> implements Future<T> {
    private static final Object PENDING = new Object();

    private record Success<T>(T value) { }
    private record Failure(Throwable failure) { }
    private record Cancelled(CancellationException failure) { }

    private final AtomicReference<Object> state = new AtomicReference<>(PENDING);
    private final AtomicReference<Future<?>> backing = new AtomicReference<>();
    private final CountDownLatch settled = new CountDownLatch(1);
    private final ConcurrentLinkedQueue<BiConsumer<? super T, ? super Throwable>> waiters =
            new ConcurrentLinkedQueue<>();

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

    public static <T> OresFuture<T> from(CompletionStage<? extends T> stage) {
        Objects.requireNonNull(stage, "stage");
        OresFuture<T> future = new OresFuture<>();
        if (stage instanceof Future<?> cancellable) {
            future.attachBacking(cancellable);
        }
        stage.whenComplete((value, failure) -> {
            if (failure == null) future.completeFromRuntime(value);
            else future.failFromRuntime(unwrapCompletionFailure(failure));
        });
        return future;
    }

    void attachBacking(Future<?> scheduled) {
        Objects.requireNonNull(scheduled, "scheduled");
        if (!backing.compareAndSet(null, scheduled)) {
            scheduled.cancel(true);
            throw new IllegalStateException("OresFuture backing task already attached");
        }
        if (isCancelled()) scheduled.cancel(true);
    }

    boolean completeFromRuntime(T value) {
        if (!state.compareAndSet(PENDING, new Success<>(value))) return false;
        settled.countDown();
        drainWaiters();
        return true;
    }

    boolean failFromRuntime(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (!state.compareAndSet(PENDING, new Failure(failure))) return false;
        settled.countDown();
        drainWaiters();
        return true;
    }

    void whenCompleteRuntime(BiConsumer<? super T, ? super Throwable> waiter) {
        Objects.requireNonNull(waiter, "waiter");
        Object snapshot = state.get();
        if (snapshot != PENDING) {
            invoke(waiter, snapshot);
            return;
        }

        waiters.add(waiter);
        snapshot = state.get();
        if (snapshot != PENDING && waiters.remove(waiter)) {
            invoke(waiter, snapshot);
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        CancellationException cancelled = new CancellationException("OresFuture cancelled");
        if (!state.compareAndSet(PENDING, new Cancelled(cancelled))) return false;

        Future<?> task = backing.get();
        if (task != null) task.cancel(mayInterruptIfRunning);
        settled.countDown();
        drainWaiters();
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

    /** Read-only runtime observation; does not expose completion authority. */
    public boolean failed() {
        return state.get() instanceof Failure;
    }

    /** Nonblocking read for runtime cleanup/inspection paths. */
    @SuppressWarnings("unchecked")
    public T valueNowOr(T fallback) {
        Object snapshot = state.get();
        return snapshot instanceof Success<?> success
                ? (T) success.value()
                : fallback;
    }

    /**
     * Uninterruptible-style convenience matching the language/runtime await
     * boundary: checked completion failures are rethrown as their original
     * runtime/error cause rather than wrapped in CompletionException.
     */
    public T join() {
        try {
            return get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancelled =
                    new CancellationException("OresFuture join interrupted");
            cancelled.initCause(interrupted);
            throw cancelled;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        settled.await();
        return decode(state.get());
    }

    @Override
    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit, "unit");
        if (!settled.await(timeout, unit)) throw new TimeoutException("OresFuture timed out");
        return decode(state.get());
    }

    @SuppressWarnings("unchecked")
    private T decode(Object snapshot) throws ExecutionException {
        if (snapshot instanceof Success<?> success) return (T) success.value();
        if (snapshot instanceof Failure failure) throw new ExecutionException(failure.failure());
        if (snapshot instanceof Cancelled cancelled) throw cancelled.failure();
        throw new IllegalStateException("OresFuture observed before settlement");
    }

    private void drainWaiters() {
        Object snapshot = state.get();
        BiConsumer<? super T, ? super Throwable> waiter;
        while ((waiter = waiters.poll()) != null) invoke(waiter, snapshot);
    }

    @SuppressWarnings("unchecked")
    private void invoke(
            BiConsumer<? super T, ? super Throwable> waiter,
            Object snapshot) {
        if (snapshot instanceof Success<?> success) {
            waiter.accept((T) success.value(), null);
        } else if (snapshot instanceof Failure failure) {
            waiter.accept(null, failure.failure());
        } else if (snapshot instanceof Cancelled cancelled) {
            waiter.accept(null, cancelled.failure());
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
