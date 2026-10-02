package dev.oreslang.runtime;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Host-side actor substrate used by the first interpreter.
 *
 * The language contract is stronger than the JVM backing implementation:
 * mutable state is actor-owned, cross-actor values are copied/frozen, and
 * explicitly shared regions are deeply immutable. The JVM heap remains the
 * backing store for this prototype; ActorRuntime therefore enforces semantic
 * heap isolation and mailbox budgets while the compiler/runtime continue to
 * move toward fully actor-local allocation arenas.
 */
public final class ActorRuntime implements AutoCloseable {
    private static final int MAX_FREEZE_DEPTH = 256;
    private static final int MAX_FAILURE_TOMBSTONES = 4096;
    private static final long SHARED_HANDLE_BYTES = 64L;

    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final Map<String, SingletonRegistration> singletonActors = new ConcurrentHashMap<>();
    private final Map<ActorId, ActorFailure> failures = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<ActorId> failureOrder =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Map<ActorId, Map<UUID, ActorId>> monitorsByTarget = new ConcurrentHashMap<>();
    private final ThreadLocal<ActorId> currentActor = new ThreadLocal<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger activeActorSlots = new AtomicInteger();
    private final IsolatePolicy policyCeiling;

    public ActorRuntime() {
        this(IsolatePolicy.developer());
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this.policyCeiling = java.util.Objects.requireNonNull(policyCeiling);
    }

    public IsolatePolicy policyCeiling() { return policyCeiling; }

    public record ActorId(UUID value) {
        public ActorId {
            java.util.Objects.requireNonNull(value, "value");
        }
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
        @Override public String toString() { return value.toString(); }
    }

    public enum ActorState {
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    public record ActorFailure(String type, String message) { }

    private record SingletonRegistration(ActorId actorId, String contract) { }

    public record MonitorRef(UUID value, ActorId target) {
        public MonitorRef {
            java.util.Objects.requireNonNull(value, "value");
            java.util.Objects.requireNonNull(target, "target");
        }
    }

