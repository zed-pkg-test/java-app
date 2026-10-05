package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ores-owned bounded channel with atomic multi-channel selection.
 *
 * <p>The reference runtime deliberately uses one fair coordination lock for
 * channel admission and selection. That makes the semantic contract simple and
 * strong: a select probes and commits exactly one case atomically, losing cases
 * never consume or reserve data, rendezvous channels (capacity 0) work between
 * direct operations and selects, and blocking selectors sleep on a condition
 * instead of polling. Native/AOT backends are free to replace this with a more
 * scalable implementation while preserving the same contract.</p>
 *
 * <p>Actor dispatcher carriers are never parked. A blocking read/write/select
 * may complete immediately on a carrier, but if it would wait the runtime fails
 * closed until actor continuation lowering suspends the mailbox turn.</p>
 */
public final class OresChannel<T> {
    private static final ReentrantLock COORDINATOR = new ReentrantLock(true);
    private static final Condition CHANGED = COORDINATOR.newCondition();
    private static final WeakHashMap<Object, Integer> FAIRNESS_CURSOR = new WeakHashMap<>();
    private static long nextTicket;

    public enum Operation {
        READ,
        WRITE
    }

    public sealed interface SelectEntry permits ReadEntry, WriteEntry {
        OresChannel<?> channel();
        Object key();
        Operation operation();
        SelectEntry withKey(Object key);
    }

    public record ReadEntry<T>(OresChannel<T> channel, Object key) implements SelectEntry {
        public ReadEntry {
            Objects.requireNonNull(channel, "channel");
        }

        @Override
        public Operation operation() {
            return Operation.READ;
        }

        @Override
        public ReadEntry<T> withKey(Object nextKey) {
            return new ReadEntry<>(channel, nextKey);
        }
    }

    public record WriteEntry<T>(OresChannel<T> channel, T value, Object key) implements SelectEntry {
        public WriteEntry {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(value, "channel values cannot be null");
        }

        @Override
        public Operation operation() {
            return Operation.WRITE;
        }

        @Override
        public WriteEntry<T> withKey(Object nextKey) {
            return new WriteEntry<>(channel, value, nextKey);
        }
    }

    /**
     * Result of one committed select operation.
     *
     * <p>For READ, value is the received item. For WRITE, value is the value
     * that was sent. index is the original list-order case index; key is the
     * dynamic collection key (or the case index for static/list selections).</p>
     */
    public record SelectionResult(
            int index,
            Object key,
            Operation operation,
            Object value,
            OresChannel<?> channel) {
        public SelectionResult {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(channel, "channel");
        }
    }

    private static final class Selection {
        private final List<SelectEntry> entries;
        private final Object executionDomain;
        private final ArrayList<Registration> registrations = new ArrayList<>();
        private SelectionResult result;

        private Selection(List<SelectEntry> entries) {
            this.entries = entries;
            this.executionDomain = ActorRuntime.currentExecutionDomain();
        }
    }

    private static final class Registration {
        private final Selection selection;
        private final int index;
        private final SelectEntry entry;
        private final long ticket;

        private Registration(Selection selection, int index, SelectEntry entry) {
            this.selection = selection;
            this.index = index;
            this.entry = entry;
            this.ticket = nextTicket++;
        }
    }

    private final int capacity;
    private final ArrayDeque<T> buffer;
    private final ArrayList<Registration> registrations = new ArrayList<>();

