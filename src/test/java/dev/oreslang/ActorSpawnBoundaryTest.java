package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSpawnBoundaryTest {

    @Test
    void spawnIsReservedAndParsesAsDedicatedActorBoundary() {
        assertEquals(Token.Type.SPAWN, new Lexer("spawn").scan().getFirst().type());

        Ast.Program program = Parser.parse("""
                actor Worker {
                  let int value = 1;
                }

                pub routine main(): void {
                  val worker = spawn Worker();
                  return;
                }
                """);

        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        Ast.BindingStmt binding = (Ast.BindingStmt) main.body().getFirst();
        assertInstanceOf(Ast.SpawnExpr.class, binding.initializer());
        Ast.SpawnExpr spawned = (Ast.SpawnExpr) binding.initializer();
        assertInstanceOf(Ast.NameExpr.class, spawned.call().callee());
        assertEquals("Worker", ((Ast.NameExpr) spawned.call().callee()).name());
    }

    @Test
    void typeCheckerAcceptsActorClassSpawnButRejectsOrdinaryCallableSpawn() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor Worker {
                  let int value = 1;
                }

                pub routine main(): void {
                  val worker = spawn Worker();
                  return;
                }
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc Worker(): void { return; }

                        pub routine main(): void {
                          val worker = spawn Worker();
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("not a known actor class"));
    }

    @Test
    void spawnArgumentsFailClosedUntilActorInitializerSemanticsAreConverged() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor Worker {
                          let int value = 1;
                        }

                        pub routine main(): void {
                          val worker = spawn Worker(42);
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("spawn arguments are not enabled"));
    }

    @Test
    void sharedActorClassOwnsPersistentStateAndProtocolCallsReturnFutures() throws Exception {
        String program = """
                actor Counter {
                  let int value = 0;

                  pub next(): int {
                    self.value = self.value + 1;
                    return self.value;
                  }
                }

                pub async fnc main(): void {
                  val counter = spawn Counter();
                  val Future<int> first = counter.next();
                  val Future<int> second = counter.next();
                  stdio.println(await first);
                  stdio.println(await second);
                  return;
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "actor-spawn-mailbox.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            assertDoesNotThrow(() -> context.eval(source));
        }

        assertEquals(
                "1\n2",
                output.toString(java.nio.charset.StandardCharsets.UTF_8).strip());
    }

    @Test
    void suspendingProtocolMethodResumesThroughActorMailboxAndKeepsStateOwned() throws Exception {
        String program = """
                async fnc bounce(int value): int {
                  return value;
                }

                actor Counter {
                  let int value = 0;

                  pub add_after(int amount): int {
                    val int delta = await bounce(amount);
                    self.value = self.value + delta;
                    return self.value;
                  }
                }

                pub async fnc main(): void {
                  val counter = spawn Counter();
                  val Future<int> first = counter.add_after(2);
                  val Future<int> second = counter.add_after(3);
                  stdio.println(await first);
                  stdio.println(await second);
                  return;
                }
                """;

        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "actor-protocol-suspension.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        java.io.ByteArrayOutputStream output =
                new java.io.ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            assertDoesNotThrow(() -> context.eval(source));
        }

        assertEquals(
                "2\n5",
                output.toString(
                                java.nio.charset.StandardCharsets.UTF_8)
                        .strip());
    }

    @Test
    void privateActorObjectLoweringStillFailsClosedAtRuntime() throws Exception {
        String program = """
                isoactor Worker {
                  let int value = 1;

                  pub current(): int {
                    return self.value;
                  }
                }

                pub routine main(): void {
                  val worker = spawn Worker();
                  return;
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "private-actor-spawn.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        RuntimeException failure;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            failure = assertThrows(RuntimeException.class, () -> context.eval(source));
        }

        assertTrue(failure.getMessage().contains("supports SHARED actors only"));
    }
}
