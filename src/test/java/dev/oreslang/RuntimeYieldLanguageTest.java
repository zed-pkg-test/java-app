package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
final class RuntimeYieldLanguageTest {
    @TempDir Path temp;

    @Test
    void rtYieldAndParenthesizedRtYieldResumeStacklessly() throws Exception {
        assertEquals("42", run("""
                define module app as
                  fnc bounce(): int {
                    rt yield;
                    rt yield();
                    return 42;
                  }

                  pub async fnc main(): void {
                    stdio.println(bounce());
                    return;
                  }
                end
                """));
    }

    @Test
    void manyRtYieldsDoNotRequireAsyncSpellingOrGrowTheJavaStack() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            body.append("    rt yield;\n");
        }

        assertEquals("ok", run("""
                define module app as
                  fnc bounce(): void {
                """ + body + """
                    stdio.println("ok");
                    return;
                  }

                  pub async fnc main(): void {
                    bounce();
                    return;
                  }
                end
                """));
    }

    @Test
    void rtYieldArgumentsFailClosedUntilSchedulingPolicySyntaxLands() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module app as
                          fnc bad(): void {
                            rt yield(1);
                            return;
                          }
                        end
                        """));
        assertTrue(failure.getMessage().contains("rt yield currently takes no arguments"),
                failure.getMessage());
    }

    @Test
    void generatorYieldStillProducesValuesAndIsNotRuntimeYield() throws Exception {
        assertEquals("7", run("""
                define module app as
                  generator fnc values(): int {
                    yield 7;
                    return;
                  }

                  pub fnc main(): void {
                    for val item of values() {
                      stdio.println(item);
                    }
                    return;
                  }
                end
                """));
    }

    @Test
    void rtYieldTypeChecksAsVoidEffect() {
        assertEquals(1, TypeChecker.check(Parser.parse("""
                define module app as
                  fnc handoff(): void {
                    rt yield;
                    return;
                  }
                end
                """)).modules().size());
    }

    private String run(String program) throws Exception {
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                output,
                new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8).strip();
    }
}
