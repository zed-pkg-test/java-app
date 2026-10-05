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
import java.util.function.Consumer;
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
public final class OresFuture<T> implements Future<T>, Awaitable<T> {
    private static final Object PENDING = new Object();

    /**
     * Single-shot completion capability used to adapt callback-only APIs.
     *
     * <p>Calling resolve/reject/cancel settles the target Future but never
     * resumes Oreslang guest code inline. Awaiting code is still resumed only
     * by its owning scheduler after the suspending turn has unwound.</p>
     */
    public interface Callback<T> {
        void resolve(T value);
        void reject(Throwable failure);
        void cancel();
        boolean isDone();

        default void complete(Throwable failure, T value) {
            if (failure == null) resolve(value);
            else reject(failure);
        }
    }

    public static final class AlreadySettledException extends IllegalStateException {
        public AlreadySettledException(String message) {
            super(message);
        }
    }

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

    /**
     * Runtime-only detachable completion registration.
     *
     * <p>Detaching never changes the Future's producer/cancellation state. It
     * only prevents this runtime continuation waiter from retaining or being
     * invoked after its owning scheduler/task has been cancelled.</p>
     */
    static final class RuntimeWaiterRegistration {
        private final AtomicBoolean claimed;
        private final Runnable remove;

        private RuntimeWaiterRegistration(
                AtomicBoolean claimed,
                Runnable remove) {
            this.claimed = claimed;
            this.remove = remove;
        }

        boolean detach() {
            if (!claimed.compareAndSet(false, true)) return false;
            remove.run();
            return true;
        }
    }

