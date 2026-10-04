package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ArityOverloadAotTest {

    private static final String PROGRAM = """
            pub interface Picker {
              fnc pick() => String;
              fnc pick(int x) => String;
              fnc pick(int x, int y) => String;
            }

            define class Base implements Picker as
              pub pick() => String {
                return "base0";
              }

              pub pick(int x) => String {
                return "base1";
              }

              pub pick(int x, int y) => String {
                return "base2";
              }

              pub same() => String {
                return "instance";
              }

              pub static fnc same() => String {
                return "static";
              }

              pub static fnc choose() => String {
                return "static0";
              }

              pub static fnc choose(int x) => String {
                return "static1";
              }
            end

            define class Child extends Base as
              pub pick(int x) => String {
                return "child1";
              }
            end

            pub routine main() => void {
              val base = new Base();
              val child = new Child();

              stdio.println(base.pick());
              stdio.println(base.pick(1));
              stdio.println(base.pick(1, 2));
              stdio.println(base.same());
              stdio.println(Base.same());
              stdio.println(Base.choose());
              stdio.println(Base.choose(1));

              stdio.println(child.pick());
              stdio.println(child.pick(1));
              stdio.println(child.pick(1, 2));
              stdio.println(Child.choose());
              stdio.println(Child.choose(1));
              return;
            }
            """;

    @Test
    void classInterfaceAndStaticOverloadsResolveOnlyByArity() throws Exception {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(PROGRAM)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, PROGRAM, "arity-overload.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(out)
                .build()) {
            context.eval(source);
        }

        assertEquals("""
                base0
                base1
                base2
                instance
                static
                static0
                static1
                base0
                child1
                base2
                static0
                static1
                """, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void sameNameAndArityCannotOverloadByParameterType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Bad as
                          pub pick(int value) => int {
                            return value;
                          }

                          pub pick(String value) => String {
                            return value;
                          }
                        end
                        """)));

        assertTrue(failure.getMessage().contains("already has arity 1"));
        assertTrue(failure.getMessage().contains("overload only by arity"));
    }

    @Test
    void genericArityDoesNotCreateAnotherOverloadSlot() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Bad as
                          pub pick<T>(T value) => T {
                            return value;
                          }

                          pub pick<U, V>(U value) => U {
                            return value;
                          }
                        end
                        """)));

        assertTrue(failure.getMessage().contains("already has arity 1"));
    }

    @Test
    void multipleInheritanceRequiresExplicitResolutionForSameAritySlot() {
        IllegalArgumentException ambiguous = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Left as
                          pub ping() => int { return 1; }
                        end

                        define class Right as
                          pub ping() => int { return 2; }
                        end

                        define class Ambiguous extends Left, Right as
                        end
                        """)));

        assertTrue(ambiguous.getMessage().contains("ambiguous inherited method slot"));
        assertTrue(ambiguous.getMessage().contains("arity 0"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Left as
                  pub ping() => int { return 1; }
                end

                define class Right as
                  pub ping() => int { return 2; }
                end

                define class Resolved extends Left, Right as
                  pub ping() => int { return 3; }
                end
                """)));
    }

    @Test
    void ordinaryDiamondInheritanceOfSameDeclarationKeepsOneSlot() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Root as
                  pub ping() => int { return 1; }
                end

                define class Left extends Root as
                end

                define class Right extends Root as
                end

                define class Diamond extends Left, Right as
                end
                """)));
    }

    @Test
    void overloadedMethodValueMustBeCalledDirectlySoArityIsKnown() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub pick() => int { return 0; }
                          pub pick(int value) => int { return value; }
                        end

                        pub fnc bad() => void {
                          val box = new Box();
                          val callback = box.pick;
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("must be called so arity can select the overload"));
    }
}
