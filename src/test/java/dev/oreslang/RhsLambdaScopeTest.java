package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for right-hand-side lambda declarations and nlex boundaries. */
final class RhsLambdaScopeTest {
    private static void check(String source) {
        Ast.Program program = TypeChecker.check(Parser.parse(source));
        OwnershipChecker.check(program);
    }

    @Test
    void typedBlockLambdasPreserveDeclaredReturnType() {
        Ast.Program program = Parser.parse("""
                pub fnc example(): void {
                    const create = || -> int {
                        return 1;
                    };
                    const nested = |int value| -> Tuple[int, int] {
                        return tuple (value, value);
                    };
                    return;
                }
                """);
        Ast.FunctionDecl function =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.LambdaExpr create =
                (Ast.LambdaExpr) ((Ast.BindingStmt) function.body().get(0)).initializer();
        Ast.LambdaExpr nested =
                (Ast.LambdaExpr) ((Ast.BindingStmt) function.body().get(1)).initializer();
        assertEquals("int", create.returnType().name());
        assertEquals("Tuple", nested.returnType().name());
        assertEquals(2, nested.returnType().arguments().size());
    }

    @Test
    void ordinaryLambdaCanCaptureLocalsWithinNlexCallable() {
        assertDoesNotThrow(() -> check("""
                pub nlex fnc foo(): int {
                    const xyz = 5;
                    const f = || -> int {
                        return xyz;
                    };
                    return f();
                }
                """));
    }

