package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class PatternMatchingTest {

    @Test
    void resultAndSymbolPatternsNarrowPayloadsAndSupportPureGuards() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc classify(Result<int, Symbol> value) : Symbol {
                  switch value {
                    Ok(val n) when n > 0 -> {
                      return :positive;
                    }
                    Err(:timeout) -> {
                      return :retry;
                    }
                    _ -> {
                      return :unknown;
                    }
                  }
                }
                """));

        assertDoesNotThrow(() -> OwnershipChecker.check(program));
        assertDoesNotThrow(() -> CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void effectfulGuardIsRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc decide() : bool {
                          return true;
                        }

                        fnc classify(Result<int, Symbol> value) : Symbol {
                          switch value {
                            Ok(val n) when decide() -> {
                              return :positive;
                            }
                            _ -> {
                              return :unknown;
                            }
                          }
                        }
                        """)));

        assertTrue(failure.getMessage().contains("guard"));
        assertTrue(failure.getMessage().contains("pure"));
    }

    @Test
    void clauseAfterUnconditionalCatchAllIsRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc classify(Symbol value) : int {
                          switch value {
                            _ -> {
                              return 0;
                            }
                            :timeout -> {
                              return 1;
                            }
                          }
                        }
                        """)));

        assertTrue(failure.getMessage().contains("unreachable"));
    }

    @Test
    void runtimeMatchesResultErrorSymbol() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, """
                pub fnc main() : void {
                  val Result<int, Symbol> result = Err(:timeout);

                  switch result {
                    Ok(val value) when value > 0 -> {
                      stdio.println("ok");
                    }
                    Err(:timeout) -> {
                      stdio.println("retry");
                    }
                    _ -> {
                      stdio.println("other");
                    }
                  }

                  return;
                }
                """, "pattern-errors.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertEquals("retry", output.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void untrustedPatternLiteralMustBeExplicitlyExported() {
        IsolatePolicy trusted = IsolatePolicy.developer();
        IsolatePolicy untrusted = IsolatePolicy.strictFaas();

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String visibleName = "visible_" + suffix;
        String hiddenName = "hidden_" + suffix;

        OresSymbol visible = OresSymbol.process(visibleName, trusted);
        OresSymbol.process(hiddenName, trusted);
        OresSymbol.exportToUntrusted(visible, trusted);

        try {
            Ast.Program visibleProgram = TypeChecker.check(Parser.parse("""
                    fnc classify(Symbol value) : bool {
                      switch value {
                        :%s -> { return true; }
                        _ -> { return false; }
                      }
                    }
                    """.formatted(visibleName)));
            assertDoesNotThrow(
                    () -> CapabilityChecker.check(visibleProgram, untrusted));

            Ast.Program hiddenProgram = TypeChecker.check(Parser.parse("""
                    fnc classify(Symbol value) : bool {
                      switch value {
                        :%s -> { return true; }
                        _ -> { return false; }
                      }
                    }
                    """.formatted(hiddenName)));
            assertThrows(
                    SecurityException.class,
                    () -> CapabilityChecker.check(hiddenProgram, untrusted));
        } finally {
            OresSymbol.revokeFromUntrusted(visible, trusted);
        }
    }
    @Test
    void classPatternsExposeOnlyPublicFields() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, """
                define class Box as
                  pub val int shown;
                  private val int hidden;
                end

                pub fnc main() : void {
                  val Box box = new Box(7, 99);
                  switch box {
                    Box(val shown) -> {
                      stdio.println(shown);
                    }
                    _ -> {
                      stdio.println(-1);
                    }
                  }
                  return;
                }
                """, "public-class-pattern.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertEquals("7", output.toString(StandardCharsets.UTF_8).trim());

        IllegalArgumentException privateArity = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int shown;
                          private val int hidden;
                        end

                        fnc reveal(Box box) : int {
                          switch box {
                            Box(val shown, val hidden) -> {
                              return hidden;
                            }
                            _ -> {
                              return 0;
                            }
                          }
                        }
                        """)));
        assertTrue(privateArity.getMessage().contains("public field"));
    }

    @Test
    void objectPatternCannotReadPrivateField() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Secret as
                          pub val int shown;
                          private val int hidden;
                        end

                        fnc reveal(Secret secret) : int {
                          switch secret {
                            { hidden: val value } -> {
                              return value;
                            }
                            _ -> {
                              return 0;
                            }
                          }
                        }
                        """)));
        assertTrue(failure.getMessage().contains("member"));
    }


}
