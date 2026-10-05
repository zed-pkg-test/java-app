package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class CallableScopeEffectKeywordTest {
    @Test
    void pureAndTrapAreReservedKeywords() {
        var tokens = new Lexer("pure trap nlex").scan();
        assertEquals(Token.Type.PURE, tokens.get(0).type());
        assertEquals(Token.Type.TRAP, tokens.get(1).type());
        assertEquals(Token.Type.NLEX, tokens.get(2).type());
    }

    @Test
    void nestedNamedCallableDeclarationsAreRejectedExplicitly() {
        IllegalArgumentException fnc = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc outer(): void {
                          fnc inner(): void {
                            return;
                          }
                          return;
                        }
                        """));
        assertTrue(fnc.getMessage().contains("named fnc/routine declarations"));

        IllegalArgumentException routine = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        routine outer(): void {
                          routine inner(): void {
                            return;
                          }
                          return;
                        }
                        """));
        assertTrue(routine.getMessage().contains("named fnc/routine declarations"));

        IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Worker as
                          run(): void {
                            fnc inner(): void {
                              return;
                            }
                            return;
                          }
                        end
                        """));
        assertTrue(method.getMessage().contains("named fnc/routine declarations"));
    }

    @Test
    void declarationAndMemberModifiersAreIllegalInsideExecutableScopes() {
        for (String modifier : new String[]{"pub", "private", "static", "abstract", "async", "pure", "trap"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("""
                            fnc outer(): void {
                              %s fnc inner(): void {
                                return;
                              }
                              return;
                            }
                            """.formatted(modifier)));
            assertTrue(error.getMessage().contains("not valid"),
                    modifier + ": " + error.getMessage());
        }

        IllegalArgumentException nlexDeclaration = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc outer(): void {
                          nlex fnc inner(): void {
                            return;
                          }
                          return;
                        }
                        """));
        assertTrue(nlexDeclaration.getMessage().contains("cannot introduce a nested named declaration"));
    }

    @Test
    void localFunctionExpressionBindingsAreAllowed() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc outer(): void {
                  let fnc first = || -> {
                    return;
                  };
                  first();
                  return;
                }

                routine second_outer(): void {
                  const fnc second = || -> {
                    return;
                  };
                  second();
                  return;
                }

                define class Worker as
                  run(): void {
                    val fnc third = || -> {
                      return;
                    };
                    third();
                    return;
                  }
                end
                """)));
    }

    @Test
    void localFncMarkerRequiresAFunctionExpression() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc outer(): void {
                          let fnc not_a_function = 42;
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("requires a function expression"));
    }

    @Test
    void functionExpressionsAreExecutableScopeOnly() {
        IllegalArgumentException namedExpression = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc callback = || -> {
                          return;
                        }
                        """));
        assertTrue(namedExpression.getMessage().contains("executable-scope-only"));

        IllegalArgumentException moduleBinding = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module callbacks
                          let callback = || -> {
                            return;
                          };
                        end
                        """));
        assertTrue(moduleBinding.getMessage().contains("executable-scope-only"));

        IllegalArgumentException classField = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Callbacks as
                          let callback = || -> {
                            return;
                          };
                        end
                        """));
        assertTrue(classField.getMessage().contains("executable-scope-only"));
    }

    @Test
    void nlexBarrierIsInheritedByLocalFunctionExpressions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        nlex fnc outer(int input): void {
                          let fnc callback = || -> {
                            stdio.println(input);
                            return;
                          };
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("input")
                        || error.getMessage().contains("unknown")
                        || error.getMessage().contains("capture"),
                error.getMessage());
    }

    @Test
    void pureChecksNestedFunctionExpressionsAsIndependentBoundaries() {
        IllegalArgumentException parameterCapture = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pure fnc outer(List<int> values): int {
                          let fnc callback = || -> {
                            values[0] = 9;
                            return;
                          };
                          return 1;
                        }
                        """)));
        assertTrue(parameterCapture.getMessage().contains("captured binding")
                        || parameterCapture.getMessage().contains("parameter")
                        || parameterCapture.getMessage().contains("pure"),
                parameterCapture.getMessage());

        IllegalArgumentException localCapture = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pure fnc outer(): int {
                          let int state = 0;
                          let fnc callback = || -> {
                            state = 1;
                            return;
                          };
                          return state;
                        }
                        """)));
        assertTrue(localCapture.getMessage().contains("captured binding"),
                localCapture.getMessage());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pure fnc outer(int input): int {
                  let fnc callback = || -> {
                    let int copy = input;
                    copy = copy + 1;
                    return;
                  };
                  callback();
                  return input;
                }
                """)));
    }

    @Test
    void pureAllowsOutsideReadsButRejectsOutsideWrites() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  let int outside = 7;

                  pure fnc read_outside(): int {
                    return outside;
                  }
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          let int outside = 7;

                          pure fnc write_outside(): int {
                            outside = 8;
                            return outside;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("outside binding")
                        || error.getMessage().contains("external"),
                error.getMessage());
    }

    @Test
    void pureCallsMustHaveProvenPureEffectsIncludingLocalCallbacks() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pure fnc one(): int {
                  return 1;
                }

                pure fnc two(): int {
                  let fnc local = || -> {
                    let int x = 1;
                    x = x + 1;
                    return;
                  };
                  local();
                  return one() + 1;
                }
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc impure(): int {
                          return 1;
                        }

                        pure fnc bad(): int {
                          return impure();
                        }
                        """)));
        assertTrue(error.getMessage().contains("unproven-effect callable"), error.getMessage());
    }

    @Test
    void functionExpressionEffectsAreExplicitAndComposable() {
        Ast.Program program = Parser.parse("""
                fnc outer(): void {
                  let fnc callback = pure nlex trap || -> {
                    return;
                  };
                  return;
                }
                """);
        Ast.FunctionDecl outer = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt binding = (Ast.BindingStmt) outer.body().getFirst();
        Ast.LambdaExpr callback = (Ast.LambdaExpr) binding.initializer();
        assertTrue(callback.pure());
        assertTrue(callback.nonLexical());
        assertTrue(callback.trapped());
    }

    @Test
    void explicitPureFunctionExpressionCannotLaunderCapturedWrites() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc outer(List<int> values): void {
                          let fnc callback = pure || -> {
                            values[0] = 9;
                            return;
                          };
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("pure"), error.getMessage());
    }

    @Test
    void explicitTrapFunctionExpressionOwnsItsThrowBoundary() throws Exception {
        String output = run("""
                pub fnc main(): void {
                  let fnc fail = trap || -> {
                    return 10 / 0;
                  };
                  [const value, const err] = fail();
                  stdio.println(value);
                  stdio.println(err);
                  return;
                }
                """);

        assertTrue(output.contains("None"), output);
        assertTrue(output.contains("Some("), output);
        assertTrue(output.contains("ArithmeticException"), output);
    }

    @Test
    void trapCoversSynchronousNestedFunctionInvocation() throws Exception {
        String output = run("""
                trap fnc outer(): int {
                  let fnc fail = || -> {
                    return 10 / 0;
                  };
                  return fail();
                }

                pub fnc main(): void {
                  [const value, const err] = outer();
                  stdio.println(value);
                  stdio.println(err);
                  return;
                }
                """);

        assertTrue(output.contains("None"), output);
        assertTrue(output.contains("Some("), output);
        assertTrue(output.contains("ArithmeticException"), output);
    }

    @Test
    void trapIsNotInheritedByEscapedFunctionExpressions() {
        assertThrows(Exception.class, () -> run("""
                trap fnc make_failure(): Fnc<int> {
                  let fnc fail = || -> {
                    return 10 / 0;
                  };
                  return fail;
                }

                pub fnc main(): void {
                  [const maybe_callback, const err] = make_failure();
                  const callback = maybe_callback.unwrap();
                  callback();
                  return;
                }
                """));
    }

    @Test
    void pureAndTrapRejectAsyncAndActorBoundariesForNow() {
        IllegalArgumentException pureAsync = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pure async fnc work(): int {
                          return 1;
                        }
                        """)));
        assertTrue(pureAsync.getMessage().contains("cannot be async"));

        IllegalArgumentException trapAsync = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        trap async fnc work(): int {
                          return 1;
                        }
                        """)));
        assertTrue(trapAsync.getMessage().contains("cannot wrap async"));

        IllegalArgumentException actorPure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pure actor fnc work(): int {
                          return 1;
                        }
                        """)));
        assertTrue(actorPure.getMessage().contains("cannot be pure"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "callable-effects.ores")
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
