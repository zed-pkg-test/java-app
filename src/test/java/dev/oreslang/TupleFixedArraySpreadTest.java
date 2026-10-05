package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TupleFixedArraySpreadTest {
    @Test
    void canonicalTupleTypesReturnsAndParenDestructuringTypeCheck() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module app as
                  fnc returns_tuple(): Tuple[int, string, bool] {
                    return (5, "hi", true);
                  }

                  fnc use(): void {
                    val (x, y, z) = returns_tuple();
                    val (a, b, c): (int, string, bool) = returns_tuple();
                    val int number = x;
                    val string message = y;
                    val bool flag = z;
                    val int annotated_number = a;
                    val string annotated_message = b;
                    val bool annotated_flag = c;
                    return;
                  }
                end
                """));

        Ast.ModuleDecl module = program.modules().getFirst();
        Ast.FunctionDecl use = (Ast.FunctionDecl) module.declarations().get(1);
        Ast.DestructureStmt annotated = (Ast.DestructureStmt) use.body().get(1);
        assertNotNull(annotated.declaredType());
        assertTrue(annotated.declaredType().isTupleType());
        assertEquals(3, annotated.declaredType().arguments().size());
    }

    @Test
    void tupleGroupingOneElementAndEmptyTupleStayUnambiguous() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc one(): (int,) {
                    return (5,);
                  }

                  fnc empty(): () {
                    return ();
                  }

                  fnc grouped(): int {
                    return (5);
                  }

                  fnc use(): void {
                    val (x,) = one();
                    val int number = x;
                    empty();
                    grouped();
                    return;
                  }
                end
                """)));
    }

    @Test
    void fixedArrayIsUniformFixedStorageAndMayUseUnionElementType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  type Scalar = int | string | bool;

                  fnc use(): void {
                    val FixedArray[Scalar, 3] values = [5, "hi", true];
                    val Scalar first = values[0];
                    values[0] = "changed";
                    val Scalar dynamic = values[1];
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc broken(): void {
                    val FixedArray[int, 3] values = [1, 2];
                    return;
                  }
                end
                """)));
    }

    @Test
    void tupleSlotsAreImmutableButFixedArraySlotsAreMutable() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc broken(): void {
                    val tuple = (1, 2, 3);
                    tuple[0] = 9;
                    return;
                  }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc good(): void {
                    val FixedArray[int, 3] values = [1, 2, 3];
                    values[0] = 9;
                    return;
                  }
                end
                """)));
    }

    @Test
    void tupleConstantIndexKeepsPositionalTypeAndDynamicIndexUsesUnion() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc choose(int index): int | string {
                    val tuple = (7, "seven");
                    val int exact = tuple[0];
                    return tuple[index];
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc broken(): void {
                    val tuple = (7, "seven");
                    val string wrong = tuple[0];
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc broken(): void {
                    val tuple = (7, "seven");
                    stdio.println(tuple[2]);
                    return;
                  }
                end
                """)));
    }

    @Test
    void tupleAndHomogeneousFixedArraySpreadIntoFixedArityCalls() {
        assertDoesNotThrow(() -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    define module app as
                      fnc mixed(int number, string text, bool flag): void {
                        return;
                      }

                      fnc sum3(int a, int b, int c): int {
                        return a + b + c;
                      }

                      fnc use(): void {
                        val tuple = (5, "hi", true);
                        mixed(...tuple);

                        val FixedArray[int, 3] values = [1, 2, 3];
                        val int total = sum3(...values);
                        stdio.println(total);
                        return;
                      }
                    end
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void singletonFixedArraySpreadIsConsumedOnceByOwnershipAnalysis() {
        assertDoesNotThrow(() -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    define module app as
                      fnc consume(int value): void {
                        return;
                      }

                      fnc use(): void {
                        val FixedArray[int, 1] values = [7];
                        consume(...values);
                        return;
                      }
                    end
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void heterogeneousFixedArraySpreadRetainsUniformUnionSlotTyping() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  type Scalar = int | string | bool;

                  fnc consume(Scalar a, Scalar b, Scalar c): void {
                    return;
                  }

                  fnc use(): void {
                    val FixedArray[Scalar, 3] values = [5, "hi", true];
                    consume(...values);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  type Scalar = int | string | bool;

                  fnc positional(int a, string b, bool c): void {
                    return;
                  }

                  fnc broken(): void {
                    val FixedArray[Scalar, 3] values = [5, "hi", true];
                    positional(...values);
                    return;
                  }
                end
                """)));
    }

    @Test
    void dynamicArrayCannotSpreadIntoFixedArityCall() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          fnc sum3(int a, int b, int c): int {
                            return a + b + c;
                          }

                          fnc broken(): int {
                            val Array<int> values = [1, 2, 3];
                            return sum3(...values);
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("dynamic Array/List"));
    }

    @Test
    void spreadExpressionIsEvaluatedExactlyOnceAtRuntime() throws Exception {
        String program = """
                define module app as
                  fnc pair(): (int, int) {
                    stdio.println("pair-evaluated");
                    return (20, 22);
                  }

                  fnc add(int a, int b): int {
                    return a + b;
                  }

                  pub fnc main(): void {
                    stdio.println(add(...pair()));
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "tuple-spread-once.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(1, occurrences(text, "pair-evaluated"));
        assertTrue(text.contains("42"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
