package dev.oreslang.runtime.ecs;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Declarative component effects for one ECS query/system. Writes imply read
 * access to the same component, but a component may not be redundantly listed
 * in both read and write sets.
 */
public record QueryAccess(
        Set<ComponentType> reads,
        Set<ComponentType> writes,
        Set<ComponentType> optional,
        Set<ComponentType> without) {

    public QueryAccess {
        reads = Set.copyOf(reads);
        writes = Set.copyOf(writes);
        optional = Set.copyOf(optional);
        without = Set.copyOf(without);

        Set<ComponentType> overlap = intersection(reads, writes);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("component access cannot be both read and write: " + names(overlap));
        }

        LinkedHashSet<ComponentType> required = new LinkedHashSet<>(reads);
        required.addAll(writes);
        overlap = intersection(required, without);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("required component cannot also be excluded: " + names(overlap));
        }
        overlap = intersection(optional, without);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("optional component cannot also be excluded: " + names(overlap));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Set<ComponentType> required() {
        LinkedHashSet<ComponentType> result = new LinkedHashSet<>(reads);
        result.addAll(writes);
        return Set.copyOf(result);
    }

    public boolean matches(Set<ComponentType> archetype) {
        return archetype.containsAll(required()) && java.util.Collections.disjoint(archetype, without);
    }

    public boolean mayRead(ComponentType component) {
        return reads.contains(component) || writes.contains(component) || optional.contains(component);
    }

    public boolean mayWrite(ComponentType component) {
        return writes.contains(component);
    }

    /** True when two systems cannot run concurrently over potentially overlapping archetypes. */
    public boolean conflictsWith(QueryAccess other) {
        LinkedHashSet<ComponentType> mine = new LinkedHashSet<>(writes);
        LinkedHashSet<ComponentType> otherTouched = new LinkedHashSet<>(other.reads);
        otherTouched.addAll(other.writes);
        otherTouched.addAll(other.optional);
        if (!java.util.Collections.disjoint(mine, otherTouched)) return true;

        LinkedHashSet<ComponentType> theirs = new LinkedHashSet<>(other.writes);
        LinkedHashSet<ComponentType> mineTouched = new LinkedHashSet<>(reads);
        mineTouched.addAll(writes);
        mineTouched.addAll(optional);
        return !java.util.Collections.disjoint(theirs, mineTouched);
    }

    private static Set<ComponentType> intersection(Set<ComponentType> left, Set<ComponentType> right) {
        LinkedHashSet<ComponentType> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static String names(Set<ComponentType> components) {
        return components.stream().map(ComponentType::name).sorted().toList().toString();
    }

    public static final class Builder {
        private final LinkedHashSet<ComponentType> reads = new LinkedHashSet<>();
        private final LinkedHashSet<ComponentType> writes = new LinkedHashSet<>();
        private final LinkedHashSet<ComponentType> optional = new LinkedHashSet<>();
        private final LinkedHashSet<ComponentType> without = new LinkedHashSet<>();

        public Builder read(ComponentType... components) {
            java.util.Collections.addAll(reads, components);
            return this;
        }

        public Builder write(ComponentType... components) {
            java.util.Collections.addAll(writes, components);
            return this;
        }

        public Builder optional(ComponentType... components) {
            java.util.Collections.addAll(optional, components);
            return this;
        }

        public Builder without(ComponentType... components) {
            java.util.Collections.addAll(without, components);
            return this;
        }

        public QueryAccess build() {
            return new QueryAccess(reads, writes, optional, without);
        }
    }
}
