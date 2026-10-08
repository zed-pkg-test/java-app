package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.ast.Ast;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorSpawnStreamContractTest {
    @TestFactory Stream<DynamicTest> declarationKindsAreExplicitInEveryBodyStyle() {
        return Stream.of("actor", "isolated actor", "untrusted actor").flatMap(kind ->
                Stream.of("as BODY end", "as { BODY }", "{ BODY }").flatMap(body ->
                        Stream.of(false, true).map(inModule -> DynamicTest.dynamicTest(kind + body + inModule, () -> {
                            String declaration = "define " + kind + " Worker " + body.replace("BODY", "pub get(): int { return 1; }");
                            Ast.Program program = Parser.parse(inModule ? "define module workers " + declaration + " end" : declaration);
                            TypeChecker.check(program);
                            Ast.ClassDecl actor = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
                            assertEquals(kind.equals("actor") ? Ast.ActorKind.SHARED
                                    : kind.startsWith("isolated") ? Ast.ActorKind.PRIVATE : Ast.ActorKind.UNTRUSTED,
                                    actor.actorKind());
                        }))));
    }

    @Test void isolatedSpellingExecutesConfinedTrustedActor() throws Exception {
        assertEquals("42\n", run("""
                define isolated actor Worker as
                  let int value = 42;
                  pub get(): int { return self.value; }
                end
                pub routine main() => void {
                  val worker = spawn Worker();
                  stdio.println(await worker.get());
                  return;
                }
                """));
    }

    @Test void sendAndReceiveNamesAreTypedEndpointsRatherThanRawMailboxAccess() throws Exception {
        assertEquals("42\n", run("""
                define actor Worker as {
                  let int value = 0;
                  pub send(value: int): void { self.value = value; return; }
                  pub receive(): int { return self.value; }
                }
                pub routine main() => void {
                  val worker = spawn Worker();
                  await worker.send(42);
                  stdio.println(await worker.receive());
                  return;
                }
                """));
    }
    @TestFactory Stream<DynamicTest> actorClassBodySpellingsExecuteThroughSpawn() {
        return Stream.of("{ BODY }", "as { BODY }", "as BODY end").map(body ->
                DynamicTest.dynamicTest(body, () -> assertEquals("42\n", run("""
                        define actor Counter BODY_STYLE
                        pub routine main() => void {
                          val counter = spawn Counter(40);
                          await counter.add(2);
                          stdio.println(await counter.get());
                          return;
                        }
                        """.replace("BODY_STYLE", body.replace("BODY", """
                          let int value = 0;
                          constructor(initial: int) { self.value = initial; }
                          pub add(delta: int): void { self.value = self.value + delta; return; }
                          pub get(): int { return self.value; }
                        """))))));
    }

    @TestFactory Stream<DynamicTest> unaryAndStreamingResultsExecuteAsActors() {
        return Stream.of("Observable", "Stream").map(kind -> DynamicTest.dynamicTest(kind, () ->
                assertEquals("42\n1\n2\ntrue\n", run("""
                        actor fnc unary(): int { return 42; }
                        actor fnc producer(): KIND<int> { return KIND.from_values([1, 2]); }
                        pub routine main() => void {
                          val one = spawn unary();
                          stdio.println(await one.result);
                          val launched = spawn producer();
                          val values = await launched.result;
                          val subscription = values.subscribe();
                          val first = await subscription.next();
                          val second = await subscription.next();
                          val completed = await subscription.next();
                          stdio.println(first.value);
                          stdio.println(second.value);
                          stdio.println(completed.is_complete());
                          return;
                        }
                        """.replace("KIND", kind)))));
    }

    @TestFactory Stream<DynamicTest> actorApiRejectsAuthorityAndConstructionBypasses() {
        List<String> bodies = List.of(
                "val denied = worker();",
                "val denied = Worker();",
                "val denied = new Worker();",
                "val factory = Worker; val denied = factory();",
                "val entry = worker;",
                "val denied = spawn ordinary();",
                "val actor = spawn Worker(); val denied = actor.secret;",
                "val actor = spawn Worker(); val denied = actor.hidden();",
                "val actor = spawn Worker(); actor.constructor();",
                "val actor = spawn Worker(); actor.send(1);",
                "val actor = spawn Worker(); actor.receive(1);",
                "val actor = spawn Worker(); val denied = actor.mailbox;",
                "val actor = spawn Worker(); val denied = actor.get;",
                "val actor = spawn Worker(); actor.secret = 3;"
        );
        return bodies.stream().map(body -> DynamicTest.dynamicTest(body, () ->
                assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as {
                          let int secret = 1;
                          constructor() { return; }
                          private hidden(): int { return self.secret; }
                          pub get(): int { return self.secret; }
                        }
                        actor fnc worker(): int { return 1; }
                        fnc ordinary(): int { return 1; }
                        pub routine main() => void { BODY return; }
                        """.replace("BODY", body))))));
    }

    @TestFactory Stream<DynamicTest> reservedProtocolNamesAreRejected() {
        return Stream.of("id", "is_alive", "mailbox").map(name ->
                DynamicTest.dynamicTest(name, () -> assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse("define actor Bad { pub " + name + "(): int { return 1; } }")))));
    }

    @TestFactory Stream<DynamicTest> invalidReactiveBoundaryTypesAreRejected() {
        return Stream.of("Stream", "Observable<void>", "Stream<Future<int>>", "Observable<Mutex<int>>")
                .map(type -> DynamicTest.dynamicTest(type, () -> assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse("actor fnc bad(): " + type + " { return 1; }")))));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build();
        try {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "spawn-stream-contract.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        } finally { context.close(true); }
        return output.toString(StandardCharsets.UTF_8);
    }
}
