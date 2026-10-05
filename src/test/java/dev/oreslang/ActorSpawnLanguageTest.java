package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSpawnLanguageTest {

    @Test
    void parsesSpawnAsDedicatedExpression() {
        Ast.Program program = Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }

                pub async routine main() => void {
                  val string pending = spawn worker(1);
                  val ready = await spawn worker(2);
                  val string ready_id = ready.id;
                  return;
                }
                """);

        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        Ast.BindingStmt pending = (Ast.BindingStmt) main.body().getFirst();
        assertInstanceOf(Ast.SpawnExpr.class, pending.initializer());

        Ast.BindingStmt ready = (Ast.BindingStmt) main.body().get(1);
        Ast.AwaitExpr awaited = assertInstanceOf(Ast.AwaitExpr.class, ready.initializer());
        assertInstanceOf(Ast.SpawnExpr.class, awaited.expression());

        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void actorCallableRequiresSpawn() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc worker(int value) => int {
                          return value;
                        }

                        pub routine main() => void {
                          val nope = worker(1);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("spawn"));
    }

    @Test
    void spawnRejectsOrdinaryCallable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc ordinary(int value) => int {
                          return value;
                        }

                        pub routine main() => void {
                          val nope = spawn ordinary(1);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("not declared with the actor keyword"));
    }

    @Test
    void voidStartedActorDoesNotExposeResultFuture() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor routine worker() => void {
                          return;
                        }

                        pub async routine main() => void {
                          val started = await spawn worker();
                          val nope = started.result;
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("void actor callable has no result"));
    }

    @Test
    void awaitedSpawnExposesStartedIdentityControlOnly() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }

                pub async routine main() => void {
                  val started = await spawn worker(1);
                  val id = started.id;
                  val alive = started.is_alive();
                  val answer = await started.result;
                  return;
                }
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc worker(int value) => int {
                          return value;
                        }

                        pub async routine main() => void {
                          val started = await spawn worker(1);
                          val denied = started.mailbox;
                          return;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("StartedActor")
                        || failure.getMessage().contains("identity/lifecycle/completion"));
    }

    @Test
    void plainSpawnStringIsNotAwaitableStartupAuthority() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc worker() => int {
                          return 1;
                        }

                        pub async routine main() => void {
                          val id = spawn worker();
                          val denied = await id;
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("Awaitable"));
    }

    @Test
    void nonVoidAwaitedSpawnExposesResultFuture() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor routine compute(int value) => int {
                  return value + 1;
                }

                pub async routine main() => void {
                  val started = await spawn compute(41);
                  val answer = await started.result;
                  return;
                }
                """)));
    }

    @Test
    void plainSpawnReturnsIdAndAwaitedSpawnReturnsStartedControl() throws Exception {
        String program = """
                pub actor fnc add_one(int value) => int {
                  return value + 1;
                }

                pub async routine main() => void {
                  val string id = spawn add_one(1);
                  val started = await spawn add_one(41);
                  val string started_id = started.id;
                  val answer = await started.result;
                  stdio.println(id);
                  stdio.println(answer);
                  stdio.println(started_id);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "actor-spawn.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("42"));
        assertFalse(rendered.contains("ActorId"),
                "source-level spawn identity must be a plain string, not a runtime ActorId object");
        long uuidLines = rendered.lines()
                .filter(line -> line.matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                .count();
        assertEquals(2, uuidLines,
                "plain spawn and StartedActor.id should both render as UUID strings");
    }

    @Test
    void untrustedActorCannotSpawnChildren() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc child() => int {
                          return 1;
                        }

                        pub untrusted actor fnc sandbox() => void {
                          val denied = spawn child();
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("untrusted actors cannot spawn child actors"));
    }

    @Test
    void isoactorCannotEscalateBySpawningSharedActorCallable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc shared_child() => int {
                          return 1;
                        }

                        pub isoactor routine private_parent() => void {
                          val denied = spawn shared_child();
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("SHARED_MEMORY"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub isoactor fnc private_child() => int {
                  return 1;
                }

                pub isoactor routine private_parent() => void {
                  val child = spawn private_child();
                  return;
                }
                """)));
    }

    @Test
    void untrustedActorCannotInspectForeignActorLiveness() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub untrusted actor fnc probe(ActorRef target) => bool {
                          return target.is_alive();
                        }
                        """)));
        assertTrue(failure.getMessage().contains("lifecycle"));
    }

    @Test
    void trustedActorMaySpawnAndReceiveIdentityWithoutWaiting() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor fnc child() => int {
                  return 1;
                }

                pub actor routine parent() => void {
                  val id = spawn child();
                  return;
                }
                """)));
    }

}
