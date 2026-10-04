package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class CircularImportInitializationTest {
    @TempDir
    Path tempDir;

    @Test
    void twoFilesMayImportEachOtherAndOnlyMainRunsAutomatically() throws Exception {
        Path a = tempDir.resolve("a.ores");
        Path b = tempDir.resolve("b.ores");

        Files.writeString(a, """
                import fnc {b_value} from "./b.ores";

                pub fnc a_value(): String {
                  return "A";
                }

                fnc init(): void {
                  stdio.stdout.write("SHOULD-NOT-RUN");
                  return;
                }

                pub routine main(): void {
                  stdio.stdout.write("main:");
                  stdio.stdout.write(a_value());
                  stdio.stdout.write(b_value());
                  return;
                }
                """);

        Files.writeString(b, """
                import fnc {a_value} from "./a.ores";

                pub fnc b_value(): String {
                  return "B";
                }

                fnc init(): void {
                  stdio.stdout.write("SHOULD-NOT-RUN");
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        IncrementalCompiler.BuildResult build = LinkedProgramRunner.run(
                a,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                error);

        assertEquals("main:AB", output.toString(StandardCharsets.UTF_8));

        List<List<String>> groups = build.initializationGroups();
        assertEquals(1, groups.size(), "the A<->B cycle should still form one linked SCC");
        assertEquals(2, groups.getFirst().size());
        assertTrue(groups.getFirst().contains(a.toAbsolutePath().normalize().toString().replace('\\', '/')));
        assertTrue(groups.getFirst().contains(b.toAbsolutePath().normalize().toString().replace('\\', '/')));
    }

    @Test
    void initIsAnOrdinaryCallableNameWithNoLifecycleSignature() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc init(int value): int {
                  return value + 1;
                }

                pub routine main(): void {
                  val result = init(41);
                  stdio.println(result);
                  return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module lifecycle as
                  pub fnc init(String value): String {
                    return value;
                  }
                end

                pub routine main(): void {
                  val result = lifecycle.init("explicit");
                  return;
                }
                """)));
    }
}
