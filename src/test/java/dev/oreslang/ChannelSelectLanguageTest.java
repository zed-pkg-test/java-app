package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ChannelSelectLanguageTest {
    @Test
    void parserAndCheckerAcceptStaticBlockingAndDefaultSelect() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc probe(Channel<int> input, Channel<int> output): void {
                  select {
                    case readch(input) as value -> {
                      writech(output, value);
                    }
                    case writech(output, 9) -> {
                      return;
                    }
                    default -> {
                      return;
                    }
                  }
                  return;
                }
                """)));
    }

    @Test
    void typedReadBindingMustMatchChannelElement() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad(Channel<int> input): void {
                          select {
                            case readch(input) as String value -> {
                              return;
                            }
                          }
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("readch select binding"), error.getMessage());
    }

    @Test
    void trySelectSyntaxIsExplicitlyNonblocking() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc probe(Channel<int> input): void {
                  try select {
                    case readch(input) as value -> {
                      stdio.println(value);
                    }
                  }
                  return;
                }
                """)));
    }

    @Test
    void dynamicSelectAcceptsListAndMapEntries() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc probe(Channel<int> a, Channel<int> b): void {
                  val cases = [select.readch(a), select.writech(b, 7)];
                  try select from cases as chosen {
                    stdio.println(chosen.index);
                    stdio.println(chosen.kind);
                    stdio.println(chosen.value);
                  }

                  val by_name = obj{
                    inbox: select.readch(a),
                    outbox: select.writech(b, 8)
                  };
                  try select from by_name as picked {
                    stdio.println(picked.key);
                  }
                  return;
                }
                """)));
    }

    @Test
    void runtimeExecutesStaticSelectWithoutTouchingLosingCase() throws Exception {
        String program = """
                pub fnc main(): void {
                  val left = channel<int>(1);
                  val right = channel<int>(1);
                  writech(left, 41);

                  select {
                    case readch(left) as value -> {
                      stdio.println(value);
                    }
                    case readch(right) as value -> {
                      stdio.println(value);
                    }
                  }

                  writech(right, 99);
                  val survivor = readch(right);
                  stdio.println(survivor);
                  return;
                }
                """;

        String output = run(program, "static-select.ores");
        assertTrue(output.contains("41"), output);
        assertTrue(output.contains("99"), output);
    }

    @Test
    void runtimeExecutesNonblockingDefault() throws Exception {
        String program = """
                pub fnc main(): void {
                  val empty = channel<int>(1);
                  select {
                    case readch(empty) as value -> {
                      stdio.println(value);
                    }
                    default -> {
                      stdio.println("idle");
                    }
                  }
                  return;
                }
                """;

        assertTrue(run(program, "default-select.ores").contains("idle"));
    }

    @Test
    void runtimeExecutesDynamicListAndMapSelect() throws Exception {
        String program = """
                pub fnc main(): void {
                  val a = channel<int>(1);
                  val b = channel<int>(1);
                  writech(b, 22);

                  val cases = [select.readch(a), select.readch(b)];
                  select from cases as chosen {
                    stdio.println(chosen.index);
                    stdio.println(chosen.value);
                  }

                  val c = channel<int>(1);
                  writech(c, 33);
                  val named = obj{
                    first: select.readch(a),
                    ready: select.readch(c)
                  };
                  select from named as picked {
                    stdio.println(picked.key);
                    stdio.println(picked.value);
                  }
                  return;
                }
                """;

        String output = run(program, "dynamic-select.ores");
        assertTrue(output.contains("22"), output);
        assertTrue(output.contains("ready"), output);
        assertTrue(output.contains("33"), output);
    }

    private static String run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
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
