package dev.oreslang.runtime;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Linear ownership gate for an HTTP transport stream handed from the
 * host/supervisor to one actor.
 *
 * <p>This is intentionally modeled after Erlang/OTP's controlling-process
 * semantics: exactly one owner exists at a time, only the current ownership
 * domain may perform I/O, and ownership transfer is mutually exclusive with
 * in-flight I/O. The lease carries identity/authority; guest code never needs
 * a raw OS file descriptor.</p>
 *
 * <p>For HTTP/1.1 or raw TCP, one lease may represent an accepted socket. For
 * HTTP/2 or HTTP/3, the lease must represent one request/response stream, not
 * the multiplexed connection.</p>
 */
public final class HttpTransportLease implements AutoCloseable {
    public HttpTransportLease() { }
    public enum OwnerKind {
        SUPERVISOR,
        ACTOR,
        RELEASED
    }

    public record Owner(OwnerKind kind, ActorRuntime.ActorId actorId) {
        public Owner {
            Objects.requireNonNull(kind, "kind");
            if (kind == OwnerKind.ACTOR && actorId == null) {
                throw new IllegalArgumentException("ACTOR ownership requires actorId");
            }
            if (kind != OwnerKind.ACTOR && actorId != null) {
                throw new IllegalArgumentException(kind + " ownership cannot carry actorId");
            }
        }

        static Owner supervisor() {
            return new Owner(OwnerKind.SUPERVISOR, null);
        }

        static Owner actor(ActorRuntime.ActorId actorId) {
            return new Owner(OwnerKind.ACTOR, Objects.requireNonNull(actorId));
        }

        static Owner released() {
            return new Owner(OwnerKind.RELEASED, null);
        }
    }

    @FunctionalInterface
    public interface Operation<T> {
        T run();
    }

    @FunctionalInterface
    public interface IoOperation<T> {
        T run() throws IOException;
    }

    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    private Owner owner = Owner.supervisor();

    public Owner owner() {
        gate.readLock().lock();
        try {
            return owner;
        } finally {
            gate.readLock().unlock();
        }
    }

    public Optional<ActorRuntime.ActorId> actorOwner() {
        return Optional.ofNullable(owner().actorId());
    }

    /**
     * Host-only transfer from the supervisor to exactly one actor. The write
     * lock is the transfer barrier: it waits for every current I/O lease to
     * finish and prevents new I/O from starting until ownership is published.
     */
    void transferToActor(ActorRuntime.ActorId target) {
        requireSupervisorContext("transfer HTTP transport ownership");
        Objects.requireNonNull(target, "target");
        gate.writeLock().lock();
        try {
            if (owner.kind() != OwnerKind.SUPERVISOR) {
                throw new IllegalStateException(
                        "HTTP transport is not supervisor-owned; current owner=" + owner);
            }
            owner = Owner.actor(target);
        } finally {
            gate.writeLock().unlock();
        }
    }

    /**
     * Run one non-throwing transport metadata operation while proving actor
     * ownership.
     */
    <T> T withActorOperation(
            ActorRuntime.ActorId actorId,
            String operation,
            Operation<T> action) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(action, "action");
        gate.readLock().lock();
        try {
            requireActorOwnerLocked(actorId, operation);
            return action.run();
        } finally {
            gate.readLock().unlock();
        }
    }

    /**
     * Run one transport operation while proving actor ownership. Multiple
     * operations by the same owner may proceed concurrently, but a transfer or
     * revocation cannot race any of them.
     */
    <T> T withActorIo(
            ActorRuntime.ActorId actorId,
            String operation,
            IoOperation<T> io) throws IOException {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(io, "io");
        gate.readLock().lock();
        try {
            requireActorOwnerLocked(actorId, operation);
            return io.run();
        } finally {
            gate.readLock().unlock();
        }
    }

    void assertActorOwner(ActorRuntime.ActorId actorId, String operation) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operation, "operation");
        gate.readLock().lock();
        try {
            requireActorOwnerLocked(actorId, operation);
        } finally {
            gate.readLock().unlock();
        }
    }

    private void requireActorOwnerLocked(
            ActorRuntime.ActorId actorId,
            String operation) {
        if (owner.kind() != OwnerKind.ACTOR || !actorId.equals(owner.actorId())) {
            throw new SecurityException(
                    "HTTP transport operation '" + operation
                            + "' denied for actor " + actorId
                            + "; current owner=" + owner);
        }
    }

    /**
     * Runtime-only terminal revocation. A released lease is never reusable:
     * this prevents stale actor capabilities from becoming valid again if an
     * OS fd number is later recycled by the kernel.
     */
    void revokeFromActor(ActorRuntime.ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId");
        gate.writeLock().lock();
        try {
            if (owner.kind() == OwnerKind.RELEASED) return;
            if (owner.kind() != OwnerKind.ACTOR || !actorId.equals(owner.actorId())) {
                throw new SecurityException(
                        "only the controlling actor may be revoked; current owner=" + owner);
            }
            owner = Owner.released();
        } finally {
            gate.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        requireSupervisorContext("close an HTTP transport lease");
        gate.writeLock().lock();
        try {
            if (owner.kind() == OwnerKind.ACTOR) {
                throw new IllegalStateException(
                        "cannot close an actor-owned HTTP transport lease");
            }
            owner = Owner.released();
        } finally {
            gate.writeLock().unlock();
        }
    }

    private static void requireSupervisorContext(String operation) {
        if (ActorRuntime.inActorExecution() || ActorRuntime.inRootExecution()) {
            throw new SecurityException(
                    "guest code cannot " + operation
                            + "; this operation belongs to the host/supervisor");
        }
    }
}
