package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class BlockLoopControlFlowTest {
    @Test
    void blockCreatesAStandaloneLexicalScope() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  block {
                    val hidden = 1;
                    stdio.stdout.write(hidden);
                  }
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  block {
                    val hidden = 1;
                  }
                  stdio.stdout.write(hidden);
                }
                """)));
    }

    @Test
    void loopSupportsBreakContinueAndMandatorySafepoints() throws Exception {
        String output = run("""
                pub routine main(): void {
                  let i = 0;
                  loop {
                    i = i + 1;
                    if i == 2 {
                      continue;
                    }
                    if i == 4 {
                      break;
                    }
                    stdio.stdout.write(i);
                  }
                  stdio.stdout.write(process.descriptor.scheduler_safepoints);
                }
                """);

        assertEquals("134", output);
    }

    @Test
    void returnEscapesLoopAndTheEnclosingCallable() throws Exception {
        String output = run("""
                fnc answer(): int {
                  loop {
                    return 7;
                  }
                }

                pub routine main(): void {
                  stdio.stdout.write(answer());
                }
                """);

        assertEquals("7", output);
    }

    @Test
    void breakAndContinueAreRejectedOutsideLoops() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  break;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  continue;
                }
                """)));
    }

    @Test
    void loopControlCannotCrossLambdaBoundary() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  loop {
                    val callback = || -> {
                      break;
                    };
                    break;
                  }
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  loop {
                    val callback = || -> {
                      continue;
                    };
                    break;
                  }
                }
                """)));
    }

    @Test
    void ifSupportsBraceAndThenFiForms() throws Exception {
        String braces = run("""
                pub routine main(): void {
                  val value = 2;
                  if value == 1 {
                    stdio.stdout.write("a");
                  } elseif value == 2 {
                    stdio.stdout.write("b");
                  } else {
                    stdio.stdout.write("c");
                  }
                }
                """);
        assertEquals("b", braces);

        String keywordDelimited = run("""
                pub routine main(): void {
                  val value = 2;
                  if value == 1 then
                    stdio.stdout.write("a");
                  elseif value == 2 then
                    stdio.stdout.write("b");
                  else
                    stdio.stdout.write("c");
                  fi
                }
                """);
        assertEquals("b", keywordDelimited);
    }

    @Test
    void legacyIfDoFiRemainsSourceCompatible() throws Exception {
        String output = run("""
                pub routine main(): void {
                  if true do
                    stdio.stdout.write("ok");
                  fi
                }
                """);
        assertEquals("ok", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "block-loop.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
