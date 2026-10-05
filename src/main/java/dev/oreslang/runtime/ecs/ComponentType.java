package dev.oreslang.runtime.ecs;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable runtime descriptor for a data-only Oreslang component. */
public record ComponentType(String name, List<String> fields) implements Comparable<ComponentType> {
    public ComponentType {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("component name cannot be blank");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String field : fields) {
            if (field == null || field.isBlank()) throw new IllegalArgumentException("component field cannot be blank");
            if (!seen.add(field)) throw new IllegalArgumentException("duplicate component field '" + field + "'");
        }
    }

    public static ComponentType of(String name, String... fields) {
        return new ComponentType(name, List.of(fields));
    }

    @Override
    public int compareTo(ComponentType other) {
        return name.compareTo(other.name);
    }
}
