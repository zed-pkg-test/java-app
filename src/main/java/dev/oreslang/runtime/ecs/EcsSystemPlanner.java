package dev.oreslang.runtime.ecs;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds deterministic conflict levels from declared ECS effects. A system is
 * placed after every earlier system that conflicts with it; systems at the
 * same level are safe to schedule concurrently.
 */
public final class EcsSystemPlanner {
    private EcsSystemPlanner() { }

    public record SystemSpec(String name, QueryAccess access) {
        public SystemSpec {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("system name cannot be blank");
            if (access == null) throw new IllegalArgumentException("system access cannot be null");
        }
    }

    public static List<List<SystemSpec>> parallelBatches(List<SystemSpec> systems) {
        systems = List.copyOf(systems);
        int[] levels = new int[systems.size()];
        int max = -1;

        for (int i = 0; i < systems.size(); i++) {
            int level = 0;
            for (int j = 0; j < i; j++) {
                if (systems.get(i).access().conflictsWith(systems.get(j).access())) {
                    level = Math.max(level, levels[j] + 1);
                }
            }
            levels[i] = level;
            max = Math.max(max, level);
        }

        List<List<SystemSpec>> result = new ArrayList<>();
        for (int level = 0; level <= max; level++) {
            List<SystemSpec> batch = new ArrayList<>();
            for (int i = 0; i < systems.size(); i++) {
                if (levels[i] == level) batch.add(systems.get(i));
            }
            result.add(List.copyOf(batch));
        }
        return List.copyOf(result);
    }
}
