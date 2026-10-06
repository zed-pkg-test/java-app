package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TupleSyntaxTest {
    @Test
    void explicitAndParenthesizedTupleReturnTypesInteroperate() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc explicit(): Tuple[string, int] {
                  return tuple ("foo", 5);
                }

                fnc shorthand(): (string, int) {
                  return explicit();
                }

                fnc explicitAgain(): Tuple[string, int] {
                  return shorthand();
                }

                pub fnc main(): void {
                  val t = explicitAgain();
                  stdio.println(t[0]);
                  stdio.println(t[1]);
                  return;
                }
                """)));
    }

    @Test
    void parenthesizedDestructureSupportsInferenceAndPerSlotTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc foo(): Tuple[string, int] {
                  return tuple ("foo", 5);
                }

                pub fnc main(): void {
                  const (k, v) = foo();
                  const (string k2, int v2) = foo();
                  const (k3: string, v3: int) = foo();
                  stdio.println(k);
                  stdio.println(v);
                  stdio.println(k2);
                  stdio.println(v2);
                  stdio.println(k3);
                  stdio.println(v3);
                  return;
                }
                """)));
    }

    @Test
    void typedTupleDestructureIsPositionalAndRejectsSwappedTypes() {
        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc foo(): Tuple[string, int] {
                          return tuple ("foo", 5);
                        }

                        pub fnc main(): void {
                          const (int k, string v) = foo();
                          return;
                        }
                        """)));
        assertTrue(mismatch.getMessage().contains("destructure binding"));
    }

    @Test
    void countedOfGroupingIsEquivalentInsideTupleShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc grouped(): Tuple[(1 of string, 1 of int)] {
                  return tuple ("foo", 5);
                }

                fnc flat(): Tuple[1 of string, 1 of int] {
                  return grouped();
                }

                fnc roundTrip(): Tuple[(1 of string, 1 of int)] {
                  return flat();
                }

                pub fnc main(): void {
                  val x = roundTrip();
                  stdio.println(x[0]);
                  stdio.println(x[1]);
                  return;
                }
                """)));
    }

    @Test
    void parenthesizedTupleElementInsideTupleShapeRemainsNested() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc nested(): Tuple[(string, int), bool] {
                  return tuple (tuple ("foo", 5), true);
                }

                pub fnc main(): void {
                  val n: int = nested()[0][1];
                  val b: bool = nested()[1];
                  stdio.println(n);
                  stdio.println(b);
                  return;
                }
                """)));
    }

    @Test
    void explicitTupleConstructorCanInferOrUseAnExplicitShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc inferred(): Tuple[string, int] {
                  return new Tuple("foo", 5);
                }

                fnc explicit(): (string, int) {
                  return new Tuple[string, int]("bar", 6);
                }

                fnc consume(Tuple[string, int] t): int {
                  return t[1];
                }

                pub fnc main(): void {
                  val a: int = consume(tuple ("inline", 7));
                  val b: int = consume(new Tuple("explicit", 8));
                  stdio.println(a);
                  stdio.println(b);
                  return;
                }
                """)));
    }

    @Test
    void tupleConstantIndexingPreservesNestedSlotTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc nested(): Tuple[Tuple[string, int], Tuple[bool, string]] {
                  return tuple (tuple ("foo", 5), tuple (true, "ok"));
                }

                pub fnc main(): void {
                  val n: int = nested()[0][1];
                  val s: string = nested()[1][1];
                  stdio.println(n);
                  stdio.println(s);
                  return;
                }
                """)));

        IllegalArgumentException bounds = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc pair(): Tuple[string, int] {
                          return tuple ("foo", 5);
                        }

                        pub fnc main(): void {
                          val x = pair()[2];
                          return;
                        }
                        """)));
        assertTrue(bounds.getMessage().contains("out of bounds"));
    }

    @Test
    void singletonTupleRequiresExplicitTupleSyntaxForNow() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc one(): Tuple[string] {
                  return new Tuple("one");
                }

                fnc oneKeyword(): Tuple[string] {
                  return tuple ("keyword");
                }

                pub fnc main(): void {
                  val one: Tuple[string] = new Tuple("one");
                  val two: Tuple[string] = oneKeyword();
                  stdio.println(one[0]);
                  stdio.println(two[0]);
                  return;
                }
                """)));

        IllegalArgumentException shorthand = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(): (string,) {
                          return new Tuple("one");
                        }
                        """));
        assertTrue(shorthand.getMessage().contains("singleton tuple type shorthand"));

        IllegalArgumentException destructure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc bad(): void {
                          const (value) = new Tuple("one");
                          return;
                        }
                        """));
        assertTrue(destructure.getMessage().contains("singleton parenthesized destructuring"));
    }

    @Test
    void tupleBracketPseudoConstructionAndEmptyConstructorAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main(): void {
                  val bad = Tuple[][5, "foo"];
                  return;
                }
                """));

        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val bad = new Tuple();
                          return;
                        }
                        """)));
        assertTrue(empty.getMessage().contains("at least one element"));
    }

    @Test
    void tupleConstructionDestructureAndNestedIndexingExecute() throws Exception {
        String program = """
                fnc foo(): Tuple[string, int] {
                  return new Tuple("foo", 5);
                }

                fnc nested(): (Tuple[string, int], Tuple[bool, string]) {
                  return tuple (foo(), new Tuple(true, "ok"));
                }

                pub fnc main(): void {
                  const (string k, int v) = foo();
                  val n: int = nested()[0][1];
                  val s: string = nested()[1][1];
                  stdio.println(k);
                  stdio.println(v);
                  stdio.println(n);
                  stdio.println(s);
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "tuple-syntax.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("foo"));
        assertTrue(text.contains("5"));
        assertTrue(text.contains("ok"));
    }
    @Test
    void tupleKeywordIsRequiredForTupleValues() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc pair(): Tuple[string, int] {
                  return tuple ("foo", 5);
                }

                pub fnc main(): void {
                  val x = tuple ("bar", 6);
                  const (k, v) = pair();
                  stdio.println(x[0]);
                  stdio.println(k);
                  stdio.println(v);
                  return;
                }
                """)));

        IllegalArgumentException bare = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc main(): void {
                          val bad = ("foo", 5);
                          return;
                        }
                        """));
        assertTrue(bare.getMessage().contains("use tuple"));
    }

    @Test
    void parenthesizedTypeAliasIsATupleType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type X = (int, string, bool);

                fnc echo(X value): X {
                  return value;
                }

                pub fnc main(): void {
                  val x: X = tuple (5, "ok", true);
                  const (n, s, b) = echo(x);
                  stdio.println(n);
                  stdio.println(s);
                  stdio.println(b);
                  return;
                }
                """)));
    }

    @Test
    void tupleIsAFinalBuiltinValueClass() {
        IllegalArgumentException inheritance = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class BadTuple extends Tuple[int, string] as
                        end
                        """)));
        assertTrue(inheritance.getMessage().contains("final built-in class 'Tuple'"));

        IllegalArgumentException redefine = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Tuple as
                        end
                        """)));
        assertTrue(redefine.getMessage().contains("final built-in value class"));
    }

    @Test
    void sequenceIsEqualsToSharesRecursiveValueEquality() throws Exception {
        String program = """
                pub fnc main(): void {
                  val a = tuple ("x", tuple (1, true));
                  val b = tuple ("x", tuple (1, true));
                  val c = tuple ("x", tuple (2, true));
                  val xs = arr [1, 2, 3];
                  val ys = arr [1, 2, 3];
                  val zs = arr [1, 2, 4];

                  stdio.println(a.isEqualsTo(b));
                  stdio.println(a.isEqualsTo(c));
                  stdio.println(a eq b);
                  stdio.println(xs.isEqualsTo(ys));
                  stdio.println(xs.isEqualsTo(zs));
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "tuple-equality.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(text.contains("true\nfalse\ntrue\ntrue\nfalse"));
    }

    @Test
    void tupleKeywordAndNewTupleShareValueSemantics() throws Exception {
        String program = """
                pub fnc main(): void {
                  val keyword = tuple ("same", 9);
                  val constructor = new Tuple("same", 9);
                  stdio.println(keyword.isEqualsTo(constructor));
                  stdio.println(keyword eq constructor);
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "tuple-constructor-equivalence.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(text.contains("true\ntrue"));
    }

    @Test
    void oneElementTupleTypeAndValueStayDistinctFromGrouping() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type One = (int,);

                fnc one(): One {
                  return tuple (5);
                }

                fnc grouped(): (int) {
                  return 5;
                }

                pub fnc main(): void {
                  const (n) = one();
                  val plain: int = grouped();
                  stdio.println(n);
                  stdio.println(plain);
                  return;
                }
                """)));
    }

}
