package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Oreslang's runtime future primitive.
 *
 * The language-level Future<T> is deliberately backed by CompletionStage so
 * host async I/O can complete it without occupying an actor carrier thread.
 * Future values are linear/local async state: they are not actor-sendable or
 * SharedSafe.
 */
public final class OresFuture<T> extends CompletableFuture<T> {
    private final Runnable cancelHook;
    private final AtomicBoolean cancelHookRun = new AtomicBoolean();

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

    @SuppressWarnings("unchecked")
    public static <T> OresFuture<T> from(CompletionStage<? extends T> stage) {
        Objects.requireNonNull(stage, "stage");
        if (stage instanceof OresFuture<?> ores) {
            return (OresFuture<T>) ores;
        }

        Runnable cancelHook = stage instanceof java.util.concurrent.Future<?> cancellable
                ? () -> cancellable.cancel(true)
                : () -> { };
        OresFuture<T> result = new OresFuture<>(cancelHook);
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                result.completeFromRuntime(value);
            } else {
                result.failFromRuntime(unwrap(failure));
            }
        });
        return result;
    }

    boolean completeFromRuntime(T value) {
        return super.complete(value);
    }

    boolean failFromRuntime(Throwable failure) {
        return super.completeExceptionally(Objects.requireNonNull(failure, "failure"));
    }

    /**
     * Completion authority belongs to the runtime/host operation that created
     * the Future. Guest code may observe or cancel but cannot forge a result.
     */
    @Override
    public boolean complete(T value) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    @Override
    public boolean completeExceptionally(Throwable ex) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    @Override
    public CompletableFuture<T> completeAsync(
            java.util.function.Supplier<? extends T> supplier) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    @Override
    public CompletableFuture<T> completeAsync(
            java.util.function.Supplier<? extends T> supplier,
            java.util.concurrent.Executor executor) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    @Override
    public CompletableFuture<T> orTimeout(long timeout, java.util.concurrent.TimeUnit unit) {
        throw new UnsupportedOperationException(
                "OresFuture completion is runtime-owned; use an Oreslang timeout combinator");
    }

    @Override
    public CompletableFuture<T> completeOnTimeout(
            T value,
            long timeout,
            java.util.concurrent.TimeUnit unit) {
        throw new UnsupportedOperationException(
                "OresFuture completion is runtime-owned; use an Oreslang timeout combinator");
    }

    @Override
    public void obtrudeValue(T value) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    @Override
    public void obtrudeException(Throwable ex) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean cancelled = super.cancel(mayInterruptIfRunning);
        if (cancelled && cancelHookRun.compareAndSet(false, true)) {
            cancelHook.run();
        }
        return cancelled;
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
