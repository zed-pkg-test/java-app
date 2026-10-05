package dev.oreslang.runtime.ecs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Chunked archetype ECS storage for OresVM.
 *
 * <p>Each archetype owns chunks with one entity-id column plus one Object[]
 * column per component field. This is field-level Struct-of-Arrays storage:
 * component state is never embedded in an entity object. Structural mutation
 * changes archetypes and therefore invalidates outstanding query views; a
 * {@link CommandBuffer} defers those mutations to a synchronization point.</p>
 *
 * <p>This class is deliberately independent of Truffle objects. It is a
 * runtime substrate that an interpreter, AOT lowering, SIMD pass, or GPU
 * mapper can target without changing ECS semantics.</p>
 */
public final class EcsWorld {
    public static final int DEFAULT_CHUNK_CAPACITY = 256;

    private final int chunkCapacity;
    private final Map<String, ComponentType> componentTypes = new LinkedHashMap<>();
    private final Map<ArchetypeKey, Archetype> archetypes = new LinkedHashMap<>();
    private final ArrayList<Slot> slots = new ArrayList<>();
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private long structureVersion;

    public EcsWorld() {
        this(DEFAULT_CHUNK_CAPACITY);
    }

    public EcsWorld(int chunkCapacity) {
        if (chunkCapacity <= 0) throw new IllegalArgumentException("chunk capacity must be positive");
        this.chunkCapacity = chunkCapacity;
    }

    /**
     * Register a component schema. Re-registering an identical schema is
     * idempotent; registering the same name with a different field layout is
     * rejected so archetype identity remains stable.
     */
    public ComponentType register(ComponentType type) {
        Objects.requireNonNull(type, "type");
        ComponentType previous = componentTypes.putIfAbsent(type.name(), type);
        if (previous != null && !previous.equals(type)) {
            throw new IllegalArgumentException(
                    "component '" + type.name() + "' is already registered with fields " + previous.fields());
        }
        return previous == null ? type : previous;
    }

    public Entity spawn(Map<ComponentType, ? extends Map<String, ?>> components) {
        Map<ComponentType, Map<String, Object>> normalized = normalizeComponents(components);
        int index;
        Slot slot;
        if (freeSlots.isEmpty()) {
            index = slots.size();
            slot = new Slot();
            slots.add(slot);
        } else {
            index = freeSlots.removeFirst();
            slot = slots.get(index);
            if (slot.alive || slot.location != null) {
                throw new IllegalStateException("corrupt ECS free-list slot " + index);
            }
        }

        Entity entity = Entity.of(index, slot.generation);
        Archetype archetype = archetypeFor(normalized.keySet());
        Location location = archetype.append(entity, normalized);
        slot.alive = true;
        slot.location = location;
        structureVersion++;
        return entity;
    }

    public boolean isAlive(Entity entity) {
        if (entity == null || entity.index() < 0 || entity.index() >= slots.size()) return false;
        Slot slot = slots.get(entity.index());
        return slot.alive && slot.generation == entity.generation();
    }

    public Set<ComponentType> components(Entity entity) {
        Slot slot = requireAlive(entity);
        return slot.location.archetype.key.components();
    }

    public Object read(Entity entity, ComponentType component, String field) {
        Slot slot = requireAlive(entity);
        ComponentType canonical = canonical(component);
        return slot.location.chunk.read(slot.location.row, canonical, field);
    }

    public List<ChunkView> query(QueryAccess access) {
        Objects.requireNonNull(access, "access");
        canonicalizeAccess(access);
        long version = structureVersion;
        List<ChunkView> result = new ArrayList<>();
        for (Archetype archetype : archetypes.values()) {
            if (!access.matches(archetype.key.components())) continue;
            for (Chunk chunk : archetype.chunks) {
                if (chunk.size > 0) result.add(new ChunkView(this, archetype, chunk, access, version));
            }
        }
        return List.copyOf(result);
    }

    public CommandBuffer commands() {
        return new CommandBuffer(this);
    }

