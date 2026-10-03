package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MultipleInheritanceTest {
    @Test
    void multipleClassInheritanceProducesGuidanceWarning() {
        TypeChecker.CheckResult result = TypeChecker.checkWithDiagnostics(Parser.parse("""
                define module model as
                  define class A as
                    pub a() => int { return 1; }
                  end
                  define class B as
                    pub b() => int { return 2; }
                  end
                  define class C extends A, B as
                  end
                end
                """));

        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals("ORES-MI-001")
                        && diagnostic.message().contains("interfaces")
                        && diagnostic.message().contains("traits")));
    }

    @Test
    void siblingParentMethodCollisionRequiresSubclassOverride() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define class A as
                            pub foo() => int { return 1; }
                          end
                          define class B as
                            pub foo() => int { return 2; }
                          end
                          define class C extends A, B as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited method"));
        assertTrue(error.getMessage().contains("must override"));
    }

    @Test
    void diamondThroughSharedGrandparentStillRequiresOverride() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define class Base as
                            pub foo() => int { return 1; }
                          end
                          define class Left extends Base as
                          end
                          define class Right extends Base as
                          end
                          define class Diamond extends Left, Right as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited method"));
        assertTrue(error.getMessage().contains("Diamond"));
    }

    @Test
    void explicitOverrideMayDelegateToEachDirectParent() throws Exception {
        String program = """
                define module app as
                  define class A as
                    pub foo() => int { return 1; }
                  end

                  define class B as
                    pub foo() => int { return 2; }
                  end

                  define class C extends A, B as
                    pub foo() => int {
                      return super.A.foo() + super.B.foo();
                    }
                  end

                  pub fnc main() => void {
                    val c = new C();
                    stdio.println(c.foo());
                    return;
                  }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "multiple-inheritance-super.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertEquals("3", output.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void unqualifiedSuperIsAllowedOnlyWithOneDirectParent() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define class A as
                    pub foo() => int { return 1; }
                  end
                  define class B extends A as
                    pub foo() => int { return super.foo(); }
                  end
                end
                """)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define class A as
                            pub foo() => int { return 1; }
                          end
                          define class B as
                            pub foo() => int { return 2; }
                          end
                          define class C extends A, B as
                            pub foo() => int { return super.foo(); }
                          end
                        end
                        """)));
        assertTrue(error.getMessage().contains("unqualified super is ambiguous"));
    }

    @Test
    void qualifiedSuperMustNameADirectParent() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define class Base as
                            pub foo() => int { return 1; }
                          end
                          define class Left extends Base as
                          end
                          define class Right extends Base as
                          end
                          define class Diamond extends Left, Right as
                            pub foo() => int { return super.Base.foo(); }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("must name exactly one direct parent"));
    }

    @Test
    void overrideMustBeCompatibleWithEveryParentBranch() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define class A as
                            pub foo(int value) => int { return value; }
                          end
                          define class B as
                            pub foo(int value) => String { return "b"; }
                          end
                          define class C extends A, B as
                            pub foo(int value) => int { return value; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("incompatible with inherited method"));
        assertTrue(error.getMessage().contains("B"));
    }

    @Test
    void inheritedStateCollisionIsNeverResolvedByParentOrder() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define class A as
                            pub val int id = 1;
                          end
                          define class B as
                            pub val int id = 2;
                          end
                          define class C extends A, B as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited state field"));
        assertTrue(error.getMessage().contains("parent order"));
    }
}
