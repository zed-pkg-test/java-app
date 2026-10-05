package dev.oreslang.runtime.ecs;

/**
 * Opaque ECS identity. The low 32 bits hold the slot index and the high
 * 32 bits hold a generation, so stale handles cannot alias recycled slots.
 */
public record Entity(long raw) {
    public Entity {
        if (raw == 0L) throw new IllegalArgumentException("entity id 0 is reserved");
    }

    static Entity of(int index, int generation) {
        if (index < 0) throw new IllegalArgumentException("entity index cannot be negative");
        if (generation <= 0) throw new IllegalArgumentException("entity generation must be positive");
        return new Entity(((long) generation << 32) | Integer.toUnsignedLong(index));
    }

    public int index() {
        return (int) raw;
    }

    public int generation() {
        return (int) (raw >>> 32);
    }
}
