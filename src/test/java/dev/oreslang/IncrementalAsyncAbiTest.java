package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class IncrementalAsyncAbiTest {
    @Test
    void syncToAsyncCallableChangeInvalidatesImporters() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        Map<String, String> first = Map.of(
                "lib.ores", """
                        pub fnc work() => int {
                          return 1;
                        }
                        """,
                "app.ores", """
                        import fnc work from "./lib.ores";

                        pub fnc main() => void {
                          val value = work();
                          return;
                        }
                        """);

        compiler.compile(first);

        Map<String, String> second = Map.of(
                "lib.ores", """
                        pub async fnc work() => int {
                          return 1;
                        }
                        """,
                "app.ores", first.get("app.ores"));

        IncrementalCompiler.BuildResult rebuilt = compiler.compile(second);

        assertTrue(rebuilt.rebuilt("lib.ores"));
        assertTrue(
                rebuilt.rebuilt("app.ores"),
                "sync -> async changes the public call ABI and must invalidate importers");
    }
}