    public int entityCount() {
        int count = 0;
        for (Slot slot : slots) if (slot.alive) count++;
        return count;
    }

    public int archetypeCount() {
        return archetypes.size();
    }

    public long structureVersion() {
        return structureVersion;
    }

    private void canonicalizeAccess(QueryAccess access) {
        for (ComponentType type : access.reads()) canonical(type);
        for (ComponentType type : access.writes()) canonical(type);
        for (ComponentType type : access.optional()) canonical(type);
        for (ComponentType type : access.without()) canonical(type);
    }

    private ComponentType canonical(ComponentType type) {
        Objects.requireNonNull(type, "component");
        ComponentType known = componentTypes.get(type.name());
        if (known == null) throw new IllegalArgumentException("component '" + type.name() + "' is not registered");
        if (!known.equals(type)) {
            throw new IllegalArgumentException(
                    "component schema mismatch for '" + type.name() + "': expected " + known.fields());
        }
        return known;
    }

    private Map<ComponentType, Map<String, Object>> normalizeComponents(
            Map<ComponentType, ? extends Map<String, ?>> components) {
        Objects.requireNonNull(components, "components");
        LinkedHashMap<ComponentType, Map<String, Object>> normalized = new LinkedHashMap<>();
        for (Map.Entry<ComponentType, ? extends Map<String, ?>> entry : components.entrySet()) {
            ComponentType type = canonical(entry.getKey());
            if (normalized.containsKey(type)) {
                throw new IllegalArgumentException("duplicate component '" + type.name() + "'");
            }
            normalized.put(type, normalizeValues(type, entry.getValue()));
        }
        return Collections.unmodifiableMap(normalized);
    }

