package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorDeclarationLexicalBoundaryTest {

    @Test
    void defineActorAsParsesAtFileAndModuleScope() {
        Ast.Program program = Parser.parse("""
                define actor Worker as
                  pub receive(): void {
                    rt cooperate;
                    return;
                  }
                end

                define module services as
                  define isoactor Isolated as
                    pub process(): void { return; }
                  end

                  define untrusted actor Sandboxed as
                    pub run(): void { return; }
                  end
                end
                """);

        Ast.ClassDecl worker = (Ast.ClassDecl) program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl klass
                        && klass.name().equals("Worker"))
                .findFirst().orElseThrow();
        assertEquals(Ast.ActorKind.SHARED, worker.actorKind());

        Ast.ModuleDecl services = program.modules().stream()
                .filter(module -> module.name().equals("services"))
                .findFirst().orElseThrow();
        assertEquals(Ast.ActorKind.PRIVATE,
                ((Ast.ClassDecl) services.declarations().get(0)).actorKind());
        assertEquals(Ast.ActorKind.UNTRUSTED,
                ((Ast.ClassDecl) services.declarations().get(1)).actorKind());
    }

    @Test
    void defineActorStillForbidsConstructorsUntilRuntimeStartupLoweringExists() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define actor Worker as
                          pub constructor() {}
                        end
                        """));
        assertTrue(failure.getMessage().contains("actors do not declare constructors"));
    }

    @Test
    void actorCannotCallEnclosingMainImplicitly() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub receive(): void {
                            main();
                            return;
                          }
                        end

                        pub routine main(): void { return; }
                        """)));
        assertTrue(failure.getMessage().contains("cannot implicitly access"), failure.getMessage());
    }

    @Test
    void OrdinaryClassCannotReadFileLevelCallableAsAmbientClosure() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc helper(): int { return 42; }

                        define class Box as
                          pub calculate(): int {
                            return helper();
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("cannot implicitly access"), failure.getMessage());
    }

    @Test
    void CapturingUnrelatedFunctionValueFromClassIsRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc helper(): int { return 42; }

                        define class Box as
                          pub consume(): void {
                            val callback = helper;
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("cannot implicitly access"), failure.getMessage());
    }

    @Test
    void ActorMethodMayStillUseItsOwnMethodsAndBuiltins() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  pub ping(): void {
                    rt cooperate;
                    stdio.println("ping");
                    return;
                  }

                  pub run(): void {
                    self.ping();
                    return;
                  }
                end
                """)));
    }
}
