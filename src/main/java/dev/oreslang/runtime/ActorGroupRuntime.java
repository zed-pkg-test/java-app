package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-owned actor-group state.
 *
 * No instance of this class is exposed to actor code. Actors receive only an
 * opaque ActorGroupHandle; root/supervisor code receives ActorGroupRef.
 */
final class ActorGroupRuntime<Out> {
    private final ActorRuntime runtime;
    private final ActorGroupId id;
    private final ActorRuntime.ActorKind kind;
    private final ActorGroupConfig.GroupPolicy policy;
    private final UUID capabilityNonce;
    private final ActorMailman<Out> mailman;
    private final ArrayBlockingQueue<ActorMail<Out>> outbox;
    private final Set<ActorRuntime.ActorId> actors =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicInteger actorCount = new AtomicInteger();
    private final AtomicLong nextSequence = new AtomicLong();
    private final Object outboxAdmissionLock = new Object();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicReference<Thread> executionLease = new AtomicReference<>();

    ActorGroupRuntime(
            ActorRuntime runtime,
            ActorGroupId id,
            ActorRuntime.ActorKind kind,
            ActorGroupConfig.GroupPolicy policy,
            UUID capabilityNonce,
            ActorMailman<Out> mailman) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.capabilityNonce = Objects.requireNonNull(capabilityNonce, "capabilityNonce");
        this.mailman = Objects.requireNonNull(mailman, "mailman");
        if (policy.actorKind() != kind) {
            throw new IllegalArgumentException(
                    "group policy actor kind " + policy.actorKind()
                            + " does not match group kind " + kind);
        }
        this.outbox = new ArrayBlockingQueue<>(policy.outboxCapacity());
    }

    ActorGroupId id() { return id; }
    ActorRuntime.ActorKind kind() { return kind; }
    ActorGroupConfig.GroupPolicy policy() { return policy; }
    int actorCount() { return actorCount.get(); }
    boolean stopped() { return stopped.get(); }

    ActorGroupRef<Out> ref() {
        return new ActorGroupRef<>(runtime, id, kind, capabilityNonce);
    }

    ActorGroupHandle<Out> handle() {
        return new ActorGroupHandle<>(id, kind, capabilityNonce);
    }

    boolean authenticates(ActorGroupHandle<?> handle) {
        return handle != null
                && id.equals(handle.id())
                && kind == handle.kind()
                && capabilityNonce.equals(handle.capabilityNonce());
    }

    boolean authenticates(ActorGroupRef<?> ref) {
        return ref != null
                && ref.ownedBy(runtime)
                && id.equals(ref.id())
                && kind == ref.kind()
                && capabilityNonce.equals(ref.capabilityNonce());
    }

    void reserveActor() {
        while (true) {
            if (stopped.get()) {
                throw new IllegalStateException("actor group " + id + " is stopped");
            }
            int current = actorCount.get();
            if (current >= policy.maxActors()) {
                throw new IllegalStateException(
                        "actor group capacity exceeded for " + id
                                + ": current=" + current
                                + " max=" + policy.maxActors());
            }
            if (actorCount.compareAndSet(current, current + 1)) return;
        }
    }

    void commitActor(ActorRuntime.ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId");
        if (!actors.add(actorId)) {
            actorCount.decrementAndGet();
            throw new IllegalStateException(
                    "actor " + actorId + " is already registered in group " + id);
        }
    }

    void abortActorReservation() {
        int remaining = actorCount.decrementAndGet();
        if (remaining < 0) {
            actorCount.incrementAndGet();
            throw new IllegalStateException("actor-group reservation accounting underflow");
        }
    }

    void removeActor(ActorRuntime.ActorId actorId) {
        if (!actors.remove(actorId)) return;
        int remaining = actorCount.decrementAndGet();
        if (remaining < 0) {
            actorCount.incrementAndGet();
            throw new IllegalStateException("actor-group membership accounting underflow");
        }
    }

    boolean containsActor(ActorRuntime.ActorId actorId) {
        return actors.contains(actorId);
    }

    @SuppressWarnings("unchecked")
    void emit(ActorRuntime.ActorRef<?> sender, Object output) {
        Objects.requireNonNull(sender, "sender");
        if (stopped.get()) {
            throw new IllegalStateException("actor group " + id + " is stopped");
        }
        if (!actors.contains(sender.id())) {
            throw new SecurityException(
                    "actor " + sender.id() + " is not a member of actor group " + id);
        }

        synchronized (outboxAdmissionLock) {
            long sequence = nextSequence.getAndIncrement();
            ActorMail<Out> mail = new ActorMail<>(
                    sender.id(),
                    id,
                    sequence,
                    (Out) output);
            if (!outbox.offer(mail)) {
                throw new IllegalStateException(
                        "actor group outbox capacity exceeded for " + id
                                + ": max=" + policy.outboxCapacity());
            }
        }
        scheduleMailman();
    }

    private void scheduleMailman() {
        if (stopped.get()) return;
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            runtime.executeActorGroupMailman(this::runMailmanQuantum);
        } catch (RuntimeException failure) {
            scheduled.set(false);
            throw failure;
        }
    }

    private void runMailmanQuantum() {
        Thread carrier = Thread.currentThread();
        if (!executionLease.compareAndSet(null, carrier)) {
            scheduled.set(false);
            throw new IllegalStateException(
                    "single-mailman execution lease violated for actor group " + id);
        }

        try {
            int throughput = runtime.dispatcherConfig().throughput();
            long maxNanos = runtime.dispatcherConfig().maxBatchNanos();
            long started = System.nanoTime();
            ActorGroupContext<Out> context = new ActorGroupContext<>() {
                @Override public ActorGroupId groupId() { return id; }
                @Override public int actorCount() { return ActorGroupRuntime.this.actorCount(); }
                @Override public <M> void send(ActorRuntime.ActorRef<M> target, M message) {
                    runtime.send(target, message);
                }
            };

            int handled = 0;
            while (!stopped.get() && handled < throughput) {
                if (handled > 0 && System.nanoTime() - started >= maxNanos) break;
                ActorMail<Out> mail = outbox.poll();
                if (mail == null) break;
                try {
                    mailman.receiveMail(mail, context);
                } catch (Exception failure) {
                    runtime.onActorGroupMailmanFailure(id, failure);
                    break;
                }
                handled++;
            }
        } finally {
            if (!executionLease.compareAndSet(carrier, null)) {
                throw new IllegalStateException(
                        "mailman execution lease ownership changed for actor group " + id);
            }
            scheduled.set(false);
            if (!stopped.get() && !outbox.isEmpty()) scheduleMailman();
        }
    }

    void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        outbox.clear();
    }

    int outboxSize() {
        return outbox.size();
    }
}