    @Test
    void nestedExplicitNlexLambdaCannotCaptureContainingLocal() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                pub nlex fnc foo(): int {
                    const xyz = 5;
                    const f = nlex || -> int {
                        return xyz;
                    };
                    return f();
                }
                """));
    }

    @Test
    void typedReturnMustMatchAndExplicitlyReturnFromBlock() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                pub fnc example(): void {
                    const f = || -> int {
                        return "not an int";
                    };
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> check("""
                pub fnc example(): void {
                    const f = || -> int {
                        const v = 1;
                    };
                }
                """));
    }

    @Test
    void expressionBodiedLambdasRemainUnchanged() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc example(): void {
                    const f = |value| -> value + 1;
                    const g = |value| ->
                        value + 2;
                }
                """));
    }

    @Test
    void asyncRhsLambdasHaveFutureResultsAndMayAwait() {
        assertDoesNotThrow(() -> check("""
                pub async fnc answer(): int {
                    return 42;
                }
                pub fnc example(): void {
                    const create = async || -> int {
                        return await answer();
                    };
                    val result = await create();
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc example(): void {
                    const create = async || -> int {
                        return await 1;
                    };
                }
                """));
    }

    @Test
    void asyncAndNlexOrderIsIndependentAndDuplicatesFail() {
        for (String modifiers : new String[] {"async nlex", "nlex async"}) {
            Ast.Program program = Parser.parse("""
                    pub fnc foo(): void {
                        const f = %s || -> int {
                            return 1;
                        };
                    }
                    """.formatted(modifiers));
            Ast.FunctionDecl outer =
                    (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
            Ast.LambdaExpr lambda =
                    (Ast.LambdaExpr) ((Ast.BindingStmt) outer.body().getFirst()).initializer();
            assertTrue(lambda.async());
            assertTrue(lambda.nonLexical());
        }
        for (String modifiers : new String[] {"async async", "nlex nlex"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    pub fnc foo(): void {
                        const f = %s || -> int {
                            return 1;
                        };
                    }
                    """.formatted(modifiers)));
        }
    }

    @Test
    void asyncLambdaAndNlexInnerCaptureExecute() throws Exception {
        String program = """
                pub async fnc answer(): int {
                    return 42;
                }
                pub nlex fnc captured(): int {
                    const xyz = 5;
                    const f = || -> int {
                        return xyz;
                    };
                    return f();
                }
                pub fnc main(): void {
                    const create = async || -> int {
                        return await answer();
                    };
                    stdio.stdout.write(await create());
                    stdio.stdout.write(captured());
                    return;
                }
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "rhs-lambda-async.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("425"));
    }

    @Test
    void nestedOrdinaryClosureCannotTunnelThroughNlexCaptureBarrier() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                pub fnc outer(): int {
                    const outside = 5;
                    const inner = nlex || -> int {
                        const child = || -> int {
                            return outside;
                        };
                        return child();
                    };
                    return inner();
                }
                """));
        assertDoesNotThrow(() -> check("""
                pub fnc outer(): int {
                    const outside = 5;
                    const inner = nlex || -> int {
                        const inside = 6;
                        const child = || -> int {
                            return inside;
                        };
                        return child();
                    };
                    return inner();
                }
                """));
    }

    @Test
    void nlexCallableCannotResolveOuterFunctionsOrModuleState() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                pub fnc bar(): int {
                    return 7;
                }
                pub nlex fnc foo(): int {
                    const xyz = 5;
                    const f = || -> int {
                        return xyz + bar();
                    };
                    return f();
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> check("""
                const outside = 9;
                pub nlex fnc foo(): int {
                    const xyz = 5;
                    const f = || -> int {
                        return xyz + outside;
                    };
                    return f();
                }
                """));
    }

    @Test
    void ordinaryNestedLambdaInsideNlexMayOnlyCaptureInsideBoundary() {
        assertDoesNotThrow(() -> check("""
                pub nlex fnc foo(): int {
                    const xyz = 5;
                    const Fnc<int> f = || -> int {
                        return xyz;
                    };
                    return f();
                }
                """));
    }

    @Test
    void pureTrapAndNlexRhsModifiersAreFirstClassAndOrderIndependent() {
        for (String modifiers : new String[] {
                "pure",
                "trap",
                "nlex pure trap",
                "trap pure nlex",
                "pure nlex trap"
        }) {
            Ast.Program program = Parser.parse("""
                    pub fnc foo(): void {
                        const f = %s || -> int {
                            return 1;
                        };
                    }
                    """.formatted(modifiers));
            Ast.FunctionDecl outer =
                    (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
            Ast.LambdaExpr lambda =
                    (Ast.LambdaExpr) ((Ast.BindingStmt) outer.body().getFirst()).initializer();
            assertEquals(modifiers.contains("pure"), lambda.pure());
            assertEquals(modifiers.contains("trap"), lambda.trapped());
            assertEquals(modifiers.contains("nlex"), lambda.nonLexical());
        }

        for (String modifiers : new String[] {"pure pure", "trap trap"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    pub fnc foo(): void {
                        const f = %s || -> int {
                            return 1;
                        };
                    }
                    """.formatted(modifiers)));
        }
    }

    @Test
    void asyncPureRhsIsAcceptedButAsyncTrapFailsClosed() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc foo(): void {
                    const f = pure async || -> int {
                        return 1;
                    };
                }
                """));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc foo(): void {
                            const f = nlex pure trap async || -> int {
                                return 1;
                            };
                        }
                        """));
        assertTrue(error.getMessage().contains("async trap"), error.getMessage());
    }

    @Test
    void trapRhsLambdaUsesOptionSemanticsAtRuntime() throws Exception {
        String program = """
                pub fnc main(): void {
                    const good = trap || -> int {
                        return 7;
                    };
                    const bad = trap || -> int {
                        return [1][3];
                    };
                    stdio.stdout.write(good().unwrap());
                    stdio.stdout.write(":");
                    stdio.stdout.write(bad().is_none());
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "rhs-lambda-trap.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        assertEquals("7:true", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void pureRhsLambdaRejectsCapturedWritesButAllowsOwnedLocals() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                pub fnc outer(): void {
                    let outside = 1;
                    const f = pure || -> void {
                        outside = 2;
                        return;
                    };
                    return;
                }
                """));

        assertDoesNotThrow(() -> check("""
                pub fnc outer(): void {
                    const f = pure || -> int {
                        let inside = 1;
                        inside = inside + 1;
                        return inside;
                    };
                    return;
                }
                """));
    }

}