    public OresChannel(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("channel capacity must be >= 0");
        }
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(Math.max(1, capacity));
    }

    public int capacity() {
        return capacity;
    }

    public int bufferedSize() {
        COORDINATOR.lock();
        try {
            return buffer.size();
        } finally {
            COORDINATOR.unlock();
        }
    }

    public T read() {
        SelectionResult selected = select(List.of(readCase(this)));
        @SuppressWarnings("unchecked")
        T value = (T) selected.value();
        return value;
    }

    public Optional<T> tryRead() {
        Optional<SelectionResult> selected = trySelect(List.of(readCase(this)));
        if (selected.isEmpty()) return Optional.empty();
        @SuppressWarnings("unchecked")
        T value = (T) selected.get().value();
        return Optional.of(value);
    }

    public void write(T value) {
        select(List.of(writeCase(this, value)));
    }

    public boolean tryWrite(T value) {
        return trySelect(List.of(writeCase(this, value))).isPresent();
    }

    public static <T> ReadEntry<T> readCase(OresChannel<T> channel) {
        return new ReadEntry<>(channel, null);
    }

    public static <T> WriteEntry<T> writeCase(OresChannel<T> channel, T value) {
        return new WriteEntry<>(channel, value, null);
    }

    public static SelectionResult select(List<? extends SelectEntry> rawEntries) {
        return selectInternal(copyEntries(rawEntries), true)
                .orElseThrow(() -> new IllegalStateException("blocking select unexpectedly produced no result"));
    }

    public static Optional<SelectionResult> trySelect(List<? extends SelectEntry> rawEntries) {
        return selectInternal(copyEntries(rawEntries), false);
    }

    public static SelectionResult selectDynamic(Object entries) {
        return select(coerceEntries(entries));
    }

    public static Optional<SelectionResult> trySelectDynamic(Object entries) {
        return trySelect(coerceEntries(entries));
    }

    /**
     * Dynamic lists may contain SelectEntry values or bare OresChannel values;
     * a bare channel means a READ case. Dynamic maps preserve each map key in
     * SelectionResult.key and accept the same value forms.
     */
    public static List<SelectEntry> coerceEntries(Object source) {
        Objects.requireNonNull(source, "select entries");
        ArrayList<SelectEntry> result = new ArrayList<>();

        if (source instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                result.add(coerceEntry(list.get(i), (long) i));
            }
            return List.copyOf(result);
        }

        if (source instanceof Object[] array) {
            for (int i = 0; i < array.length; i++) {
                result.add(coerceEntry(array[i], (long) i));
            }
            return List.copyOf(result);
        }

        if (source instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.add(coerceEntry(entry.getValue(), entry.getKey()));
            }
            return List.copyOf(result);
        }

        throw new IllegalArgumentException(
                "dynamic select expects a List, array, or Map of channels/select entries");
    }

    private static SelectEntry coerceEntry(Object raw, Object key) {
        if (raw instanceof SelectEntry entry) {
            return entry.withKey(key);
        }
        if (raw instanceof OresChannel<?> channel) {
            return new ReadEntry<>(channel, key);
        }
        throw new IllegalArgumentException(
                "dynamic select entry must be a Channel or select.readch/select.writech case");
    }

    private static List<SelectEntry> copyEntries(List<? extends SelectEntry> rawEntries) {
        Objects.requireNonNull(rawEntries, "select entries");
        if (rawEntries.isEmpty()) {
            throw new IllegalArgumentException("select requires at least one case");
        }
        ArrayList<SelectEntry> entries = new ArrayList<>(rawEntries.size());
        for (int i = 0; i < rawEntries.size(); i++) {
            SelectEntry entry = Objects.requireNonNull(rawEntries.get(i), "select case " + i);
            Object key = entry.key() == null ? (long) i : entry.key();
            entries.add(entry.withKey(key));
        }
        return List.copyOf(entries);
    }

    private static Optional<SelectionResult> selectInternal(List<SelectEntry> entries, boolean blocking) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("select requires at least one case");
        }

        COORDINATOR.lock();
        Selection selection = new Selection(entries);
        try {
            register(selection);
            pumpAffected(selection);

            if (selection.result != null) {
                return Optional.of(selection.result);
            }

            if (!blocking) {
                unregister(selection);
                return Optional.empty();
            }

            if (ActorRuntime.isActorCarrierThread()) {
                unregister(selection);
                throw new IllegalStateException(
                        "blocking channel select would park an actor dispatcher carrier; "
                                + "use try select/default until actor select continuation lowering is available");
            }

            while (selection.result == null) {
                try {
                    CHANGED.await();
                } catch (InterruptedException interrupted) {
                    unregister(selection);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("channel select interrupted", interrupted);
                }
                pumpAffected(selection);
            }
            return Optional.of(selection.result);
        } finally {
            COORDINATOR.unlock();
        }
    }

    private static void register(Selection selection) {
        int size = selection.entries.size();
        int cursor = Math.floorMod(FAIRNESS_CURSOR.getOrDefault(selection.executionDomain, 0), size);
        for (int offset = 0; offset < size; offset++) {
            int index = (cursor + offset) % size;
            SelectEntry entry = selection.entries.get(index);
            Registration registration = new Registration(selection, index, entry);
            selection.registrations.add(registration);
            entry.channel().registrations.add(registration);
        }
    }

    private static void unregister(Selection selection) {
        for (Registration registration : selection.registrations) {
            registration.entry.channel().registrations.remove(registration);
        }
        selection.registrations.clear();
    }

    private static void pumpAffected(Selection selection) {
        Set<OresChannel<?>> channels = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (SelectEntry entry : selection.entries) channels.add(entry.channel());
        boolean progressed;
        do {
            progressed = false;
            for (OresChannel<?> channel : channels) {
                progressed |= channel.pumpOneOrMore();
            }
        } while (progressed && selection.result == null);
    }

    private boolean pumpOneOrMore() {
        boolean any = false;
        boolean progressed;
        do {
            progressed = false;

            Registration reader = first(Operation.READ, null);
            if (reader != null && !buffer.isEmpty()) {
                Object value = buffer.removeFirst();
                completeSingle(reader, value);
                progressed = true;
                any = true;
                continue;
            }

            Registration rendezvousReader = first(Operation.READ, null);
            Registration rendezvousWriter = firstCompatibleWriter(rendezvousReader);
            if (rendezvousReader != null && rendezvousWriter != null) {
                completePair(rendezvousReader, rendezvousWriter);
                progressed = true;
                any = true;
                continue;
            }

            if (capacity > buffer.size()) {
                Registration writer = first(Operation.WRITE, null);
                if (writer != null) {
                    Object value = ((WriteEntry<?>) writer.entry).value();
                    completeSingle(writer, value);
                    @SuppressWarnings("unchecked")
                    T typed = (T) value;
                    buffer.addLast(typed);
                    progressed = true;
                    any = true;
                }
            }
        } while (progressed);

        return any;
    }

    private Registration first(Operation operation, Selection excludeSelection) {
        Registration best = null;
        for (Registration registration : registrations) {
            if (registration.selection.result != null) continue;
            if (registration.entry.operation() != operation) continue;
            if (registration.selection == excludeSelection) continue;
            if (best == null || registration.ticket < best.ticket) best = registration;
        }
        return best;
    }

    private Registration firstCompatibleWriter(Registration reader) {
        if (reader == null) return null;
        return first(Operation.WRITE, reader.selection);
    }

    private static void completeSingle(Registration registration, Object value) {
        Selection selection = registration.selection;
        if (selection.result != null) return;
        selection.result = new SelectionResult(
                registration.index,
                registration.entry.key(),
                registration.entry.operation(),
                value,
                registration.entry.channel());
        rotateFairness(selection, registration.index);
        unregister(selection);
        CHANGED.signalAll();
    }

    private static void completePair(Registration reader, Registration writer) {
        Selection readSelection = reader.selection;
        Selection writeSelection = writer.selection;
        if (readSelection == writeSelection
                || readSelection.result != null
                || writeSelection.result != null) {
            return;
        }

        Object value = ((WriteEntry<?>) writer.entry).value();
        readSelection.result = new SelectionResult(
                reader.index,
                reader.entry.key(),
                Operation.READ,
                value,
                reader.entry.channel());
        writeSelection.result = new SelectionResult(
                writer.index,
                writer.entry.key(),
                Operation.WRITE,
                value,
                writer.entry.channel());

        rotateFairness(readSelection, reader.index);
        rotateFairness(writeSelection, writer.index);
        unregister(readSelection);
        unregister(writeSelection);
        CHANGED.signalAll();
    }

    private static void rotateFairness(Selection selection, int selectedIndex) {
        if (!selection.entries.isEmpty()) {
            FAIRNESS_CURSOR.put(
                    selection.executionDomain,
                    (selectedIndex + 1) % selection.entries.size());
        }
    }
}
