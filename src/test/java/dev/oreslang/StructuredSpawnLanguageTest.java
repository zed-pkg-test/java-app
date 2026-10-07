package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class StructuredSpawnLanguageTest {
    @Test void canonicalDeclarationsAndLegacyAliasResolveTheSameKinds() {
        Ast.Program program = Parser.parse("""
                define actor DefaultWorker as end
                define shared actor SharedWorker as end
                define isolated actor PrivateWorker as end
                define untrusted actor UntrustedWorker as end
                isoactor LegacyWorker { }
                """);
        assertEquals(List.of(Ast.ActorKind.SHARED, Ast.ActorKind.SHARED,
                        Ast.ActorKind.PRIVATE, Ast.ActorKind.UNTRUSTED, Ast.ActorKind.PRIVATE),
                program.modules().getFirst().declarations().stream()
                        .map(d -> ((Ast.ClassDecl)d).actorKind()).toList());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module workers as
                  pub define isolated actor Worker as
                    pub fnc value(): int { return 42; }
                  end
                end
                """)));
    }

    @Test void conflictingKindsAndDetachedModifiersAreRejected() {
        for (String declaration : List.of("define shared isolated actor X as end",
                "define untrusted isolated actor X as end", "define shared untrusted actor X as end",
                "define isolated X as end", "define isolated isolated actor X as end",
                "generator actor X {}", "untrusted define class X as end")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(declaration), declaration);
        }
    }

    @Test void spawnRejectsOrdinaryClassesAndNewRejectsActors() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Worker as end
                pub routine main(): void { val worker = spawn Worker(); return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Worker as end
                pub routine main(): void { val worker = new Worker(); return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void { val worker = spawn Missing(); return; }
                """)));
    }

    @Test void actorStatePersistsAcrossMailboxRequestsForAllKinds() throws Exception {
        for (String kind : List.of("actor", "shared actor", "isolated actor", "untrusted actor")) {
            assertEquals("42:43", run("""
                    define %s Counter as
                      let int value = 0;
                      pub fnc next(): int { self.value = self.value + 1; return self.value; }
                    end
                    pub routine main(): void {
                      val worker = spawn Counter(41);
                      stdio.stdout.write(await worker.next());
                      stdio.stdout.write(":");
                      stdio.stdout.write(await worker.next());
                      return;
                    }
                    """.formatted(kind)), kind);
        }
    }

    @Test void spawnAndMethodArgumentsCannotCarryLocalMutexes() {
        for (String body : List.of("val worker = spawn Worker(Mutex.new(1));",
                "val worker = spawn Worker(); val result = worker.accept(Mutex.new(1));")) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                    define isolated actor Worker as
                      let Mutex<int> state = Mutex.new(0);
                      pub fnc accept(Mutex<int> value): int { return 1; }
                    end
                    pub routine main(): void { %s return; }
                    """.formatted(body))));
        }
    }

    @Test void spawnRequiresCapabilityEvenForEmptyActor() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define actor Worker as end
                pub routine main(): void { val worker = spawn Worker(); return; }
                """));
        IsolatePolicy policy = IsolatePolicy.developer().withoutCapabilities(IsolatePolicy.Capability.ACTOR_SPAWN);
        assertThrows(SecurityException.class, () -> CapabilityChecker.check(program, policy));
    }

    @Test void nestedSourceSpawnsResumeThroughTheParentsMailbox() throws Exception {
        assertEquals("42", run("""
                define isolated actor Worker as
                  pub fnc value(): int { return 42; }
                end
                define actor Parent as
                  pub fnc start(): int {
                    val worker = spawn Worker();
                    return await worker.value();
                  }
                end
                pub routine main(): void {
                  val parent = spawn Parent();
                  stdio.stdout.write(await parent.start());
                  return;
                }
                """));
    }

    @Test void buildOptimizerPreservesSpawnAndItsActorDeclaration() {
        var result = dev.oreslang.compiler.OresCompiler.compileForBuild("""
                define isolated actor Worker as
                  pub fnc value(): int { return 42; }
                end
                pub routine main(): void {
                  val worker = spawn Worker();
                  stdio.stdout.write(await worker.value());
                  return;
                }
                """, dev.oreslang.compiler.BuildOptions.executable());
        Ast.FunctionDecl main = result.program().modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance).map(Ast.FunctionDecl.class::cast)
                .filter(f -> f.name().equals("main")).findFirst().orElseThrow();
        Ast.NewExpr spawn = (Ast.NewExpr)((Ast.BindingStmt)main.body().getFirst()).initializer();
        assertTrue(spawn.actorSpawn());
        assertTrue(result.program().modules().getFirst().declarations().stream()
                .anyMatch(d -> d instanceof Ast.ClassDecl c && c.name().equals("Worker")));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "structured-spawn.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
