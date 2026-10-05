package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class AotLocalStructTest {
    @Test
    void directLocalStructMayAppearInCallableReturnTypeBeforeItsDeclaration() throws Exception {
        String program = """
                pub fnc make = || -> R {
                    struct R {
                        foo: string
                        bar: string
                    }

                    return R{foo: "x", bar: "y"}
                }

                pub routine main(): void {
                    val r = make();
                    stdio.stdout.write(r.foo);
                    stdio.stdout.write(r.bar);
                    return;
                }
                """;

        AstAssertions.assertNoDynamicObjectArtifacts(TypeChecker.check(Parser.parse(program)));
        assertEquals("xy", run(program));
    }

    @Test
    void inlineStructTypeAliasMayOmitItsSemicolon() throws Exception {
        String program = """
                pub fnc make = || -> R {
                    type R = struct {
                        foo: string
                        bar: string
                    }
                    return R{foo: "x", bar: "y"}
                }

                pub routine main(): void {
                    val r = make();
                    stdio.stdout.write(r.foo);
                    stdio.stdout.write(r.bar);
                    return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals("xy", run(program));
    }

    @Test
    void localInterfacesAreCompileTimeOnlyDeclarations() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc validate(): void {
                    interface Pair {
                        left: string
                        right: string
                    }

                    struct R {
                        left: string
                        right: string
                    }

                    val R pair = R{left: "l", right: "r"};
                    return;
                }
                """)));
    }

    @Test
    void structShapeIsClosedAndCheckedStatically() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                    struct R { foo: string }
                    val R r = R{foo: "x", extra: "y"};
                    return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                    struct R { foo: string bar: string }
                    val R r = R{foo: "x"};
                    return;
                }
                """)));
    }

    @Test
    void classesModulesAndNamespacesRemainTopLevelOnly() {
        for (String declaration : new String[] {
                "define class Inner as end",
                "define module Inner end",
                "namespace Inner;"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    fnc bad(): void {
                    """ + declaration + """
                      return;
                    }
                    """));
        }
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "local-struct.ores")
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

    private static final class AstAssertions {
        private AstAssertions() {}

        static void assertNoDynamicObjectArtifacts(dev.oreslang.ast.Ast.Program program) {
            for (var module : program.modules()) {
                for (var declaration : module.declarations()) {
                    if (declaration instanceof dev.oreslang.ast.Ast.FunctionDecl fn) {
                        assertFalse(containsObj(fn.body()), "local struct lowering must not emit obj{}");
                    }
                }
            }
        }

        private static boolean containsObj(java.util.List<dev.oreslang.ast.Ast.Stmt> statements) {
            for (var statement : statements) {
                if (statement instanceof dev.oreslang.ast.Ast.ExprStmt expr
                        && expr.expression() instanceof dev.oreslang.ast.Ast.ObjectExpr) return true;
                if (statement instanceof dev.oreslang.ast.Ast.ReturnStmt ret
                        && ret.value() instanceof dev.oreslang.ast.Ast.ObjectExpr) return true;
            }
            return false;
        }
    }
}
