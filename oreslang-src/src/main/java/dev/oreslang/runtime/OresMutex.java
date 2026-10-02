package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Oreslang synchronization primitives.
 *
 * <p>{@link Local} is an actor/private-domain mutex. It deliberately does not
 * use a JVM lock: the creating semantic execution domain owns it and recursive
 * acquisition is rejected. Shared actors may migrate JVM worker threads without
 * changing that domain. {@link Shared} is an explicit same-process
 * shared-memory capability backed by a JVM synchronizer with poisoning and
 * acquire/release ordering.</p>
 */
public final class OresMutex {
    private OresMutex() { }

    public static <T> Local<T> local(T value) {
        return new Local<>(value);
    }

    public static <T> Shared<T> shared(T value) {
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy != null) {
            actorPolicy.require(
                    IsolatePolicy.Capability.SHARED_MEMORY,
                    "SharedMutex.new");
        }
        return new Shared<>(value);
    }

    public sealed interface Lock<T> permits Local, Shared {
        Guard<T> lock();
        Optional<Guard<T>> tryLock();
        Optional<Guard<T>> lockFor(Duration timeout);
        CompletableFuture<Guard<T>> lockAsync();
        <R> R withLock(Function<? super T, ? extends R> body);
        boolean isPoisoned();
    }

    /**
     * Linear lock capability. Oreslang lowering owns guard release; guest code
     * may release early, but the mutex itself has no public unlock operation.
     */
    public interface Guard<T> extends AutoCloseable {
        T value();
        boolean released();
        void release();

        /**
         * Releases after an abnormal critical-section exit. Shared mutexes are
         * poisoned; actor-local mutexes simply release because their state is
         * confined to the failing actor/private domain.
         */
        void fail();

        @Override
        default void close() {
            release();
        }
    }

    public static final class RecursiveLockException extends IllegalStateException {
        public RecursiveLockException(String kind) {
            super(kind + " is non-reentrant; recursive acquisition is forbidden");
        }
    }

    public static final class WrongMutexDomainException extends IllegalStateException {
        public WrongMutexDomainException(String message) {
            super(message);
        }
    }

    public static final class PoisonedMutexException extends IllegalStateException {
        public PoisonedMutexException() {
            super("SharedMutex is poisoned because a previous critical section exited abnormally; call recover(...)");
        }
    }

    /**
     * Actor/private-domain mutex. There is no host lock and therefore no
     * blocking path. Ownership follows the actor execution domain, not the
     * transient JVM worker Thread. Outside actor execution, Thread identity is
     * used as the local domain.
     */
    public static final class Local<T> implements Lock<T> {
        private final T value;
        private final Object ownerDomain;
        private boolean held;

        private Local(T value) {
            this.value = value;
            this.ownerDomain = ActorRuntime.currentExecutionDomain();
        }

        private void requireOwnerDomain() {
            if (!Objects.equals(ActorRuntime.currentExecutionDomain(), ownerDomain)) {
                throw new WrongMutexDomainException(
                        "Mutex<T> is actor/private-domain state and cannot be accessed from another actor/execution domain; use SharedMutex<T>");
            }
        }

        @Override
        public Guard<T> lock() {
            requireOwnerDomain();
            if (held) throw new RecursiveLockException("Mutex<T>");
            held = true;
            return new LocalGuard();
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            requireOwnerDomain();
            if (held) return Optional.empty();
            held = true;
            return Optional.of(new LocalGuard());
        }

        @Override
        public Optional<Guard<T>> lockFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            return tryLock();
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsync() {
            try {
                return CompletableFuture.completedFuture(lock());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public <R> R withLock(Function<? super T, ? extends R> body) {
            Objects.requireNonNull(body, "body");
            Guard<T> guard = lock();
            try {
                R result = body.apply(value);
                guard.release();
                return result;
            } catch (RuntimeException | Error failure) {
                guard.fail();
                throw failure;
            }
        }

        @Override
        public boolean isPoisoned() {
            return false;
        }

        private final class LocalGuard implements Guard<T> {
            private boolean released;

            @Override
            public T value() {
                requireOwnerDomain();
                if (released) throw new IllegalStateException("MutexGuard has been released");
                return value;
            }

            @Override public boolean released() { return released; }

            @Override
            public void release() {
                requireOwnerDomain();
                if (released) return;
                released = true;
                held = false;
            }

            @Override
            public void fail() {
                release();
            }
        }
    }

    /**
     * Explicit same-process shared-memory mutex. This is intentionally not a
     * distributed lock and must not be serialized across OS-process/Graal
     * isolate boundaries.
     */
    public static final class Shared<T> implements Lock<T>, ActorRuntime.Sendable {
        private static final int MAX_ASYNC_WAITERS = 8_192;

        private final T value;
        private final Semaphore permit = new Semaphore(1, true);
        private final AtomicBoolean poisoned = new AtomicBoolean();
        private final AtomicInteger asyncWaiters = new AtomicInteger();
        private final AtomicReference<ActorRuntime> owningRuntime = new AtomicReference<>();
        private final Set<Object> activeDomains = ConcurrentHashMap.newKeySet();

        private Shared(T value) {
            this.value = value;
        }

        boolean bindToRuntime(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            ActorRuntime existing = owningRuntime.get();
            if (existing == runtime) return true;
            if (existing != null) return false;
            if (owningRuntime.compareAndSet(null, runtime)) return true;
            return owningRuntime.get() == runtime;
        }

        private void requireActorAccess() {
            IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
            if (actorPolicy != null) {
                actorPolicy.require(
                        IsolatePolicy.Capability.SHARED_MEMORY,
                        "SharedMutex operation");
            }

            ActorRuntime current = ActorRuntime.currentActorRuntime();
            if (current != null && !bindToRuntime(current)) {
                throw new WrongMutexDomainException(
                        "SharedMutex belongs to another ActorRuntime");
            }
        }

        private void rejectBlockingActorAcquisition() {
            requireActorAccess();
            if (ActorRuntime.inActorExecution()) {
                throw new WrongMutexDomainException(
                        "blocking SharedMutex.lock()/lock_for()/with_lock() is forbidden during actor execution; use try_lock() or await lock_async()");
            }
        }

        /**
         * Reserve the semantic execution domain before waiting. This prevents a
         * single actor from queueing a second acquisition behind itself and
         * deadlocking, even when the actor migrates JVM workers.
         */
        private Object reserveDomain(boolean tryOnly) {
            requireActorAccess();
            Object domain = ActorRuntime.currentExecutionDomain();
            if (activeDomains.add(domain)) return domain;
            if (tryOnly) return null;
            throw new RecursiveLockException("SharedMutex<T>");
        }

        private void releaseDomain(Object domain) {
            if (domain != null) activeDomains.remove(domain);
        }

        private int asyncWaiterLimit() {
            IsolatePolicy policy = ActorRuntime.currentActorPolicy();
            return policy == null
                    ? MAX_ASYNC_WAITERS
                    : Math.min(MAX_ASYNC_WAITERS, policy.maxMailboxMessages());
        }

        private boolean reserveAsyncWaiter(int limit) {
            while (true) {
                int current = asyncWaiters.get();
                if (current >= limit) return false;
                if (asyncWaiters.compareAndSet(current, current + 1)) return true;
            }
        }

        private void releaseAsyncWaiter() {
            int remaining = asyncWaiters.decrementAndGet();
            if (remaining < 0) {
                asyncWaiters.incrementAndGet();
                throw new IllegalStateException("SharedMutex async waiter accounting underflow");
            }
        }

        private Guard<T> checkedGuardAfterAcquire(Object ownerDomain, boolean enforceOwnerDomain) {
            if (poisoned.get()) {
                releaseDomain(ownerDomain);
                permit.release();
                throw new PoisonedMutexException();
            }
            return new SharedGuard(ownerDomain, enforceOwnerDomain);
        }

        @Override
        public Guard<T> lock() {
            rejectBlockingActorAcquisition();
            Object ownerDomain = reserveDomain(false);
            try {
                permit.acquire();
            } catch (InterruptedException interrupted) {
                releaseDomain(ownerDomain);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("SharedMutex lock wait interrupted");
            }
            return checkedGuardAfterAcquire(ownerDomain, true);
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            Object ownerDomain = reserveDomain(true);
            if (ownerDomain == null) return Optional.empty();
            if (!permit.tryAcquire()) {
                releaseDomain(ownerDomain);
                return Optional.empty();
            }
            return Optional.of(checkedGuardAfterAcquire(ownerDomain, true));
        }

        @Override
        public Optional<Guard<T>> lockFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            rejectBlockingActorAcquisition();
            Object ownerDomain = reserveDomain(true);
            if (ownerDomain == null) return Optional.empty();
            try {
                if (!permit.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    releaseDomain(ownerDomain);
                    return Optional.empty();
                }
                return Optional.of(checkedGuardAfterAcquire(ownerDomain, true));
            } catch (InterruptedException interrupted) {
                releaseDomain(ownerDomain);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("SharedMutex lock wait interrupted");
            }
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsync() {
            Object ownerDomain = reserveDomain(false);
            int waiterLimit = asyncWaiterLimit();
            if (!reserveAsyncWaiter(waiterLimit)) {
                releaseDomain(ownerDomain);
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "SharedMutex async waiter limit exceeded: " + waiterLimit));
            }

            boolean enforceOwnerDomain = ActorRuntime.inActorExecution();
            CompletableFuture<Guard<T>> future = new CompletableFuture<>();
            final Thread waiter;
            try {
                waiter = Thread.ofVirtual().name("ores-shared-mutex-waiter").unstarted(() -> {
                    try {
                        permit.acquire();
                        Guard<T> guard = checkedGuardAfterAcquire(ownerDomain, enforceOwnerDomain);
                        if (!future.complete(guard)) {
                            // Cancellation can win after the permit is acquired but
                            // before the future accepts its result. This cleanup is
                            // runtime-owned and may execute on the waiter virtual
                            // thread, so it must not apply the guest guard's actor-
                            // domain release check.
                            ((SharedGuard) guard).releaseFromRuntime();
                        }
                    } catch (InterruptedException interrupted) {
                        releaseDomain(ownerDomain);
                        Thread.currentThread().interrupt();
                        future.completeExceptionally(
                                new java.util.concurrent.CancellationException(
                                        "SharedMutex async wait cancelled"));
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                });
            } catch (Throwable startupFailure) {
                releaseDomain(ownerDomain);
                releaseAsyncWaiter();
                return CompletableFuture.failedFuture(startupFailure);
            }

            future.whenComplete((ignored, failure) -> {
                releaseAsyncWaiter();
                if (future.isCancelled()) waiter.interrupt();
            });

            try {
                waiter.start();
            } catch (Throwable startupFailure) {
                releaseDomain(ownerDomain);
                future.completeExceptionally(startupFailure);
            }
            return future;
        }

        @Override
        public <R> R withLock(Function<? super T, ? extends R> body) {
            Objects.requireNonNull(body, "body");
            Guard<T> guard = lock();
            try {
                R result = body.apply(value);
                guard.release();
                return result;
            } catch (RuntimeException | Error failure) {
                guard.fail();
                throw failure;
            }
        }

        public <R> R recover(Function<? super T, ? extends R> repair) {
            Objects.requireNonNull(repair, "repair");
            Object ownerDomain = reserveDomain(false);
            boolean acquired = false;
            try {
                if (ActorRuntime.inActorExecution()) {
                    // Recovery is expected to happen after the poisoning guard
                    // released its permit. Never block an actor if another
                    // recovery attempt is already in progress.
                    acquired = permit.tryAcquire();
                    if (!acquired) {
                        throw new WrongMutexDomainException(
                                "SharedMutex recovery is busy; actor recovery never blocks, retry from a later mailbox turn");
                    }
                } else {
                    try {
                        permit.acquire();
                        acquired = true;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CancellationException(
                                "SharedMutex recovery wait interrupted");
                    }
                }

                if (!poisoned.get()) {
                    throw new IllegalStateException(
                            "SharedMutex is not poisoned; recover(...) is only for repairing poisoned state");
                }
                try {
                    R result = repair.apply(value);
                    poisoned.set(false);
                    return result;
                } catch (RuntimeException | Error failure) {
                    poisoned.set(true);
                    throw failure;
                }
            } finally {
                releaseDomain(ownerDomain);
                if (acquired) permit.release();
            }
        }

        @Override
        public boolean isPoisoned() {
            requireActorAccess();
            return poisoned.get();
        }

        /** Shared mutex handles cross actor mailboxes by reference only when SHARED_MEMORY is permitted. */
        @Override
        public Object freezeForSend() {
            return this;
        }

        private final class SharedGuard implements Guard<T> {
            private final Object ownerDomain;
            private final boolean enforceOwnerDomain;
            private final AtomicBoolean released = new AtomicBoolean();

            private SharedGuard(Object ownerDomain, boolean enforceOwnerDomain) {
                this.ownerDomain = ownerDomain;
                this.enforceOwnerDomain = enforceOwnerDomain;
            }

            private void requireOwnerDomain() {
                if (enforceOwnerDomain
                        && !Objects.equals(ActorRuntime.currentExecutionDomain(), ownerDomain)) {
                    throw new WrongMutexDomainException(
                            "SharedMutex guard belongs to another actor/execution domain");
                }
            }

            @Override
            public T value() {
                requireOwnerDomain();
                if (released()) throw new IllegalStateException("MutexGuard has been released");
                return value;
            }

            @Override public boolean released() { return released.get(); }

            @Override
            public void release() {
                requireOwnerDomain();
                releaseFromRuntime();
            }

            private void releaseFromRuntime() {
                if (!released.compareAndSet(false, true)) return;
                releaseDomain(ownerDomain);
                permit.release();
            }

            @Override
            public void fail() {
                requireOwnerDomain();
                if (released()) return;
                poisoned.set(true);
                release();
            }
        }
    }
}
