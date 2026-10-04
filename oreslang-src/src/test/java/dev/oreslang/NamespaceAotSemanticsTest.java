package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NamespaceAotSemanticsTest {

    @Test
    void defineAndDeclareNamespacesAreFlatTypeOnlyContainers() throws Exception {
        String program = """
                define namespace domain as
                  type UserId = int;

                  define interface Named
                    String name;
                  end

                  define abstract class Entity as
                  end

                  define class Box as
                    val int value;

                    read(): int {
                      return self.value;
                    }
                  end
                end

                declare namespace wire as {
                  type RequestId = String;
                }

                define module app
                  pub fnc main(): void {
                    val domain.UserId id = 42;
                    val domain.Box box = new domain.Box(id);
                    stdio.stdout.write(box.read());
                    return;
                  }
                end
                """;

        Ast.Program checked = TypeChecker.check(Parser.parse(program));
        assertTrue(checked.modules().stream().anyMatch(m -> m.name().equals("domain") && m.isNamespace()));
        assertTrue(checked.modules().stream().anyMatch(m -> m.name().equals("wire") && m.isNamespace()));
        assertTrue(checked.modules().stream().anyMatch(m -> m.name().equals("app") && !m.isNamespace()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "namespace-aot.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void namespacesRejectRuntimeDeclarationsAndNesting() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define namespace bad as
                  fnc run(): void { return; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define namespace bad as
                  val int state = 1;
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define namespace bad as
                  actor Worker {
                    pub receive_message(int value): void { return; }
                  }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define namespace outer as
                  define namespace inner as
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define namespace outer as
                  define module inner
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module outer
                  define namespace inner as
                  end
                end
                """));
    }

    @Test
    void legacyFileNamespaceHeaderIsRejectedAndDeclareIsReserved() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                namespace payments;
                fnc run(): void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc run(): void {
                  val declare = 1;
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                declare module runtime
                end
                """));
    }

    @Test
    void classesAreStaticDeclarationsButInstancesRemainDynamic() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Worker as
                  val int id;
                end

                fnc run(): void {
                  for (val id of arr[1, 2, 3]) {
                    val Worker worker = new Worker(id);
                  }
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc run(): void {
                  define class Local as
                  end
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc run(): void {
                  for (val id of arr[1]) {
                    define class Local as
                    end
                  }
                  return;
                }
                """));
    }

    @Test
    void semanticPassRejectsExecutableNamespaceMembersEvenForProgrammaticAst() {
        Ast.Program parsed = Parser.parse("""
                fnc leaked(): void {
                  return;
                }
                """);
        Ast.FunctionDecl leaked = (Ast.FunctionDecl) parsed.modules().getFirst().declarations().getFirst();
        Ast.Program forged = new Ast.Program(List.of(
                new Ast.ModuleDecl(
                        "types",
                        Ast.ContainerKind.NAMESPACE,
                        List.of(),
                        List.of(leaked))));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(forged));
        assertTrue(failure.getMessage().contains("type-only"));
    }
}