    public record ActorSnapshot(
            ActorId id,
            ActorState state,
            int mailboxMessages,
            long mailboxBytes,
            long activeMessageBytes,
            long ownedStateBytes,
            long manualGcRequests,
            ActorFailure failure) {
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id.toString());
            result.put("state", state.name().toLowerCase(java.util.Locale.ROOT));
            result.put("mailbox_messages", mailboxMessages);
            result.put("mailbox_bytes", mailboxBytes);
            result.put("active_message_bytes", activeMessageBytes);
            result.put("owned_state_bytes", ownedStateBytes);
            result.put(
                    "estimated_actor_heap_bytes",
                    safeAdd(safeAdd(mailboxBytes, activeMessageBytes), ownedStateBytes));
            result.put("heap_backend", "logical_jvm");
            result.put("physical_heap_isolation", false);
            result.put("manual_gc_requests", manualGcRequests);
            if (failure != null) {
                result.put("failure_type", failure.type());
                result.put("failure_message", failure.message() == null ? "" : failure.message());
            }
            return Map.copyOf(result);
        }
    }

    /**
     * Opaque handle to a deeply immutable graph that may be reused by multiple
     * actors without copying the backing graph again.
     */
    public static final class Shared<T> {
        private final UUID regionId;
        private final T value;
        private final long estimatedBytes;

        private Shared(T value, long estimatedBytes) {
            this.regionId = UUID.randomUUID();
            this.value = value;
            this.estimatedBytes = estimatedBytes;
        }

        public UUID regionId() { return regionId; }
        public T value() { return value; }
        public long estimatedBytes() { return estimatedBytes; }

        @Override
        public String toString() {
            return "Shared[" + regionId + ", estimatedBytes=" + estimatedBytes + "]";
        }
    }

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, ActorContext<M> context) throws Exception;
    }

    public interface ActorContext<M> {
        ActorRef<M> self();
        ActorRuntime runtime();
        IsolatePolicy policy();
        long ownedStateBytes();
        void replaceOwnedStateBytes(long estimatedBytes);
    }

    public final class ActorRef<M> {
        private final ActorId id;

        private ActorRef(ActorId id) {
            this.id = id;
        }

        public ActorId id() { return id; }

        public void send(M message) {
            ActorRuntime.this.send(this, message);
        }

        public ActorSnapshot snapshot() {
            return ActorRuntime.this.snapshot(this);
        }

        @Override
        public String toString() {
            return "ActorRef[" + id.value() + "]";
        }
    }

    /**
     * Creates actor-local behavior inside the actor thread. The Supplier should
     * be generated by Oreslang lowering, not supplied from untrusted guest code.
     */
    public <M> ActorRef<M> spawn(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnOwned(policyCeiling, 0L, behaviorFactory);
    }

    public <M> ActorRef<M> spawn(IsolatePolicy policy, Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnOwned(policy, 0L, behaviorFactory);
    }

    public <M> ActorRef<M> spawnOwned(
            IsolatePolicy policy,
            long initialOwnedStateBytes,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        java.util.Objects.requireNonNull(behaviorFactory, "behaviorFactory");
        requireWithinCeiling(policy);
        requireOwnedStateBytes(policy, initialOwnedStateBytes);
        reserveActorSlot();
        ActorId id = ActorId.create();
        ActorRef<M> ref = new ActorRef<>(id);
        ActorCell<M> cell = new ActorCell<>(ref, policy, initialOwnedStateBytes, behaviorFactory);
        boolean inserted = false;
        try {
            ActorCell<?> previous = actors.putIfAbsent(id, cell);
            if (previous != null) throw new IllegalStateException("actor id collision");
            inserted = true;
            cell.start();
            return ref;
        } catch (Throwable failure) {
            if (inserted) removeActorCell(id, cell);
            else releaseActorSlot();
            throw failure;
        }
    }

    /**
     * Returns one named actor for this runtime/context. Mutable singleton state
     * remains owned by that actor; callers still communicate exclusively by
     * message passing.
     */
    @SuppressWarnings("unchecked")
    public synchronized <M> ActorRef<M> spawnSingleton(
            String name,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnSingletonOwned(name, 0L, behaviorFactory);
    }

    @SuppressWarnings("unchecked")
    public synchronized <M> ActorRef<M> spawnSingletonOwned(
            String name,
            long initialOwnedStateBytes,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnSingletonOwned(name, initialOwnedStateBytes, null, behaviorFactory);
    }

    @SuppressWarnings("unchecked")
    public synchronized <M> ActorRef<M> spawnSingletonOwned(
            String name,
            long initialOwnedStateBytes,
            String contract,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        validateSingletonName(name);
        requireOwnedStateBytes(policyCeiling, initialOwnedStateBytes);
        SingletonRegistration registration = singletonActors.get(name);
        if (registration != null) {
            ActorCell<?> existing = actors.get(registration.actorId());
            if (existing != null
                    && (existing.state == ActorState.STARTING || existing.state == ActorState.RUNNING)) {
                if (contract != null && registration.contract() != null
                        && !contract.equals(registration.contract())) {
                    throw new IllegalStateException(
                            "singleton actor '" + name + "' already exists with an incompatible runtime contract");
                }
                return (ActorRef<M>) existing.ref;
            }
            singletonActors.remove(name, registration);
        }

        ActorRef<M> created = spawnOwned(policyCeiling, initialOwnedStateBytes, behaviorFactory);
        SingletonRegistration createdRegistration = new SingletonRegistration(created.id(), contract);
        singletonActors.put(name, createdRegistration);

        // The virtual thread may fail during behavior construction before the
        // registry insertion above. Remove that stale name immediately; if it
        // exits after this check its normal lifecycle cleanup removes the name.
        if (!actors.containsKey(created.id())) {
            singletonActors.remove(name, createdRegistration);
        }
        return created;
    }

    @SuppressWarnings("unchecked")
    public <M> Optional<ActorRef<M>> lookupSingleton(String name) {
        SingletonRegistration registration = singletonActors.get(name);
        if (registration == null) return Optional.empty();
        ActorCell<?> cell = actors.get(registration.actorId());
        if (cell == null) return Optional.empty();
        return Optional.of((ActorRef<M>) cell.ref);
    }

    public Optional<ActorId> currentActorId() {
        return Optional.ofNullable(currentActor.get());
    }

    @SuppressWarnings("unchecked")
    public <M> Optional<ActorRef<M>> currentActorRef() {
        ActorId id = currentActor.get();
        if (id == null) return Optional.empty();
        ActorCell<?> cell = actors.get(id);
        if (cell == null) return Optional.empty();
        return Optional.of((ActorRef<M>) cell.ref);
    }

    public int activeActorCount() {
        return activeActorSlots.get();
    }

    private void reserveActorSlot() {
        while (true) {
            int current = activeActorSlots.get();
            if (current >= policyCeiling.maxActors()) {
                throw new IllegalStateException(
                        "actor limit exceeded for isolate: maxActors=" + policyCeiling.maxActors());
            }
            if (activeActorSlots.compareAndSet(current, current + 1)) return;
        }
    }

    private void releaseActorSlot() {
        int remaining = activeActorSlots.decrementAndGet();
        if (remaining < 0) {
            activeActorSlots.incrementAndGet();
            throw new IllegalStateException("actor slot underflow");
        }
    }

    private void removeActorCell(ActorId id, ActorCell<?> cell) {
        if (actors.remove(id, cell)) releaseActorSlot();
    }

    /**
     * Erlang-style unidirectional monitor. The watcher receives one immutable
     * DOWN message when the target exits. Monitoring an already-dead actor
     * immediately queues a DOWN message with reason "noproc" or "failed".
     */
    public MonitorRef monitor(ActorRef<?> watcher, ActorRef<?> target) {
        java.util.Objects.requireNonNull(watcher, "watcher");
        java.util.Objects.requireNonNull(target, "target");
        if (watcher.id().equals(target.id())) {
            throw new IllegalArgumentException("an actor cannot monitor itself");
        }
        if (!actors.containsKey(watcher.id())) {
            throw new IllegalStateException("monitor watcher is not a live actor");
        }

        MonitorRef monitor = new MonitorRef(UUID.randomUUID(), target.id());
        ActorCell<?> targetCell = actors.get(target.id());
        if (targetCell == null) {
            ActorFailure failure = failures.get(target.id());
            sendDown(watcher.id(), monitor, failure == null ? "noproc" : "failed", failure);
            return monitor;
        }

        monitorsByTarget.computeIfAbsent(target.id(), ignored -> new ConcurrentHashMap<>())
                .put(monitor.value(), watcher.id());

        // Close the race where the target exits after the liveness check but
        // before monitor registration.
        if (!actors.containsKey(target.id())) {
            Map<UUID, ActorId> monitors = monitorsByTarget.get(target.id());
            if (monitors != null && monitors.remove(monitor.value(), watcher.id())) {
                if (monitors.isEmpty()) monitorsByTarget.remove(target.id(), monitors);
                ActorFailure failure = failures.get(target.id());
                sendDown(watcher.id(), monitor, failure == null ? "normal" : "failed", failure);
            }
        }
        return monitor;
    }

    public boolean demonitor(ActorRef<?> watcher, MonitorRef monitor) {
        java.util.Objects.requireNonNull(watcher, "watcher");
        java.util.Objects.requireNonNull(monitor, "monitor");
        Map<UUID, ActorId> monitors = monitorsByTarget.get(monitor.target());
        if (monitors == null) return false;
        boolean removed = monitors.remove(monitor.value(), watcher.id());
        if (monitors.isEmpty()) monitorsByTarget.remove(monitor.target(), monitors);
        return removed;
    }

    private static void requireOwnedStateBytes(IsolatePolicy policy, long bytes) {
        if (bytes < 0L) throw new IllegalArgumentException("owned state bytes cannot be negative");
        if (bytes > policy.maxHeapBytes()) {
            throw new IllegalArgumentException("actor owned state exceeds actor heap policy");
        }
    }

    private void requireWithinCeiling(IsolatePolicy child) {
        if (!policyCeiling.capabilities().containsAll(child.capabilities())) {
            java.util.Set<IsolatePolicy.Capability> excess = java.util.EnumSet.copyOf(child.capabilities());
            excess.removeAll(policyCeiling.capabilities());
            throw new SecurityException("child actor policy exceeds parent capabilities: " + excess);
        }
        if (child.maxHeapBytes() > policyCeiling.maxHeapBytes()) {
            throw new SecurityException("child actor maxHeapBytes exceeds parent policy");
        }
        if (child.maxMailboxMessages() > policyCeiling.maxMailboxMessages()) {
            throw new SecurityException("child actor mailbox limit exceeds parent policy");
        }
        if (child.maxActors() > policyCeiling.maxActors()) {
            throw new SecurityException("child actor maxActors exceeds parent policy");
        }
        if (child.maxWallTime().compareTo(policyCeiling.maxWallTime()) > 0) {
            throw new SecurityException("child actor wall-time limit exceeds parent policy");
        }
        if (policyCeiling.adversarial() && !child.adversarial()) {
            throw new SecurityException("child actor cannot weaken an adversarial parent policy");
        }
    }

    @SuppressWarnings("unchecked")
    public <M> void send(ActorRef<M> ref, M message) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        java.util.Objects.requireNonNull(ref, "ref");
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null) {
            ActorFailure failure = failures.get(ref.id());
            if (failure != null) {
                throw new IllegalStateException("actor " + ref.id() + " failed: " + failure.type() + ": " + failure.message());
            }
            throw new IllegalStateException("unknown actor " + ref.id());
        }

        final Object deliveryValue;
        final long mailboxBytes;
        if (message instanceof Shared<?> shared) {
            // Shared regions are owned by the enclosing runtime/isolate, not
            // copied into the recipient actor heap. The actor pays only for
            // the immutable handle; shareReadonly() already admitted the
            // backing region against the isolate-level policy ceiling.
            deliveryValue = shared.value();
            mailboxBytes = SHARED_HANDLE_BYTES;
        } else {
            long maxMessageBytes = Math.max(
                    1024L * 1024L,
                    Math.min(cell.policy.maxHeapBytes() / 2L, 64L * 1024L * 1024L));
            long sourceEstimate = estimatedFrozenBytes(message);
            if (sourceEstimate > maxMessageBytes) {
                throw new IllegalArgumentException(
                        "message is too large for actor policy; use process.share_readonly for large immutable payloads");
            }
            deliveryValue = freeze(message);
            mailboxBytes = estimatedFrozenBytes(deliveryValue);
            if (mailboxBytes > maxMessageBytes) {
                throw new IllegalArgumentException(
                        "frozen message is too large for actor policy; use process.share_readonly for large immutable payloads");
            }
        }

        if (!cell.offer(new Envelope(deliveryValue, mailboxBytes))) {
            throw new IllegalStateException("actor mailbox memory/message limit exceeded for " + ref.id());
        }
    }

    /**
     * Creates an explicitly read-only shared value. The returned graph is a
     * frozen representation; no mutable source object itself is exposed.
     */
    @SuppressWarnings("unchecked")
    public <T> Shared<T> shareReadonly(T value) {
        long sourceEstimate = estimatedFrozenBytes(value);
        if (sourceEstimate > policyCeiling.maxHeapBytes()) {
            throw new IllegalArgumentException("shared value exceeds isolate heap policy");
        }
        T frozen = (T) freezeReadonly(value);
        long frozenBytes = estimatedFrozenBytes(frozen);
        if (frozenBytes > policyCeiling.maxHeapBytes()) {
            throw new IllegalArgumentException("frozen shared value exceeds isolate heap policy");
        }
        return new Shared<>(frozen, frozenBytes);
    }

    /**
     * Cooperative scheduler hook used by compiler-injected loop safepoints.
     */
    public void schedulerSafepoint() {
        if (closed.get()) throw new CancellationException("actor runtime is closing");
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("actor execution interrupted");
        Thread.yield();
    }

    public boolean stop(ActorRef<?> ref) {
        java.util.Objects.requireNonNull(ref, "ref");
        ActorCell<?> cell = actors.get(ref.id());
        return cell != null && cell.requestGracefulStop();
    }

    public boolean join(ActorRef<?> ref, Duration timeout) {
        java.util.Objects.requireNonNull(ref, "ref");
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        if (ref.id().equals(currentActor.get())) {
            throw new IllegalStateException("an actor cannot join itself");
        }
        ActorCell<?> cell = actors.get(ref.id());
        if (cell == null) return true;
        Thread thread = cell.thread;
        if (thread == null) return true;
        try {
            long millis = Math.max(1L, timeout.toMillis());
            thread.join(millis);
            return !thread.isAlive();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public ActorSnapshot snapshot(ActorRef<?> ref) {
        java.util.Objects.requireNonNull(ref, "ref");
        ActorCell<?> cell = actors.get(ref.id());
        if (cell != null) return cell.snapshot();
        ActorFailure failure = failures.get(ref.id());
        return new ActorSnapshot(
                ref.id(),
                failure == null ? ActorState.STOPPED : ActorState.FAILED,
                0,
                0L,
                0L,
                0L,
                0L,
                failure);
    }

    /** Called by process.gc() so actor-local pressure/telemetry can be tracked. */
    public void noteManualGcRequest() {
        ActorId id = currentActor.get();
        if (id == null) return;
        ActorCell<?> cell = actors.get(id);
        if (cell != null) cell.manualGcRequests.incrementAndGet();
    }

    /**
     * Converts supported values into a deeply immutable/sendable graph.
     * Unknown host objects are rejected instead of being passed by reference.
     * Cyclic mutable graphs are rejected; explicit shared regions must be
     * acyclic immutable values.
     */
    public static Object freeze(Object value) {
        return freeze(value, new IdentityHashMap<>(), 0, false);
    }

    private static Object freezeReadonly(Object value) {
        return freeze(value, new IdentityHashMap<>(), 0, true);
    }

    private static Object freeze(
            Object value,
            IdentityHashMap<Object, Boolean> path,
            int depth,
            boolean readOnlyShared) {
        if (depth > MAX_FREEZE_DEPTH) throw new IllegalArgumentException("message graph exceeds maximum freeze depth " + MAX_FREEZE_DEPTH);
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Enum<?> || value instanceof UUID || value instanceof ActorId || value instanceof MonitorRef) {
            return value;
        }
        if (value instanceof Shared<?> shared) return shared;
        if (value instanceof ActorRef<?> ref) return ref;

        if (path.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic mutable graphs cannot cross actor boundaries");
        }

        try {
            if (value instanceof List<?> list) {
                List<Object> frozen = new ArrayList<>();
                for (Object item : list) frozen.add(freeze(item, path, depth + 1, readOnlyShared));
                return List.copyOf(frozen);
            }
            if (value instanceof Set<?> set) {
                java.util.LinkedHashSet<Object> frozen = new java.util.LinkedHashSet<>();
                for (Object item : set) frozen.add(freeze(item, path, depth + 1, readOnlyShared));
                return Set.copyOf(frozen);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    frozen.put(freeze(entry.getKey(), path, depth + 1, readOnlyShared), freeze(entry.getValue(), path, depth + 1, readOnlyShared));
                }
                return Map.copyOf(frozen);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> frozen = new ArrayList<>();
                for (int i = 0; i < length; i++) frozen.add(freeze(Array.get(value, i), path, depth + 1, readOnlyShared));
                return List.copyOf(frozen);
            }
            if (value instanceof Sendable sendable) {
                Object replacement = sendable.freezeForSend(
                        nested -> freeze(nested, path, depth + 1, readOnlyShared),
                        readOnlyShared);
                if (replacement == value) {
                    throw new IllegalArgumentException(
                            "Sendable.freezeForSend() must return an actor-local copy or immutable transport value, not this");
                }
                return replacement;
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            path.remove(value);
        }
    }

    @FunctionalInterface
    public interface SendFreezer {
        Object freeze(Object value);
    }

    @FunctionalInterface
    public interface SendSizer {
        long estimatedBytes(Object value);
    }

    /**
     * Implemented only by trusted/generated Oreslang runtime values.
     * Implementations must create a transport-safe actor-local copy and must
     * recursively process nested values through the supplied freezer so cycle
     * detection and depth limits remain one graph traversal.
     */
    public interface Sendable {
        Object freezeForSend(SendFreezer freezer, boolean readOnlyShared);

        default long estimatedSendBytes(SendSizer sizer) {
            return 64L;
        }
    }

    public static long estimatedFrozenBytes(Object value) {
        return estimatedFrozenBytes(value, new IdentityHashMap<>(), 0);
    }

    private static long estimatedFrozenBytes(
            Object value,
            IdentityHashMap<Object, Boolean> path,
            int depth) {
        if (depth > MAX_FREEZE_DEPTH) return Long.MAX_VALUE;
        if (value == null) return 8L;
        if (value instanceof Shared<?>) return SHARED_HANDLE_BYTES;
        if (value instanceof String string) return safeAdd(24L, safeMultiply(2L, string.length()));
        if (value instanceof Boolean || value instanceof Byte) return 16L;
        if (value instanceof Character || value instanceof Short) return 16L;
        if (value instanceof Integer || value instanceof Float) return 16L;
        if (value instanceof Long || value instanceof Double || value instanceof UUID
                || value instanceof ActorId || value instanceof MonitorRef) return 32L;
        if (value instanceof BigInteger integer) return safeAdd(32L, integer.bitLength() / 8L + 1L);
        if (value instanceof BigDecimal decimal) {
            return safeAdd(48L, estimatedFrozenBytes(decimal.unscaledValue(), path, depth + 1));
        }
        if (value instanceof Enum<?>) return 24L;
        if (value instanceof ActorRef<?>) return SHARED_HANDLE_BYTES;

        boolean composite = value instanceof List<?>
                || value instanceof Set<?>
                || value instanceof Map<?, ?>
                || value.getClass().isArray()
                || value instanceof Sendable;
        if (composite && path.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic mutable graphs cannot cross actor boundaries");
        }

        try {
            if (value instanceof List<?> list) {
                long total = 24L;
                for (Object item : list) {
                    total = safeAdd(total, estimatedFrozenBytes(item, path, depth + 1));
                }
                return total;
            }
            if (value instanceof Set<?> set) {
                long total = 48L;
                for (Object item : set) {
                    total = safeAdd(total, estimatedFrozenBytes(item, path, depth + 1));
                }
                return total;
            }
            if (value instanceof Map<?, ?> map) {
                long total = 64L;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    total = safeAdd(total, estimatedFrozenBytes(entry.getKey(), path, depth + 1));
                    total = safeAdd(total, estimatedFrozenBytes(entry.getValue(), path, depth + 1));
                }
                return total;
            }
            if (value.getClass().isArray()) {
                long total = 24L;
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    total = safeAdd(total, estimatedFrozenBytes(Array.get(value, i), path, depth + 1));
                }
                return total;
            }
            if (value instanceof Sendable sendable) {
                return sendable.estimatedSendBytes(
                        nested -> estimatedFrozenBytes(nested, path, depth + 1));
            }
            return 64L;
        } finally {
            if (composite) path.remove(value);
        }
    }

    private static long safeAdd(long left, long right) {
        if (left == Long.MAX_VALUE || right == Long.MAX_VALUE || right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
    }

    private static long safeMultiply(long left, long right) {
        if (left == 0L || right == 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static void validateSingletonName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("singleton actor name cannot be blank");
        if (name.length() > 128) throw new IllegalArgumentException("singleton actor name is too long");
        if (!name.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("singleton actor name contains unsupported characters");
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyMonitors(ActorId target, String reason, ActorFailure failure) {
        Map<UUID, ActorId> monitors = monitorsByTarget.remove(target);
        if (monitors == null || monitors.isEmpty()) return;
        for (Map.Entry<UUID, ActorId> entry : monitors.entrySet()) {
            sendDown(entry.getValue(), new MonitorRef(entry.getKey(), target), reason, failure);
        }
    }

    @SuppressWarnings("unchecked")
    private void sendDown(ActorId watcherId, MonitorRef monitor, String reason, ActorFailure failure) {
        if (closed.get()) return;
        ActorCell<?> watcherCell = actors.get(watcherId);
        if (watcherCell == null) return;

        Map<String, Object> down = new LinkedHashMap<>();
        down.put("type", "DOWN");
        down.put("event", "DOWN");
        down.put("monitor_id", monitor.value().toString());
        down.put("actor_id", monitor.target().toString());
        down.put("reason", reason);
        if (failure != null) {
            down.put("error_type", failure.type());
            down.put("error_message", failure.message() == null ? "" : failure.message());
        }
        send((ActorRef<Object>) watcherCell.ref, Map.copyOf(down));
    }

    private void removeWatchesOwnedBy(ActorId watcher) {
        for (Map.Entry<ActorId, Map<UUID, ActorId>> entry : monitorsByTarget.entrySet()) {
            Map<UUID, ActorId> monitors = entry.getValue();
            monitors.entrySet().removeIf(item -> item.getValue().equals(watcher));
            if (monitors.isEmpty()) monitorsByTarget.remove(entry.getKey(), monitors);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        List<ActorCell<?>> cells = List.copyOf(actors.values());
        for (ActorCell<?> cell : cells) cell.forceStop();
        for (ActorCell<?> cell : cells) {
            Thread thread = cell.thread;
            if (thread == null) continue;
            try {
                thread.join(1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        actors.clear();
        activeActorSlots.set(0);
        singletonActors.clear();
        monitorsByTarget.clear();
        failures.clear();
        failureOrder.clear();
    }

    private record Envelope(Object value, long bytes) { }

    private final class ActorCell<M> {
        private static final Object STOP = new Object();

        private final ActorRef<M> ref;
        private final IsolatePolicy policy;
        private final Supplier<? extends Behavior<M>> behaviorFactory;
        private final BlockingQueue<Object> mailbox;
        private final AtomicLong mailboxBytes = new AtomicLong();
        private final AtomicLong activeMessageBytes = new AtomicLong();
        private final AtomicLong ownedStateBytes = new AtomicLong();
        private final AtomicLong manualGcRequests = new AtomicLong();
        private final long maxMailboxBytes;
        private volatile Thread thread;
        private volatile ActorState state = ActorState.STARTING;
        private volatile ActorFailure failureRecord;

        private ActorCell(
                ActorRef<M> ref,
                IsolatePolicy policy,
                long initialOwnedStateBytes,
                Supplier<? extends Behavior<M>> behaviorFactory) {
            this.ref = ref;
            this.policy = policy;
            this.behaviorFactory = behaviorFactory;
            this.ownedStateBytes.set(initialOwnedStateBytes);
            this.mailbox = new LinkedBlockingQueue<>(policy.maxMailboxMessages());
            this.maxMailboxBytes = Math.max(1024L * 1024L, Math.min(policy.maxHeapBytes() / 4L, 64L * 1024L * 1024L));
        }

        private void start() {
            thread = Thread.ofVirtual().name("ores-actor-" + ref.id().value()).start(this::run);
        }

        private boolean offer(Envelope envelope) {
            if (state == ActorState.STOPPING || state == ActorState.STOPPED || state == ActorState.FAILED) return false;
            long next;
            do {
                long current = mailboxBytes.get();
                if (envelope.bytes() > maxMailboxBytes - current) return false;
                next = current + envelope.bytes();
                long liveBytes = safeAdd(ownedStateBytes.get(), activeMessageBytes.get());
                if (liveBytes > policy.maxHeapBytes() - next) return false;
                if (mailboxBytes.compareAndSet(current, next)) break;
            } while (true);

            if (mailbox.offer(envelope)) return true;
            mailboxBytes.addAndGet(-envelope.bytes());
            return false;
        }

        @SuppressWarnings("unchecked")
        private void run() {
            currentActor.set(ref.id());
            state = ActorState.RUNNING;
            final Behavior<M> behavior;
            try {
                behavior = java.util.Objects.requireNonNull(behaviorFactory.get(), "behaviorFactory returned null");
            } catch (Throwable failure) {
                recordFailure(failure);
                currentActor.remove();
                activeMessageBytes.set(0L);
                ownedStateBytes.set(0L);
                removeSingletonMapping();
                removeWatchesOwnedBy(ref.id());
                notifyMonitors(ref.id(), "failed", failureRecord);
                // Keep the cell discoverable through monitor delivery so join()
                // cannot race ahead and tear down the watcher.
                removeActorCell(ref.id(), this);
                return;
            }

            final ActorContext<M> context = new ActorContext<>() {
                @Override public ActorRef<M> self() { return ref; }
                @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                @Override public IsolatePolicy policy() { return policy; }
                @Override public long ownedStateBytes() { return ownedStateBytes.get(); }
                @Override public void replaceOwnedStateBytes(long estimatedBytes) {
                    requireOwnedStateBytes(policy, estimatedBytes);
                    long transientBytes = safeAdd(mailboxBytes.get(), activeMessageBytes.get());
                    if (estimatedBytes > policy.maxHeapBytes() - transientBytes) {
                        throw new IllegalStateException("actor heap limit exceeded by persistent state");
                    }
                    ownedStateBytes.set(estimatedBytes);
                }
            };

            try {
                while (true) {
                    Object queued = mailbox.take();
                    if (queued == STOP) {
                        state = ActorState.STOPPED;
                        return;
                    }
                    Envelope envelope = (Envelope) queued;
                    mailboxBytes.addAndGet(-envelope.bytes());
                    activeMessageBytes.set(envelope.bytes());
                    try {
                        behavior.onMessage((M) envelope.value(), context);
                    } finally {
                        activeMessageBytes.set(0L);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (state != ActorState.FAILED) state = ActorState.STOPPED;
            } catch (Throwable failure) {
                recordFailure(failure);
            } finally {
                currentActor.remove();
                activeMessageBytes.set(0L);
                ownedStateBytes.set(0L);
                removeSingletonMapping();
                removeWatchesOwnedBy(ref.id());
                ActorFailure actorFailure = failureRecord;
                notifyMonitors(ref.id(), actorFailure == null ? "normal" : "failed", actorFailure);
                // Publish termination only after all lifecycle notifications
                // have been queued. This gives join() a real happens-before
                // boundary for supervision instead of a fail-open lookup race.
                removeActorCell(ref.id(), this);
            }
        }

        private void recordFailure(Throwable failure) {
            state = ActorState.FAILED;
            ActorFailure record = new ActorFailure(
                    failure.getClass().getName(),
                    failure.getMessage() == null ? "" : failure.getMessage());
            failureRecord = record;
            failures.put(ref.id(), record);
            failureOrder.add(ref.id());
            while (failures.size() > MAX_FAILURE_TOMBSTONES) {
                ActorId oldest = failureOrder.poll();
                if (oldest == null) break;
                failures.remove(oldest);
            }
        }

        private void removeSingletonMapping() {
            singletonActors.entrySet().removeIf(entry -> entry.getValue().actorId().equals(ref.id()));
        }

        private boolean requestGracefulStop() {
            if (state == ActorState.STOPPED || state == ActorState.FAILED) return true;
            state = ActorState.STOPPING;
            if (mailbox.offer(STOP)) return true;
            state = ActorState.RUNNING;
            return false;
        }

        private void forceStop() {
            state = ActorState.STOPPING;
            mailbox.clear();
            mailboxBytes.set(0L);
            ownedStateBytes.set(0L);
            mailbox.offer(STOP);
            Thread t = thread;
            if (t != null) t.interrupt();
        }

        private ActorSnapshot snapshot() {
            return new ActorSnapshot(
                    ref.id(),
                    state,
                    mailbox.size(),
                    mailboxBytes.get(),
                    activeMessageBytes.get(),
                    ownedStateBytes.get(),
                    manualGcRequests.get(),
                    failureRecord);
        }
    }
}
