package dev.oreslang.runtime;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Compiler-facing allocation/affinity boundary for local rt ownership operations.
 * This is an actor identity, not a carrier thread or a shared-memory capability.
 * The compiler still proves copy contracts, ownership modes, and borrow lifetimes.
 * Mailbox transport must detach values before they enter this domain.
 */
public final class ActorMemoryDomain {
    public enum Operation { COPY, TAKE, SHARE, BORROW }

    private final ActorRuntime runtime;
    private final ActorRuntime.ActorId owner;
    private final ActorRuntime.ActorKind kind;
    private final IsolatePolicy policy;
    private final Object executionDomain;
    private final BooleanSupplier alive;
    private final LongFunction<Runnable> reserveBytes;
    private final Set<Reservation> reservations = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean retired = new AtomicBoolean();

    ActorMemoryDomain(
            ActorRuntime runtime, ActorRuntime.ActorId owner,
            ActorRuntime.ActorKind kind, IsolatePolicy policy, Object executionDomain,
            BooleanSupplier alive, LongFunction<Runnable> reserveBytes) {
        this.runtime = Objects.requireNonNull(runtime);
        this.owner = Objects.requireNonNull(owner);
        this.kind = Objects.requireNonNull(kind);
        this.policy = Objects.requireNonNull(policy);
        this.executionDomain = Objects.requireNonNull(executionDomain);
        this.alive = Objects.requireNonNull(alive);
        this.reserveBytes = Objects.requireNonNull(reserveBytes);
    }

    public ActorRuntime.ActorId owner() { return owner; }
    public ActorRuntime.ActorKind kind() { return kind; }
    public IsolatePolicy policy() { return policy; }
    public boolean retired() { return retired.get() || !alive.getAsBoolean(); }

    /** A source provenance check, never a grant of mutation or lifetime authority. */
    public void requireLocalOperation(Operation operation, ActorMemoryDomain source) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(source, "source domain");
        requireCurrentOwner();
        if (source != this) {
            throw new SecurityException("rt " + operation.name().toLowerCase(java.util.Locale.ROOT)
                    + " cannot access a foreign actor memory domain; use validated mailbox transport");
        }
    }

    public void requireCurrentOwner() {
        if (retired()) throw new IllegalStateException("actor memory domain is retired: " + owner);
        if (ActorRuntime.currentActorRuntime() != runtime
                || ActorRuntime.currentActorExecutionDomain() != executionDomain) {
            throw new SecurityException("actor memory domain requires its owning actor turn: " + owner);
        }
    }

    /** Reserve before constructing compiler-managed state; retain until that state dies. */
    public Reservation reserveAllocation(long bytes) {
        requireCurrentOwner();
        if (bytes < 0) throw new IllegalArgumentException("allocation bytes cannot be negative");
        Runnable release = reserveBytes.apply(bytes);
        Reservation reservation = new Reservation(bytes, release);
        // No payload is retained by the domain or the cleanup callback.
        if (bytes != 0) reservations.add(reservation);
        if (retired()) {
            reservation.close();
            throw new IllegalStateException("actor memory domain retired during allocation: " + owner);
        }
        return reservation;
    }

    /**
     * Transactional allocation hook for a compiler-verified local copy plan.
     * bytes must be a compiler/backend-proven upper bound, including scratch
     * storage retained during construction. This hook cannot infer a class copy
     * contract from a Java Supplier and must never be exposed as a guest callback API.
     */
    public <T> Allocation<T> allocateCopy(
            ActorMemoryDomain source, long bytes, Supplier<? extends T> copyPlan,
            RuntimeGarbageCollector collector) {
        requireLocalOperation(Operation.COPY, source);
        Objects.requireNonNull(copyPlan, "verified copy plan");
        Objects.requireNonNull(collector, "collector");
        runtime.schedulerSafepoint();
        Reservation reservation = reserveAllocation(bytes);
        try {
            T value = Objects.requireNonNull(copyPlan.get(), "copy plan returned null");
            // A plan can fail, cancel, or exhaust untrusted fuel. Never publish
            // its result after the domain has been revoked.
            runtime.schedulerSafepoint();
            requireCurrentOwner();
            RuntimeGarbageCollector.CleanupHandle cleanup = collector.track(value, reservation::close);
            Allocation<T> allocation = new Allocation<>(value, reservation, cleanup);
            reservation.allocation = new WeakReference<>(allocation);
            if (reservation.released()) {
                allocation.close();
                throw new IllegalStateException("copy allocation was revoked before publication");
            }
            return allocation;
        } catch (RuntimeException | Error failure) {
            try { reservation.close(); }
            catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    /** Called only by actor lifecycle finalization, after active turns have left. */
    void retireFromActor() {
        if (!retired.compareAndSet(false, true)) return;
        for (Reservation reservation : reservations) reservation.close();
    }

    public final class Reservation implements AutoCloseable {
        private final long bytes;
        private final Runnable release;
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile WeakReference<Allocation<?>> allocation;

        private Reservation(long bytes, Runnable release) {
            this.bytes = bytes;
            this.release = Objects.requireNonNull(release);
        }

        public ActorMemoryDomain domain() { return ActorMemoryDomain.this; }
        public long bytes() { return bytes; }
        public boolean released() { return released.get(); }

        @Override public synchronized void close() {
            if (!released.compareAndSet(false, true)) return;
            try {
                release.run();
                WeakReference<Allocation<?>> handle = allocation;
                Allocation<?> live = handle == null ? null : handle.get();
                if (live != null) live.value = null;
                reservations.remove(this);
            } catch (RuntimeException | Error failure) {
                released.set(false);
                throw failure;
            }
        }
    }

    /** Internal lowering handle. It is not an actor message or a shared capability. */
    public final class Allocation<T> implements AutoCloseable {
        private volatile T value;
        private final Reservation reservation;
        private final RuntimeGarbageCollector.CleanupHandle cleanup;

        private Allocation(T value, Reservation reservation, RuntimeGarbageCollector.CleanupHandle cleanup) {
            this.value = value;
            this.reservation = reservation;
            this.cleanup = cleanup;
        }

        public ActorMemoryDomain domain() { return ActorMemoryDomain.this; }

        public T value() {
            requireCurrentOwner();
            T current = value;
            if (reservation.released() || current == null) throw new IllegalStateException("actor allocation has been released");
            return current;
        }

        @Override public void close() { cleanup.close(); value = null; }
    }
}
