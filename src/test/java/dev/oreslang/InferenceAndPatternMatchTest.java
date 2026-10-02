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

final class InferenceAndPatternMatchTest {

    @Test
    void lexerRecognizesMatchAnyUnknownAndPipeForward() {
        var tokens = new Lexer("fnc f(any x, unknown y) { return x |> match { _ => y }; }").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.MATCH));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ANY));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.UNKNOWN));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.PIPE_FORWARD));
    }

    @Test
    void omittedCallableReturnTypeUsesInferenceMarkerAndExactSourceSpanBudget() {
        Ast.Program program = Parser.parse("""
                fnc short(int x) {
                  return x + 1;
                }

                fnc long(int x) {
                  val a = x;
                  val b = a;
                  val c = b;
                  val d = c;
                  val e = d;
                  val f = e;
                  val g = f;
                  val h = g;
                  return h;
                }
                """);

        Ast.FunctionDecl shortFn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.FunctionDecl longFn = (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);

        assertEquals("$infer$", shortFn.returnType().name());
        assertTrue(shortFn.sourceLineSpan() < 10);
        assertTrue(longFn.sourceLineSpan() >= 10);
    }

    @Test
    void infersShortCallableReturnFromExhaustiveMatch() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc choose(bool flag) {
                  return match flag {
                    true => 10,
                    false => 20
                  };
                }

                fnc use() => int {
                  return choose(true) + 1;
                }
                """)));
    }

    @Test
    void longUntypedCallableFallsBackToAny() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc intentionally_dynamic(bool flag) {
                  val int a = 1;
                  val int b = 2;
                  val int c = 3;
                  val int d = 4;
                  val int e = 5;
                  val int f = 6;
                  val int g = 7;
                  val int h = 8;
                  return "dynamic";
                }

                fnc use_as_int() => int {
                  return intentionally_dynamic(true);
                }
                """)));
    }

    @Test
    void longAnyFallbackStillRequiresReturnOnEveryPathAndBareReturnsStayVoid() {
        IllegalArgumentException missingReturn = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc dynamic(bool flag) {
                          val a = 1;
                          val b = 2;
                          val c = 3;
                          val d = 4;
                          val e = 5;
                          val f = 6;
                          val g = 7;
                          if flag; do
                            return "value";
                          fi
                        }
                        """)));
        assertTrue(missingReturn.getMessage().contains("must explicitly return on every path"));

        IllegalArgumentException voidUse = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc long_void() {
                          val a = 1;
                          val b = 2;
                          val c = 3;
                          val d = 4;
                          val e = 5;
                          val f = 6;
                          val g = 7;
                          val h = 8;
                          return;
                        }

                        fnc bad_use() => int {
                          return long_void();
                        }
                        """)));
        assertTrue(voidUse.getMessage().contains("return value") || voidUse.getMessage().contains("expected"));
    }

    @Test
    void explicitReturnTypeChecksEveryMatchArm() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc broken(bool flag) => int {
                          return match flag {
                            true => 1,
                            false => "not an int"
                          };
                        }
                        """)));
        assertTrue(error.getMessage().contains("match arm result") || error.getMessage().contains("return value"));
    }

    @Test
    void rejectsNonExhaustiveBooleanAndOptionMatches() {
        IllegalArgumentException boolError = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc broken(bool flag) => int {
                          return match flag {
                            true => 1
                          };
                        }
                        """)));
        assertTrue(boolError.getMessage().contains("non-exhaustive match"));

        IllegalArgumentException optionError = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc broken(Option<int> value) => int {
                          return match value {
                            Some(x) => x
                          };
                        }
                        """)));
        assertTrue(optionError.getMessage().contains("non-exhaustive match"));
    }

    @Test
    void guardedPatternsDoNotCountAsExhaustive() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc broken(bool flag, bool guard) => int {
                          return match flag {
                            true if guard => 1,
                            false => 0
                          };
                        }
                        """)));
        assertTrue(error.getMessage().contains("non-exhaustive match"));
    }

    @Test
    void priorUnguardedPatternMakesGuardedDuplicateUnreachable() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc broken(bool flag, bool guard) => int {
                          return match flag {
                            true => 1,
                            true if guard => 2,
                            false => 0
                          };
                        }
                        """)));
        assertTrue(error.getMessage().contains("redundant") || error.getMessage().contains("unreachable"));
    }

    @Test
    void anyCanFlowThroughForOfAsDynamicEscapeHatch() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc consume(any values) => void {
                  for (val item of values) {
                    print(item);
                  }
                  return;
                }
                """)));
    }

    @Test
    void rejectsArmsAfterCatchAll() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc broken(bool flag) => int {
                          return match flag {
                            _ => 0,
                            true => 1
                          };
                        }
                        """)));
        assertTrue(error.getMessage().contains("redundant") || error.getMessage().contains("unreachable"));
    }

    @Test
    void unknownMustBeNarrowedButAnyIsEscapeHatch() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc bad(unknown value) => int {
                          return value + 1;
                        }
                        """)));
        assertTrue(error.getMessage().contains("numeric") || error.getMessage().contains("unknown"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc dynamic(any value) => int {
                  return value + 1;
                }

                fnc narrowed(unknown value) => int {
                  return match value {
                    n as int => n + 1,
                    _ => 0
                  };
                }
                """)));
    }

    @Test
    void inferredNonVoidCallableStillRequiresExplicitReturnOnEveryPath() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc maybe(bool flag) {
                          if flag; do
                            return 1;
                          fi
                        }
                        """)));
        assertTrue(error.getMessage().contains("must explicitly return on every path"));
    }

    @Test
    void explicitReturnTypeChecksLongMatchEvenWhenInferenceWouldBeSkipped() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc classify(int value) => int {
                          return match value {
                            0 => 0,
                            1 => 1,
                            2 => 2,
                            3 => 3,
                            4 => 4,
                            5 => 5,
                            6 => 6,
                            7 => 7,
                            8 => 8,
                            _ => "wrong"
                          };
                        }
                        """)));
        assertTrue(error.getMessage().contains("match arm result") || error.getMessage().contains("return value"));
    }

    @Test
    void pipeForwardAndPipeIntoMatchExecute() throws Exception {
        String output = run("""
                fnc add(int left, int right) => int {
                  return left + right;
                }

                fnc twice(int value) => int {
                  return value * 2;
                }

                pub routine main() => void {
                  stdio.stdout.write(2 |> add(3) |> twice);
                  stdio.stdout.write(":");
                  stdio.stdout.write(Some(4) |> match {
                    Some(x) => x,
                    None => 0
                  });
                  return;
                }
                """);
        assertEquals("10:4", output);
    }

    @Test
    void finiteCoverageMakesLaterCatchAllUnreachable() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc broken(bool flag) => int {
                          return match flag {
                            true => 1,
                            false => 0,
                            _ => 2
                          };
                        }
                        """)));
        assertTrue(error.getMessage().contains("exhaust") || error.getMessage().contains("unreachable"));
    }

    @Test
    void orPatternsMayBindTheSameNamesButMustAgreeOnBindingSet() throws Exception {
        String output = run("""
                fnc unwrap(Option<int> value) => int {
                  return match value {
                    Some(x) | Some(x) => x,
                    None => 0
                  };
                }

                pub routine main() => void {
                  stdio.stdout.write(unwrap(Some(7)));
                  return;
                }
                """);
        assertEquals("7", output);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc broken(Option<int> value) => int {
                          return match value {
                            Some(x) | Some(y) => 1,
                            None => 0
                          };
                        }
                        """)));
        assertTrue(error.getMessage().contains("same names"));
    }

    @Test
    void numericLiteralPatternsUseNumericValueEquality() throws Exception {
        String output = run("""
                fnc classify(float value) => string {
                  return match value {
                    1 => "one",
                    _ => "other"
                  };
                }

                pub routine main() => void {
                  stdio.stdout.write(classify(1.0));
                  return;
                }
                """);
        assertEquals("one", output);
    }

    @Test
    void shortTypedLambdaInfersItsReturnType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc use() => int {
                  val add_one = |int value| -> {
                    return value + 1;
                  };
                  return add_one(41);
                }
                """)));
    }

    @Test
    void longLambdaFallsBackToAnyButStillRequiresExplicitReturns() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc use() => int {
                  val dynamic = |int value| -> {
                    val a = value;
                    val b = a;
                    val c = b;
                    val d = c;
                    val e = d;
                    val f = e;
                    val g = f;
                    val h = g;
                    return "dynamic";
                  };
                  return dynamic(1);
                }
                """)));

        IllegalArgumentException mixed = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc broken(bool flag) {
                          if flag; do
                            return 1;
                          else
                            return;
                          fi
                        }
                        """)));
        assertTrue(mixed.getMessage().contains("cannot mix") || mixed.getMessage().contains("bare 'return'"));
    }

    @Test
    void genericFunctionCallsSpecializeFromArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc identity<T>(T value) {
                  return value;
                }

                fnc first<T>(T left, T right) => T {
                  return left;
                }

                fnc use_identity() => int {
                  return identity(42);
                }

                fnc use_numeric_join() => float {
                  return first(1, 2.5);
                }
                """)));
    }

    @Test
    void genericHigherOrderCallsCanContextuallyTypeALambda() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc apply<T>(T value, Fnc<T, T> operation) => T {
                  return operation(value);
                }

                fnc use() => int {
                  return apply(41, |value| -> {
                    return value + 1;
                  });
                }
                """)));
    }

    @Test
    void genericConstructorInferenceFlowsIntoFieldAccess() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box<T>
                  pub val T value;
                end

                fnc use() => int {
                  val box = new Box(42);
                  return box.value;
                }

                fnc explicit() => String {
                  val box = new Box<String>("ok");
                  return box.value;
                }
                """)));
    }

    @Test
    void genericInferenceRejectsIrreconcilableArguments() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc same<T>(T left, T right) => T {
                          return left;
                        }

                        fnc broken() => int {
                          return same(1, "x");
                        }
                        """)));
        assertTrue(error.getMessage().contains("cannot infer generic") || error.getMessage().contains("conflicting"));
    }

    @Test
    void noneActsAsBottomSoOptionInferenceStaysPrecise() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc maybe(bool present) {
                  return match present {
                    true => Some(42),
                    false => None
                  };
                }

                fnc explicit_none() => Option<int> {
                  return None;
                }

                fnc use() => Option<int> {
                  return maybe(true);
                }
                """)));
    }

    @Test
    void uninferredGenericResultsBecomeUnknownNotAny() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc phantom<T>(int seed) => T {
                          return seed;
                        }

                        fnc broken() => int {
                          return phantom(1);
                        }
                        """)));
        assertTrue(error.getMessage().contains("Unknown")
                || error.getMessage().contains("unknown")
                || error.getMessage().contains("return value"));
    }

    @Test
    void strAliasAndSingletonStringListsWidenToStableStringTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc accepts_str(str value) => void {
                  return;
                }

                fnc accepts_list(Array<String> values) => void {
                  return;
                }

                fnc use() => void {
                  accepts_str("hello");
                  val values = arr["shared"];
                  accepts_list(values);
                  return;
                }
                """)));
    }

    @Test
    void expectedTypesContextuallyTypeEmptyLists() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc make_empty() => Array<int> {
                  return arr[];
                }

                fnc accept(Array<String> values) => void {
                  return;
                }

                fnc use() => void {
                  val Array<String> values = arr[];
                  accept(arr[]);
                  return;
                }
                """)));
    }

    @Test
    void mutableLetInferenceWidensSingletonStringTypesRecursively() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc use() => void {
                  let name = "first";
                  name = "second";

                  let pair = ("left", "right");
                  pair = ("next", "value");

                  let maybe = Some("one");
                  maybe = Some("two");
                  return;
                }
                """)));
    }

    @Test
    void expectedResultCanInferOtherwiseUnboundGeneric() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc empty_option<T>() => Option<T> {
                  return None;
                }

                fnc as_return() => Option<int> {
                  return empty_option();
                }

                fnc as_binding() => void {
                  val Option<String> value = empty_option();
                  return;
                }
                """)));
    }

    @Test
    void rangeAndOrPatternsComposeWithCatchAll() throws Exception {
        String output = run("""
                fnc bucket(int value) {
                  return match value {
                    0..=9 => 1,
                    10 | 11 => 2,
                    _ => 3
                  };
                }

                pub routine main() => void {
                  stdio.stdout.write(bucket(5));
                  stdio.stdout.write(bucket(10));
                  stdio.stdout.write(bucket(50));
                  return;
                }
                """);
        assertEquals("123", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "inference-match.ores")
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
