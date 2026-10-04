package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One singleton instance per semantic memory slot.
 *
 * A slot is the main/shared memory domain or one isolated actor memory domain.
 * The registry never exposes the mutable state object. Callers receive an opaque
 * slot-bound Handle and execute operations under the cell lock. Arguments and
 * results cross the boundary as frozen data, preventing mutable alias escape.
 */
public final class MemorySlotSingletonRegistry implements AutoCloseable {
    private static final int MAX_SINGLETONS_PER_SLOT = 4_096;
    private static final int MAX_KEY_CHARS = 2_048;

    private final ActorRuntime runtime;
    private final ConcurrentHashMap<Object, ConcurrentHashMap<String, Cell>> slots =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadLocal<Cell> currentCell = new ThreadLocal<>();
    private final Object waitGraphLock = new Object();
    private final Map<Cell, Map<Cell, Integer>> waitGraph = new IdentityHashMap<>();

    MemorySlotSingletonRegistry(ActorRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @FunctionalInterface
    public interface Operation<S> {
        Object apply(S state, List<Object> arguments) throws Exception;
    }

    public <S> Handle<S> getOrCreate(
            String key,
            Supplier<? extends S> stateFactory) {
        Objects.requireNonNull(stateFactory, "stateFactory");
        String normalized = normalizeKey(key);
        Object slot = runtime.currentMemorySlotKey();

        while (true) {
            if (closed.get()) {
                throw new IllegalStateException("memory-slot singleton registry is closed");
            }

            ConcurrentHashMap<String, Cell> table =
                    slots.computeIfAbsent(slot, ignored -> new ConcurrentHashMap<>());
            synchronized (table) {
                // releaseSlot may have detached this table while we were
                // waiting for its monitor. Never create a cell in an orphaned
                // table that is no longer reachable from the registry.
                if (slots.get(slot) != table) continue;
                if (closed.get()) {
                    throw new IllegalStateException("memory-slot singleton registry is closed");
                }

                Cell existing = table.get(normalized);
                if (existing != null) return new Handle<>(this, existing);
                if (table.size() >= MAX_SINGLETONS_PER_SLOT) {
                    throw new IllegalStateException(
                            "memory-slot singleton limit exceeded: "
                                    + MAX_SINGLETONS_PER_SLOT);
                }

                Object state = Objects.requireNonNull(
                        stateFactory.get(),
                        "singleton state factory returned null");
                Cell created = new Cell(slot, normalized, state);
                table.put(normalized, created);
                return new Handle<>(this, created);
            }
        }
    }

    public int singletonCountForCurrentSlot() {
        Map<String, Cell> table = slots.get(runtime.currentMemorySlotKey());
        return table == null ? 0 : table.size();
    }

    /**
     * Called by ActorRuntime when one isolated/private memory slot is retired.
     * Shared/main slot state lives until runtime close.
     */
    void releaseSlot(Object slot) {
        while (true) {
            ConcurrentHashMap<String, Cell> table = slots.get(slot);
            if (table == null) return;
            synchronized (table) {
                if (!slots.remove(slot, table)) continue;
                for (Cell cell : table.values()) cell.close();
                table.clear();
                return;
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Object slot : List.copyOf(slots.keySet())) releaseSlot(slot);
        synchronized (waitGraphLock) {
            waitGraph.clear();
        }
    }

    private WaitEdge registerWaitEdge(Cell caller, Cell target) {
        if (caller == target) {
            throw new IllegalStateException(
                    "reentrant singleton call is forbidden: " + target.diagnosticId());
        }
        synchronized (waitGraphLock) {
            if (pathExists(target, caller)) {
                throw new IllegalStateException(
                        "singleton wait cycle rejected before deadlock: "
                                + caller.diagnosticId() + " -> " + target.diagnosticId());
            }
            waitGraph.computeIfAbsent(caller, ignored -> new IdentityHashMap<>())
                    .merge(target, 1, Integer::sum);
        }
        return new WaitEdge(caller, target);
    }

    private boolean pathExists(Cell start, Cell wanted) {
        ArrayDeque<Cell> pending = new ArrayDeque<>();
        Set<Cell> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(start);
        while (!pending.isEmpty()) {
            Cell current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current == wanted) return true;
            Map<Cell, Integer> outgoing = waitGraph.get(current);
            if (outgoing != null) pending.addAll(outgoing.keySet());
        }
        return false;
    }

    private void removeWaitEdge(Cell caller, Cell target) {
        synchronized (waitGraphLock) {
            Map<Cell, Integer> outgoing = waitGraph.get(caller);
            if (outgoing == null) return;
            Integer count = outgoing.get(target);
            if (count == null) return;
            if (count <= 1) outgoing.remove(target);
            else outgoing.put(target, count - 1);
            if (outgoing.isEmpty()) waitGraph.remove(caller);
        }
    }

    private final class WaitEdge implements AutoCloseable {
        private final Cell caller;
        private final Cell target;
        private boolean closed;

        private WaitEdge(Cell caller, Cell target) {
            this.caller = caller;
            this.target = target;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            removeWaitEdge(caller, target);
        }
    }

    private static String normalizeKey(String key) {
        String normalized = Objects.requireNonNull(key, "key").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("singleton key cannot be blank");
        if (normalized.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException(
                    "singleton key exceeds " + MAX_KEY_CHARS + " characters");
        }
        return normalized;
    }

    public static final class Handle<S> implements SandboxForbiddenCapability {
        private final MemorySlotSingletonRegistry registry;
        private final Cell cell;

        private Handle(MemorySlotSingletonRegistry registry, Cell cell) {
            this.registry = registry;
            this.cell = cell;
        }

        public UUID instanceId() {
            return cell.instanceId;
        }

        public String singletonKey() {
            return cell.key;
        }

        public Object call(List<?> arguments, Operation<S> operation) {
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(operation, "operation");
            if (registry.closed.get()) {
                throw new IllegalStateException("memory-slot singleton registry is closed");
            }
            if (registry.runtime.currentMemorySlotKey() != cell.slot) {
                throw new SecurityException(
                        "singleton handle belongs to a different memory slot");
            }

            @SuppressWarnings("unchecked")
            List<Object> frozenArguments =
                    (List<Object>) ActorRuntime.freeze(new ArrayList<>(arguments));
            @SuppressWarnings("unchecked")
            List<Object> ownedArguments =
                    (List<Object>) ActorRuntime.materializeFrozen(frozenArguments);

            Cell caller = registry.currentCell.get();
            WaitEdge edge = caller == null ? null : registry.registerWaitEdge(caller, cell);
            cell.lock.lock();
            Cell previous = registry.currentCell.get();
            registry.currentCell.set(cell);
            try {
                if (cell.closed) {
                    throw new IllegalStateException(
                            "memory-slot singleton is closed: " + cell.key);
                }
                @SuppressWarnings("unchecked")
                S state = (S) cell.state;
                return ActorRuntime.freeze(operation.apply(state, ownedArguments));
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new RuntimeException(
                        "memory-slot singleton operation failed: " + cell.key,
                        failure);
            } finally {
                if (previous == null) registry.currentCell.remove();
                else registry.currentCell.set(previous);
                cell.lock.unlock();
                if (edge != null) edge.close();
            }
        }
    }

    private static final class Cell {
        private final Object slot;
        private final String key;
        private final UUID instanceId = UUID.randomUUID();
        private final ReentrantLock lock = new ReentrantLock(true);
        private Object state;
        private boolean closed;

        private Cell(Object slot, String key, Object state) {
            this.slot = Objects.requireNonNull(slot);
            this.key = Objects.requireNonNull(key);
            this.state = Objects.requireNonNull(state);
        }

        private String diagnosticId() {
            return Integer.toUnsignedString(key.hashCode(), 16)
                    + "-" + instanceId.toString().substring(0, 8);
        }

        private void close() {
            Object toClose;
            lock.lock();
            try {
                if (closed) return;
                closed = true;
                toClose = state;
                state = null;
            } finally {
                lock.unlock();
            }
            if (toClose instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception failure) {
                    throw new IllegalStateException(
                            "failed to close memory-slot singleton " + key,
                            failure);
                }
            }
        }
    }
}
