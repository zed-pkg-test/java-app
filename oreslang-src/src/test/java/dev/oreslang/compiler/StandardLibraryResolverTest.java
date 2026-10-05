package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StandardLibraryResolverTest {

    @Test
    void bundledCollectionsJoinTheOrdinaryCompilationGraph() {
        String app = """
                import module collections from "std/collections";

                define module app
                  pub fnc make_size(): int {
                    let values = new collections.ArrayList<int>();
                    values.add(1);
                    values.add(2);
                    return values.size();
                  }
                end
                """;

        IncrementalCompiler.BuildResult result =
                new IncrementalCompiler().compile(Map.of("app.ores", app));

        assertTrue(result.units().containsKey("app.ores"));
        assertTrue(result.units().containsKey("std/collections.ores"));
        assertTrue(result.units().get("std/collections.ores").sourceText()
                .contains("define class ArrayList<T>"));
    }

    @Test
    void callerCannotShadowReservedStdlibNamespace() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(
                        "std/collections.ores",
                        "define module fake end")));

        assertTrue(failure.getMessage().contains("reserved"));
    }

    @Test
    void traversalOutOfStdlibFailsClosed() {
        String app = """
                import module escaped from "std/../escaped";
                define module app
                end
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of("app.ores", app)));
    }

    @Test
    void unknownStdlibUnitFailsClosed() {
        String app = """
                import module missing from "std/does/not/exist";
                define module app
                end
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of("app.ores", app)));

        assertTrue(failure.getMessage().contains("unknown Oreslang standard-library import"));
    }
}