    private Map<String, Object> normalizeValues(ComponentType type, Map<String, ?> values) {
        Objects.requireNonNull(values, "values for " + type.name());
        if (!values.keySet().equals(new LinkedHashSet<>(type.fields()))) {
            throw new IllegalArgumentException(
                    "component '" + type.name() + "' requires exactly fields " + type.fields()
                            + ", got " + values.keySet());
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (String field : type.fields()) {
            Object value = values.get(field);
            if (value == null) {
                throw new IllegalArgumentException(
                        "component field '" + type.name() + "." + field + "' cannot be null");
            }
            result.put(field, value);
        }
        return Collections.unmodifiableMap(result);
    }

    private Archetype archetypeFor(Set<ComponentType> types) {
        ArchetypeKey key = new ArchetypeKey(types);
        return archetypes.computeIfAbsent(key, ignored -> new Archetype(key, chunkCapacity));
    }

    private Slot requireAlive(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        int index = entity.index();
        if (index < 0 || index >= slots.size()) {
            throw new IllegalArgumentException("unknown entity " + entity.raw());
        }
        Slot slot = slots.get(index);
        if (!slot.alive || slot.generation != entity.generation()) {
            throw new IllegalStateException("stale or destroyed entity " + entity.raw());
        }
        return slot;
    }

    private void destroyNow(Entity entity) {
        Slot slot = requireAlive(entity);
        removeFromCurrentArchetype(slot);
        slot.alive = false;
        slot.location = null;
        slot.generation = nextGeneration(slot.generation);
        freeSlots.addLast(entity.index());
        structureVersion++;
    }

    private void addNow(Entity entity, ComponentType type, Map<String, ?> values) {
        Slot slot = requireAlive(entity);
        ComponentType canonical = canonical(type);
        if (slot.location.archetype.key.components().contains(canonical)) {
            throw new IllegalArgumentException(
                    "entity " + entity.raw() + " already has component '" + canonical.name() + "'");
        }

        Map<ComponentType, Map<String, Object>> state = slot.location.chunk.snapshot(slot.location.row);
        state.put(canonical, normalizeValues(canonical, values));
        migrate(entity, slot, state);
    }

    private void removeNow(Entity entity, ComponentType type) {
        Slot slot = requireAlive(entity);
        ComponentType canonical = canonical(type);
        if (!slot.location.archetype.key.components().contains(canonical)) {
            throw new IllegalArgumentException(
                    "entity " + entity.raw() + " does not have component '" + canonical.name() + "'");
        }

        Map<ComponentType, Map<String, Object>> state = slot.location.chunk.snapshot(slot.location.row);
        state.remove(canonical);
        migrate(entity, slot, state);
    }

    private void migrate(Entity entity, Slot slot, Map<ComponentType, Map<String, Object>> state) {
        removeFromCurrentArchetype(slot);
        Archetype target = archetypeFor(state.keySet());
        slot.location = target.append(entity, state);
        structureVersion++;
    }

    private void removeFromCurrentArchetype(Slot slot) {
        Location old = slot.location;
        Entity moved = old.chunk.removeSwap(old.row);
        if (moved != null) {
            Slot movedSlot = requireAlive(moved);
            movedSlot.location = new Location(old.archetype, old.chunk, old.row);
        }
    }

    private static int nextGeneration(int generation) {
        int next = generation + 1;
        return next == 0 ? 1 : next;
    }

    private static final class Slot {
        private int generation = 1;
        private boolean alive;
        private Location location;
    }

    private record Location(Archetype archetype, Chunk chunk, int row) { }

    private static final class ArchetypeKey {
        private final List<ComponentType> ordered;
        private final Set<ComponentType> components;

        private ArchetypeKey(Set<ComponentType> components) {
            ArrayList<ComponentType> sorted = new ArrayList<>(components);
            sorted.sort(Comparator.naturalOrder());
            this.ordered = List.copyOf(sorted);
            this.components = Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
        }

        private Set<ComponentType> components() {
            return components;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ArchetypeKey key && ordered.equals(key.ordered);
        }

        @Override
        public int hashCode() {
            return ordered.hashCode();
        }
    }

    private static final class Archetype {
        private final ArchetypeKey key;
        private final int chunkCapacity;
        private final List<Chunk> chunks = new ArrayList<>();

        private Archetype(ArchetypeKey key, int chunkCapacity) {
            this.key = key;
            this.chunkCapacity = chunkCapacity;
        }

        private Location append(Entity entity, Map<ComponentType, Map<String, Object>> state) {
            Chunk chunk;
            if (chunks.isEmpty() || chunks.getLast().isFull()) {
                chunk = new Chunk(key, chunkCapacity);
                chunks.add(chunk);
            } else {
                chunk = chunks.getLast();
            }
            int row = chunk.append(entity, state);
            return new Location(this, chunk, row);
        }
    }

    private static final class Chunk {
        private final ArchetypeKey key;
        private final Entity[] entities;
        private final Map<ComponentType, Map<String, Object[]>> columns = new LinkedHashMap<>();
        private int size;

        private Chunk(ArchetypeKey key, int capacity) {
            this.key = key;
            this.entities = new Entity[capacity];
            for (ComponentType type : key.ordered) {
                LinkedHashMap<String, Object[]> fields = new LinkedHashMap<>();
                for (String field : type.fields()) fields.put(field, new Object[capacity]);
                columns.put(type, fields);
            }
        }

        private boolean isFull() {
            return size == entities.length;
        }

        private int append(Entity entity, Map<ComponentType, Map<String, Object>> state) {
            if (isFull()) throw new IllegalStateException("cannot append to a full ECS chunk");
            if (!state.keySet().equals(key.components())) {
                throw new IllegalArgumentException("component state does not match target archetype");
            }
            int row = size++;
            entities[row] = entity;
            for (ComponentType type : key.ordered) {
                Map<String, Object> values = state.get(type);
                for (String field : type.fields()) {
                    columns.get(type).get(field)[row] = values.get(field);
                }
            }
            return row;
        }

        private Object read(int row, ComponentType type, String field) {
            checkRow(row);
            Object[] column = column(type, field);
            return column[row];
        }

        private void write(int row, ComponentType type, String field, Object value) {
            checkRow(row);
            if (value == null) throw new IllegalArgumentException("component fields cannot be assigned null");
            column(type, field)[row] = value;
        }

        private Object[] column(ComponentType type, String field) {
            Map<String, Object[]> fields = columns.get(type);
            if (fields == null) throw new IllegalArgumentException("archetype does not contain component '" + type.name() + "'");
            Object[] values = fields.get(field);
            if (values == null) {
                throw new IllegalArgumentException(
                        "component '" + type.name() + "' has no field '" + field + "'");
            }
            return values;
        }

        private Map<ComponentType, Map<String, Object>> snapshot(int row) {
            checkRow(row);
            LinkedHashMap<ComponentType, Map<String, Object>> result = new LinkedHashMap<>();
            for (ComponentType type : key.ordered) {
                LinkedHashMap<String, Object> values = new LinkedHashMap<>();
                for (String field : type.fields()) values.put(field, columns.get(type).get(field)[row]);
                result.put(type, Collections.unmodifiableMap(values));
            }
            return result;
        }

        /**
         * Removes a row with swap-remove. Returns the entity moved into the
         * removed row, or null when the removed row was already the last row.
         */
        private Entity removeSwap(int row) {
            checkRow(row);
            int last = size - 1;
            Entity moved = row == last ? null : entities[last];

            if (row != last) {
                entities[row] = entities[last];
                for (Map<String, Object[]> fields : columns.values()) {
                    for (Object[] column : fields.values()) column[row] = column[last];
                }
            }

            entities[last] = null;
            for (Map<String, Object[]> fields : columns.values()) {
                for (Object[] column : fields.values()) column[last] = null;
            }
            size--;
            return moved;
        }

        private void checkRow(int row) {
            if (row < 0 || row >= size) throw new IndexOutOfBoundsException("ECS chunk row " + row);
        }
    }

    /**
     * A dense, non-owning query window over one archetype chunk. Structural
     * mutation invalidates the view; ordinary component writes do not.
     */
    public static final class ChunkView {
        private final EcsWorld world;
        private final Archetype archetype;
        private final Chunk chunk;
        private final QueryAccess access;
        private final long structureVersion;

        private ChunkView(
                EcsWorld world,
                Archetype archetype,
                Chunk chunk,
                QueryAccess access,
                long structureVersion) {
            this.world = world;
            this.archetype = archetype;
            this.chunk = chunk;
            this.access = access;
            this.structureVersion = structureVersion;
        }

        public int size() {
            checkFresh();
            return chunk.size;
        }

        public Entity entity(int row) {
            checkFresh();
            chunk.checkRow(row);
            return chunk.entities[row];
        }

        public boolean has(ComponentType component) {
            checkFresh();
            ComponentType canonical = world.canonical(component);
            if (!access.mayRead(canonical)) {
                throw new IllegalStateException(
                        "query did not declare access to component '" + canonical.name() + "'");
            }
            return archetype.key.components().contains(canonical);
        }

        /**
         * Reads one field. Optional components that are absent from this
         * archetype return null; actual component field values cannot be null.
         */
        public Object get(int row, ComponentType component, String field) {
            checkFresh();
            ComponentType canonical = world.canonical(component);
            if (!access.mayRead(canonical)) {
                throw new IllegalStateException(
                        "query did not declare read access to component '" + canonical.name() + "'");
            }
            if (!archetype.key.components().contains(canonical)) {
                if (access.optional().contains(canonical)) return null;
                throw new IllegalStateException(
                        "required component '" + canonical.name() + "' is absent from query archetype");
            }
            return chunk.read(row, canonical, field);
        }

        public void set(int row, ComponentType component, String field, Object value) {
            checkFresh();
            ComponentType canonical = world.canonical(component);
            if (!access.mayWrite(canonical)) {
                throw new IllegalStateException(
                        "query did not declare write access to component '" + canonical.name() + "'");
            }
            if (!archetype.key.components().contains(canonical)) {
                throw new IllegalStateException(
                        "writable component '" + canonical.name() + "' is absent from query archetype");
            }
            chunk.write(row, canonical, field, value);
        }

        /**
         * Trusted lowering hook: exposes the physical field column without a
         * copy. It remains package-private so guest code cannot bypass query
         * read/write admission.
         */
        Object[] rawColumn(ComponentType component, String field) {
            checkFresh();
            ComponentType canonical = world.canonical(component);
            if (!access.mayRead(canonical)) {
                throw new IllegalStateException(
                        "query did not declare access to component '" + canonical.name() + "'");
            }
            return chunk.column(canonical, field);
        }

        private void checkFresh() {
            if (structureVersion != world.structureVersion) {
                throw new IllegalStateException(
                        "ECS query view was invalidated by structural mutation; acquire a new query");
            }
        }
    }

    /**
     * Ordered, one-shot structural command buffer. apply() preflights the full
     * sequence against a simulated component signature before mutating storage,
     * preventing ordinary validation errors from leaving a half-applied batch.
     */
    public static final class CommandBuffer {
        private final EcsWorld world;
        private final List<Command> commands = new ArrayList<>();
        private boolean applied;

        private CommandBuffer(EcsWorld world) {
            this.world = world;
        }

        public CommandBuffer add(Entity entity, ComponentType component, Map<String, ?> values) {
            ensureOpen();
            ComponentType canonical = world.canonical(component);
            Map<String, Object> normalized = world.normalizeValues(canonical, values);
            commands.add(new Add(entity, canonical, normalized));
            return this;
        }

        public CommandBuffer remove(Entity entity, ComponentType component) {
            ensureOpen();
            commands.add(new Remove(entity, world.canonical(component)));
            return this;
        }

        public CommandBuffer destroy(Entity entity) {
            ensureOpen();
            commands.add(new Destroy(entity));
            return this;
        }

        public int size() {
            return commands.size();
        }

        public void apply() {
            ensureOpen();
            preflight();
            applied = true;
            for (Command command : commands) {
                if (command instanceof Add add) world.addNow(add.entity, add.component, add.values);
                else if (command instanceof Remove remove) world.removeNow(remove.entity, remove.component);
                else if (command instanceof Destroy destroy) world.destroyNow(destroy.entity);
            }
        }

        private void preflight() {
            Map<Long, SimulatedEntity> simulated = new LinkedHashMap<>();
            for (Command command : commands) {
                Entity entity = command.entity();
                SimulatedEntity state = simulated.computeIfAbsent(entity.raw(), ignored -> {
                    Slot slot = world.requireAlive(entity);
                    return new SimulatedEntity(
                            true,
                            new LinkedHashSet<>(slot.location.archetype.key.components()));
                });
                if (!state.alive) {
                    throw new IllegalStateException(
                            "command targets entity after it was destroyed in the same command buffer: " + entity.raw());
                }

                if (command instanceof Add add) {
                    if (!state.components.add(add.component)) {
                        throw new IllegalArgumentException(
                                "entity " + entity.raw() + " already has component '" + add.component.name() + "'");
                    }
                } else if (command instanceof Remove remove) {
                    if (!state.components.remove(remove.component)) {
                        throw new IllegalArgumentException(
                                "entity " + entity.raw() + " does not have component '" + remove.component.name() + "'");
                    }
                } else if (command instanceof Destroy) {
                    state.alive = false;
                }
            }
        }

        private void ensureOpen() {
            if (applied) throw new IllegalStateException("ECS command buffer has already been applied");
        }

        private static final class SimulatedEntity {
            private boolean alive;
            private final LinkedHashSet<ComponentType> components;

            private SimulatedEntity(boolean alive, LinkedHashSet<ComponentType> components) {
                this.alive = alive;
                this.components = components;
            }
        }

        private sealed interface Command permits Add, Remove, Destroy {
            Entity entity();
        }

        private record Add(Entity entity, ComponentType component, Map<String, Object> values)
                implements Command { }

        private record Remove(Entity entity, ComponentType component) implements Command { }

        private record Destroy(Entity entity) implements Command { }
    }
}