    private final Runnable cancelHook;
    private final AtomicBoolean cancelHookRun = new AtomicBoolean();
    private final AtomicReference<Object> state = new AtomicReference<>(PENDING);
    private final ConcurrentLinkedQueue<Waiter<T>> waiters = new ConcurrentLinkedQueue<>();

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
     * Adapt a single-shot callback registration API into an Ores Future.
     *
     * <p>The registrar may invoke the callback synchronously. That only settles
     * the Future; it cannot re-enter an awaiting Oreslang frame. A second
     * callback settlement is rejected deterministically.</p>
     */
    public static <T> OresFuture<T> fromCallback(
            Consumer<? super Callback<T>> registrar) {
        Objects.requireNonNull(registrar, "registrar");
        OresFuture<T> future = new OresFuture<>();
        AtomicBoolean callbackClaimed = new AtomicBoolean();

        Callback<T> completion = new Callback<>() {
            private void claim(String operation) {
                if (!callbackClaimed.compareAndSet(false, true)) {
                    throw new AlreadySettledException(
                            "callback Future already settled; duplicate " + operation);
                }
            }

            @Override
            public void resolve(T value) {
                claim("resolve");
                if (!future.completeFromRuntime(value)) {
                    throw new AlreadySettledException(
                            "callback Future was already settled before resolve");
                }
            }

            @Override
            public void reject(Throwable failure) {
                Objects.requireNonNull(failure, "failure");
                claim("reject");
                if (!future.failFromRuntime(failure)) {
                    throw new AlreadySettledException(
                            "callback Future was already settled before reject");
                }
            }

            @Override
            public void cancel() {
                claim("cancel");
                if (!future.cancel(false)) {
                    throw new AlreadySettledException(
                            "callback Future was already settled before cancel");
                }
            }

            @Override
            public boolean isDone() {
                return callbackClaimed.get() || future.isDone();
            }
        };

        try {
            registrar.accept(completion);
        } catch (Throwable failure) {
            // Promise-style constructor semantics: a registrar failure rejects
            // only if the callback has not already won the single-shot race.
            if (callbackClaimed.compareAndSet(false, true)) {
                future.failFromRuntime(failure);
            } else if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            } else if (failure instanceof ThreadDeath fatal) {
                throw fatal;
            } else if (failure instanceof LinkageError fatal) {
                throw fatal;
            }
        }
        return future;
    }

    @Override
    public OresFuture<T> getAwaited() {
        return this;
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
            } else {
                result.failFromRuntime(unwrap(failure));
            }
        });
        return result;
    }

    /**
     * Chain a callback-only operation after this Future on an explicit Ores
     * scheduler. Guest registrar code executes only as a scheduler turn.
     *
     * <p>If the callback fires synchronously, the dependent Future is already
     * settled when this turn returns Await, but TaskRunner still requires the
     * current turn to unwind before the continuation can be dispatched.</p>
     */
    public <U> OresFuture<U> attachCallback(
            OresScheduler scheduler,
            java.util.function.BiConsumer<? super T, ? super Callback<U>> registrar) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(registrar, "registrar");
        OresFuture<T> source = this;

        return scheduler.start(new OresScheduler.Task<>() {
            private int pc;
            private OresFuture<U> dependent;

            @Override
            public OresScheduler.Step<U> resume(OresScheduler.Resume resume) {
                if (pc == 0) {
                    if (!resume.initial()) {
                        throw new IllegalStateException(
                                "callback chain started with a non-initial resume");
                    }
                    pc = 1;
                    return OresScheduler.await(source);
                }

                if (pc == 1) {
                    if (resume.failure() != null) {
                        throw propagate(resume.failure());
                    }
                    @SuppressWarnings("unchecked")
                    T value = (T) resume.value();
                    dependent = OresFuture.fromCallback(
                            callback -> registrar.accept(value, callback));
                    pc = 2;
                    return OresScheduler.await(dependent);
                }

                if (pc == 2) {
                    if (resume.failure() != null) {
                        throw propagate(resume.failure());
                    }
                    @SuppressWarnings("unchecked")
                    U value = (U) resume.value();
                    pc = 3;
                    return OresScheduler.done(value);
                }

                throw new IllegalStateException(
                        "callback Future chain resumed after completion");
            }

            private RuntimeException propagate(Throwable failure) {
                Throwable unwrapped = OresFuture.unwrap(failure);
                if (unwrapped instanceof RuntimeException runtime) return runtime;
                if (unwrapped instanceof Error error) throw error;
                return new RuntimeException(unwrapped);
            }
        });
    }

    boolean completeFromRuntime(T value) {
        return settle(new Success<>(value));
    }

    boolean failFromRuntime(Throwable failure) {
        return settle(new Failure(Objects.requireNonNull(failure, "failure")));
    }

    /**
     * Runtime-only completion subscription.
     *
     * <p>Callbacks registered here must be scheduler plumbing only: transition a
     * dependent Future, enqueue a continuation, or release runtime accounting.
     * They must never execute Oreslang guest code directly.</p>
     */
    void whenCompleteRuntime(BiConsumer<? super T, ? super Throwable> callback) {
        whenCompleteRuntimeCancellable(callback);
    }

    RuntimeWaiterRegistration whenCompleteRuntimeCancellable(
            BiConsumer<? super T, ? super Throwable> callback) {
        Objects.requireNonNull(callback, "callback");
        Waiter<T> waiter = new Waiter<>(callback);
        RuntimeWaiterRegistration registration =
                new RuntimeWaiterRegistration(
                        waiter.claimed,
                        () -> waiters.remove(waiter));
        waiters.add(waiter);

        Object observed = state.get();
        if (observed != PENDING) {
            // A registration racing with (or following) settlement must not
            // leave an already-claimed callback strongly retained in the
            // pending waiter queue. Removing before notification is race-safe:
            // if settle() already polled it, remove is a no-op and the claimed
            // bit still guarantees exactly-once callback delivery.
            waiters.remove(waiter);
            notifyWaiter(waiter, observed);
        }
        return registration;
    }

    int pendingRuntimeWaiterCount() {
        return waiters.size();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        CancellationException cancelled =
                new CancellationException("OresFuture was cancelled");
        if (!settle(new Cancelled(cancelled))) return false;

        if (cancelHookRun.compareAndSet(false, true)) {
            try {
                cancelHook.run();
            } catch (RuntimeException | Error ignored) {
                // Cancellation state is already authoritative. A host
                // cancellation hook cannot roll it back or poison waiter
                // delivery.
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
        Object observed = awaitState(0L, null);
        return reportGet(observed);
    }

    @Override
    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
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
        CompletableFuture<T> bridge = new CompletableFuture<>();
        whenCompleteRuntime((value, failure) -> {
            if (failure == null) bridge.complete(value);
            else bridge.completeExceptionally(failure);
        });
        return bridge;
    }

    private boolean settle(Object terminal) {
        if (!state.compareAndSet(PENDING, terminal)) return false;

        Waiter<T> waiter;
        while ((waiter = waiters.poll()) != null) {
            notifyWaiter(waiter, terminal);
        }
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
        } catch (RuntimeException | Error ignored) {
            // Runtime waiter failures must not stop delivery to other waiters or
            // mutate the settled Future. Scheduler plumbing owns its own
            // failure path.
        }
    }

    private Object awaitState(long timeout, TimeUnit unit) throws InterruptedException {
        Object observed = state.get();
        if (observed != PENDING) return observed;

        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        whenCompleteRuntime((value, failure) -> done.countDown());

        if (unit == null) {
            done.await();
        } else if (!done.await(timeout, unit)) {
            return state.get() == PENDING ? PENDING : state.get();
        }
        return state.get();
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
