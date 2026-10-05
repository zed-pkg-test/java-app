package dev.oreslang.runtime.ecs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class EcsWorldTest {
    private static final ComponentType POSITION = ComponentType.of("Position", "x", "y");
    private static final ComponentType VELOCITY = ComponentType.of("Velocity", "x", "y");
    private static final ComponentType HEALTH = ComponentType.of("Health", "value");
    private static final ComponentType SLEEPING = ComponentType.of("Sleeping");

    @Test
    void storesAndMutatesDenseArchetypeColumnsThroughDeclaredAccess() {
        EcsWorld world = world();
        Entity first = world.spawn(Map.of(
                POSITION, Map.of("x", 1.0f, "y", 2.0f),
                VELOCITY, Map.of("x", 3.0f, "y", 4.0f)));
        Entity second = world.spawn(Map.of(
                POSITION, Map.of("x", 10.0f, "y", 20.0f),
                VELOCITY, Map.of("x", 30.0f, "y", 40.0f)));
        world.spawn(Map.of(POSITION, Map.of("x", 99.0f, "y", 100.0f)));

        QueryAccess movement = QueryAccess.builder()
                .write(POSITION)
                .read(VELOCITY)
                .build();

        List<EcsWorld.ChunkView> chunks = world.query(movement);
        assertEquals(1, chunks.size());
        EcsWorld.ChunkView chunk = chunks.getFirst();
        assertEquals(2, chunk.size());
        assertEquals(first, chunk.entity(0));
        assertEquals(second, chunk.entity(1));

        for (int row = 0; row < chunk.size(); row++) {
            float x = (Float) chunk.get(row, POSITION, "x");
            float vx = (Float) chunk.get(row, VELOCITY, "x");
            chunk.set(row, POSITION, "x", x + vx);
        }

        assertEquals(4.0f, world.read(first, POSITION, "x"));
        assertEquals(40.0f, world.read(second, POSITION, "x"));
        assertThrows(IllegalStateException.class, () -> chunk.set(0, VELOCITY, "x", 0.0f));
    }

    @Test
    void optionalAndWithoutFiltersMatchArchetypesWithoutPerEntityChecks() {
        EcsWorld world = world();
        Entity awake = world.spawn(Map.of(
                POSITION, Map.of("x", 1.0f, "y", 2.0f),
                HEALTH, Map.of("value", 10.0f)));
        world.spawn(Map.of(
                POSITION, Map.of("x", 3.0f, "y", 4.0f),
                SLEEPING, Map.of()));

        QueryAccess query = QueryAccess.builder()
                .read(POSITION)
                .optional(HEALTH)
                .without(SLEEPING)
                .build();

        List<EcsWorld.ChunkView> chunks = world.query(query);
        assertEquals(1, chunks.size());
        assertEquals(awake, chunks.getFirst().entity(0));
        assertTrue(chunks.getFirst().has(HEALTH));
        assertEquals(10.0f, chunks.getFirst().get(0, HEALTH, "value"));
    }

    @Test
    void structuralCommandsAreDeferredAndInvalidateOldViewsAtApplyBarrier() {
        EcsWorld world = world();
        Entity entity = world.spawn(Map.of(POSITION, Map.of("x", 1.0f, "y", 2.0f)));

        QueryAccess positions = QueryAccess.builder().read(POSITION).build();
        EcsWorld.ChunkView oldView = world.query(positions).getFirst();

        EcsWorld.CommandBuffer commands = world.commands()
                .add(entity, HEALTH, Map.of("value", 100.0f));

        assertFalse(world.components(entity).contains(HEALTH));
        assertEquals(1, oldView.size());

        commands.apply();

        assertTrue(world.components(entity).contains(HEALTH));
        assertThrows(IllegalStateException.class, oldView::size);
        assertEquals(100.0f, world.read(entity, HEALTH, "value"));
        assertThrows(IllegalStateException.class, commands::apply);
    }

    @Test
    void commandPreflightAvoidsPartialMutationForInvalidBatch() {
        EcsWorld world = world();
        Entity entity = world.spawn(Map.of(POSITION, Map.of("x", 1.0f, "y", 2.0f)));

        EcsWorld.CommandBuffer commands = world.commands()
                .add(entity, HEALTH, Map.of("value", 10.0f))
                .add(entity, HEALTH, Map.of("value", 20.0f));

        assertThrows(IllegalArgumentException.class, commands::apply);
        assertFalse(world.components(entity).contains(HEALTH));
    }

    @Test
    void recycledSlotsUseGenerationsSoStaleEntityHandlesCannotAlias() {
        EcsWorld world = world();
        Entity old = world.spawn(Map.of(POSITION, Map.of("x", 1.0f, "y", 2.0f)));
        world.commands().destroy(old).apply();

        assertFalse(world.isAlive(old));
        assertThrows(IllegalStateException.class, () -> world.read(old, POSITION, "x"));

        Entity replacement = world.spawn(Map.of(POSITION, Map.of("x", 9.0f, "y", 8.0f)));
        assertEquals(old.index(), replacement.index());
        assertNotEquals(old.generation(), replacement.generation());
        assertTrue(world.isAlive(replacement));
        assertFalse(world.isAlive(old));
    }

    @Test
    void accessConflictsProduceDeterministicSafeParallelBatches() {
        QueryAccess movement = QueryAccess.builder().write(POSITION).read(VELOCITY).build();
        QueryAccess health = QueryAccess.builder().write(HEALTH).build();
        QueryAccess render = QueryAccess.builder().read(POSITION).build();

        List<List<EcsSystemPlanner.SystemSpec>> batches = EcsSystemPlanner.parallelBatches(List.of(
                new EcsSystemPlanner.SystemSpec("movement", movement),
                new EcsSystemPlanner.SystemSpec("health", health),
                new EcsSystemPlanner.SystemSpec("render", render)));

        assertEquals(List.of("movement", "health"),
                batches.getFirst().stream().map(EcsSystemPlanner.SystemSpec::name).toList());
        assertEquals(List.of("render"),
                batches.get(1).stream().map(EcsSystemPlanner.SystemSpec::name).toList());
        assertTrue(movement.conflictsWith(render));
        assertFalse(movement.conflictsWith(health));
    }

    private static EcsWorld world() {
        EcsWorld world = new EcsWorld(2);
        world.register(POSITION);
        world.register(VELOCITY);
        world.register(HEALTH);
        world.register(SLEEPING);
        return world;
    }
}
