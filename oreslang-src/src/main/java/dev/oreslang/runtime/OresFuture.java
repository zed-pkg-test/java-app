package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Oreslang's runtime-owned Future primitive.
 *
 * <p>This is deliberately <em>not</em> a {@link CompletableFuture}. Oreslang
 * Future values do not expose a callback surface that can accidentally execute
 * guest code on an I/O, timer, JNI, or producer-completion thread. Runtime
 * components may register enqueue-only waiters through
 * {@link #whenCompleteRuntime(BiConsumer)}; actor/root schedulers decide when
 * the captured Oreslang continuation actually runs.</p>
 *
 * <p>Completion authority belongs to the runtime operation that created the
 * Future. Guest code may observe state, await, or request cancellation, but it
 * cannot forge a value/failure.</p>
 */
public final class OresFuture<T> implements Future<T>, OresAwaitable<T> {
    private static final Object PENDING = new Object();

    private record Success<T>(T value) { }
    private record Failure(Throwable failure) { }
    private record Cancelled(CancellationException failure) { }

    interface RuntimeWaiterRegistration {
        boolean cancel();
    }

    private static final class Waiter<T> {
        private final BiConsumer<? super T, ? super Throwable> callback;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private Waiter(BiConsumer<? super T, ? super Throwable> callback) {
            this.callback = callback;
        }
    }

    private final AtomicReference<Runnable> cancelHook;
    private final AtomicBoolean cancelHookRun = new AtomicBoolean();
    private final AtomicReference<Object> state = new AtomicReference<>(PENDING);
    private final ConcurrentLinkedQueue<Waiter<T>> waiters = new ConcurrentLinkedQueue<>();

    public OresFuture() {
        this(() -> { });
    }

    OresFuture(Runnable cancelHook) {
        this.cancelHook = new AtomicReference<>(
                Objects.requireNonNull(cancelHook, "cancelHook"));
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
    public static <T> OresFuture<T> from(OresFuture<? extends T> future) {
        return (OresFuture<T>) Objects.requireNonNull(future, "future");
    }

    /**
     * Host-interop adapter. The host CompletionStage may complete on any thread;
     * its callback only settles this OresFuture. It does not run guest code.
     */
    public static <T> OresFuture<T> from(CompletionStage<? extends T> stage) {
        Objects.requireNonNull(stage, "stage");
        Runnable cancelHook = stage instanceof Future<?> cancellable
                ? () -> cancellable.cancel(true)
                : () -> { };
        OresFuture<T> result = new OresFuture<>(cancelHook);
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                result.completeFromRuntime(value);
                return;
            }

            Throwable terminal = unwrap(failure);
            if (stage instanceof Future<?> cancellable
                    && cancellable.isCancelled()) {
                CancellationException cancellation =
                        terminal instanceof CancellationException existing
                                ? existing
                                : new CancellationException("host Future was cancelled");
                if (cancellation != terminal) cancellation.initCause(terminal);
                result.cancelFromRuntime(cancellation);
            } else {
                result.failFromRuntime(terminal);
            }
        });
        return result;
    }

    boolean completeFromRuntime(T value) {
        return settle(new Success<>(value));
    }

    boolean failFromRuntime(Throwable failure) {
        return settle(new Failure(Objects.requireNonNull(failure, "failure")));
    }

    boolean cancelFromRuntime(CancellationException failure) {
        return settle(new Cancelled(Objects.requireNonNull(failure, "failure")));
    }

    /**
     * Runtime-only completion subscription.
     *
     * <p>Callbacks registered here must be scheduler plumbing only: transition a
     * dependent Future, enqueue a continuation, or release runtime accounting.
     * They must never execute Oreslang guest code directly.</p>
     *
     * <p>Registration is race-safe against completion and does not retain
     * already-delivered waiters on a terminal Future. That matters for shared
     * Futures used as reactive sources, where many subscriptions may observe the
     * same already-completed producer.</p>
     */
    RuntimeWaiterRegistration whenCompleteRuntime(
            BiConsumer<? super T, ? super Throwable> callback) {
        Objects.requireNonNull(callback, "callback");
        Waiter<T> waiter = new Waiter<>(callback);

        Object observed = state.get();
        if (observed != PENDING) {
            notifyWaiter(waiter, observed);
            return () -> false;
        }

        waiters.add(waiter);

        observed = state.get();
        if (observed != PENDING) {
            // Completion may have raced either before or after queue insertion.
            // Remove our queue node when it is still present; claimed prevents
            // duplicate delivery if the settling thread already polled it.
            waiters.remove(waiter);
            notifyWaiter(waiter, observed);
        }

        return () -> cancelRuntimeWaiter(waiter);
    }

    @Override
    public OresFuture<T> getAwait() {
        return this;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        CancellationException cancelled =
                new CancellationException("OresFuture was cancelled");
        Runnable hook = cancelHook.get();
        if (!settle(new Cancelled(cancelled))) return false;

        if (hook != null && cancelHookRun.compareAndSet(false, true)) {
            try {
                hook.run();
            } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
                throw fatal;
            } catch (RuntimeException | Error ignored) {
                // Cancellation state is already authoritative. An ordinary
                // host cancellation-hook failure cannot roll it back.
            }
        }
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

    /**
     * Read-only compatibility observation used by runtime/tests. As with
     * CompletableFuture, cancellation is also an exceptional terminal state.
     */
    public boolean isCompletedExceptionally() {
        Object observed = state.get();
        return observed instanceof Failure || observed instanceof Cancelled;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        rejectCarrierBlocking("Future.get");
        Object observed = awaitState(0L, null);
        return reportGet(observed);
    }

    @Override
    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
        rejectCarrierBlocking("Future.get(timeout)");
        Object observed = awaitState(timeout, unit);
        if (observed == PENDING) {
            throw new TimeoutException("OresFuture did not complete before timeout");
        }
        return reportGet(observed);
    }

    /**
     * Host/embedder blocking bridge. Oreslang actor/root lowering must use the
     * scheduler suspension ABI rather than calling join on a carrier.
     */
    public T join() {
        rejectCarrierBlocking("Future.join");
        Object observed = state.get();
        boolean interrupted = false;
        if (observed == PENDING) {
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            whenCompleteRuntime((value, failure) -> done.countDown());
            for (;;) {
                try {
                    done.await();
                    break;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
            observed = state.get();
        }
        if (interrupted) Thread.currentThread().interrupt();
        return reportJoin(observed);
    }

    /**
     * Runtime callback adapters use this to compose an Ores Future with APIs
     * that still require CompletionStage. Guest/language code should never
     * receive the returned stage.
     */
    CompletionStage<T> asCompletionStage() {
        AtomicReference<RuntimeWaiterRegistration> registrationRef =
                new AtomicReference<>();
        CompletableFuture<T> bridge = new CompletableFuture<>();

        bridge.whenComplete((ignoredValue, ignoredFailure) -> {
            RuntimeWaiterRegistration registration =
                    registrationRef.getAndSet(null);
            if (registration != null) registration.cancel();
        });

        RuntimeWaiterRegistration registration =
                whenCompleteRuntime((value, failure) -> {
                    if (failure == null) bridge.complete(value);
                    else bridge.completeExceptionally(failure);
                });
        registrationRef.set(registration);

        if (bridge.isDone()) {
            registration.cancel();
            registrationRef.compareAndSet(registration, null);
        }
        return bridge;
    }

    private boolean settle(Object terminal) {
        if (!state.compareAndSet(PENDING, terminal)) return false;

        // No terminal Future retains producer/task cancellation authority.
        // public cancel() snapshots the hook before settlement when it is the
        // winning terminal transition.
        cancelHook.set(null);

        Error fatal = null;
        Waiter<T> waiter;
        while ((waiter = waiters.poll()) != null) {
            try {
                notifyWaiter(waiter, terminal);
            } catch (VirtualMachineError | ThreadDeath | LinkageError waiterFatal) {
                if (fatal == null) fatal = waiterFatal;
            }
        }
        if (fatal != null) throw fatal;
        return true;
    }

    private boolean cancelRuntimeWaiter(Waiter<T> waiter) {
        if (!waiter.claimed.compareAndSet(false, true)) return false;
        waiters.remove(waiter);
        return true;
    }

    @SuppressWarnings("unchecked")
    private void notifyWaiter(Waiter<T> waiter, Object terminal) {
        if (!waiter.claimed.compareAndSet(false, true)) return;
        try {
            if (terminal instanceof Success<?> success) {
                waiter.callback.accept((T) success.value(), null);
            } else if (terminal instanceof Failure failed) {
                waiter.callback.accept(null, failed.failure());
            } else if (terminal instanceof Cancelled cancelled) {
                waiter.callback.accept(null, cancelled.failure());
            } else {
                throw new IllegalStateException("attempted to notify waiter from pending Future");
            }
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (RuntimeException | Error ignored) {
            // Ordinary runtime waiter failures must not mutate the settled
            // Future. VM-fatal errors are deliberately never swallowed.
        }
    }

    private static void rejectCarrierBlocking(String operation) {
        if (ActorRuntime.isOresCarrierThread()) {
            throw new IllegalStateException(
                    operation
                            + " cannot block an OresVM carrier; use await/continuation suspension");
        }
    }

    private Object awaitState(long timeout, TimeUnit unit) throws InterruptedException {
        Object observed = state.get();
        if (observed != PENDING) return observed;

        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        RuntimeWaiterRegistration registration =
                whenCompleteRuntime((value, failure) -> done.countDown());

        try {
            if (unit == null) {
                done.await();
            } else if (!done.await(timeout, unit)) {
                return state.get();
            }
            return state.get();
        } finally {
            // Timed-out and interrupted host observers must not remain rooted
            // in a Future that may never settle. If completion already claimed
            // the waiter, cancel() is a harmless no-op.
            registration.cancel();
        }
    }

    @SuppressWarnings("unchecked")
    private T reportGet(Object terminal) throws ExecutionException {
        if (terminal instanceof Success<?> success) return (T) success.value();
        if (terminal instanceof Failure failed) throw new ExecutionException(failed.failure());
        if (terminal instanceof Cancelled cancelled) throw cancelled.failure();
        throw new IllegalStateException("Future is still pending");
    }

    @SuppressWarnings("unchecked")
    private T reportJoin(Object terminal) {
        if (terminal instanceof Success<?> success) return (T) success.value();
        if (terminal instanceof Failure failed) {
            throw new CompletionException(failed.failure());
        }
        if (terminal instanceof Cancelled cancelled) throw cancelled.failure();
        throw new IllegalStateException("Future is still pending");
    }

    /**
     * Completion remains runtime-owned. These methods intentionally exist so
     * Java interop receives an explicit failure instead of silently gaining a
     * completion capability.
     */
    public boolean complete(T value) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public boolean completeExceptionally(Throwable ex) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public CompletableFuture<T> completeAsync(
            Supplier<? extends T> supplier,
            java.util.concurrent.Executor executor) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException(
                "OresFuture completion is runtime-owned; use an Oreslang timeout combinator");
    }

    public CompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException(
                "OresFuture completion is runtime-owned; use an Oreslang timeout combinator");
    }

    public void obtrudeValue(T value) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public void obtrudeException(Throwable ex) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
