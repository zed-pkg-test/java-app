package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class BooleanIntrinsicsTest {
    @Test
    void typeChecksAritySelectedScalarAndListOverloads() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc combine(bool foo, bool bar, bool x, bool y): bool {
                  return Or(foo, bar, And(x, y));
                }

                fnc from_array(Array<bool> values): bool {
                  return BooleanOps.And(values);
                }

                fnc qualified(bool a, bool b, bool c): bool {
                  return BooleanOps.Or(a, b, c);
                }

                fnc empty_xor(): bool {
                  return Xor([]);
                }
                """)));
    }

    @Test
    void rejectsInvalidArityAndOperandTypes() {
        IllegalArgumentException scalarAtArityOne = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(bool value): bool {
                          return And(value);
                        }
                        """)));
        assertTrue(scalarAtArityOne.getMessage().contains("arity 1"));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  return Or();
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  return Xor(true, 1);
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  return BooleanOps.And([true, 1]);
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  return Or<bool>(true, false);
                }
                """)));
    }

    @Test
    void moduleAndTopLevelFunctionsOverloadByNameAndExactArity() throws Exception {
        String program = """
                define module Picks as
                  pub fnc pick(int x): int {
                    return x;
                  }

                  pub fnc pick(int x, int y): int {
                    return x + y;
                  }
                end

                fnc local(int x): int {
                  return x + 10;
                }

                fnc local(int x, int y): int {
                  return x + y + 10;
                }

                pub fnc main(): void {
                  stdio.println(Picks.pick(2));
                  stdio.println(Picks.pick(2, 3));
                  stdio.println(local(1));
                  stdio.println(local(1, 2));
                  return;
                }
                """;

        assertEquals("2\n5\n11\n13\n", run(program, "module-overloads.ores"));

        IllegalArgumentException duplicateArity = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module Bad as
                          pub fnc choose(int value): int {
                            return value;
                          }

                          pub fnc choose(bool value): int {
                            return 0;
                          }
                        end
                        """)));
        assertTrue(duplicateArity.getMessage().contains("name + arity"));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module AmbiguousValue as
                  pub fnc choose(int value): int {
                    return value;
                  }

                  pub fnc choose(int left, int right): int {
                    return left + right;
                  }
                end

                fnc bad(): void {
                  val callback = AmbiguousValue.choose;
                  return;
                }
                """)));
    }

    @Test
    void realBooleanOpsModuleShadowsBuiltinNamespace() throws Exception {
        String program = """
                define module BooleanOps as
                  pub fnc And(int left, int right): int {
                    return left + right;
                  }
                end

                pub fnc main(): void {
                  stdio.println(BooleanOps.And(2, 3));
                  return;
                }
                """;

        assertEquals("5\n", run(program, "boolean-ops-shadow.ores"));
    }

    @Test
    void runtimeUsesLogicalIdentitiesParityAndScalarShortCircuiting() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Array<bool> values = [false, false, true];

                  stdio.println(And(true, true, false));
                  stdio.println(BooleanOps.Or(false, false, true));
                  stdio.println(Xor(true, false, true));

                  stdio.println(BooleanOps.And([]));
                  stdio.println(Or([]));
                  stdio.println(Xor([]));

                  stdio.println(And(false, [true][99]));
                  stdio.println(BooleanOps.Or(true, [false][99]));

                  stdio.println(And([true, true, false]));
                  stdio.println(BooleanOps.Or(values));
                  stdio.println(Xor([true, true, false, true]));
                  return;
                }
                """;

        assertEquals(
                """
                false
                true
                false
                true
                false
                false
                false
                true
                false
                true
                true
                """,
                run(program, "boolean-intrinsics.ores"));
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
