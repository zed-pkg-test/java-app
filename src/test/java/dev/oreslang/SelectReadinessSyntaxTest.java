package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SelectReadinessSyntaxTest {
    @Test
    void parsesAndTypesAllStaticReadinessArmKinds() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc waitForSomething(
                    Channel<string> messages,
                    Future<int> future,
                    CancellationToken token
                ): void {
                  select {
                    when readch messages: val msg {
                      stdio.println(msg);
                    }

                    when await future: val result {
                      stdio.println(result);
                    }

                    when timeout 5s {
                      stdio.println("timeout");
                    }

                    when cancelled token {
                      stdio.println("cancelled");
                    }
                  }
                  return;
                }
                """));

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.SelectStmt selected = assertInstanceOf(Ast.SelectStmt.class, fn.body().getFirst());
        assertEquals(
                java.util.List.of(
                        Ast.SelectOperation.READ,
                        Ast.SelectOperation.AWAIT,
                        Ast.SelectOperation.TIMEOUT,
                        Ast.SelectOperation.CANCELLED),
                selected.arms().stream().map(Ast.SelectArm::operation).toList());
        assertEquals(5_000_000_000L, selected.arms().get(2).timeoutNanos());
        assertEquals("result", selected.arms().get(1).bindingName());
        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    @Test
    void timeoutUnitsAreExactAndInvalidUnitsAreRejected() {
        Ast.Program program = Parser.parse("""
                fnc timers(): void {
                  select {
                    when timeout 2.5ms {
                      return;
                    }
                  }
                }
                """);
        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.SelectStmt selected = (Ast.SelectStmt) fn.body().getFirst();
        assertEquals(2_500_000L, selected.arms().getFirst().timeoutNanos());

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): void {
                  select {
                    when timeout 5fortnights {
                      return;
                    }
                  }
                }
                """));
    }

    @Test
    void awaitRequiresFutureAndVoidFutureCannotBind() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(int value): void {
                  select {
                    when await value: val result {
                      stdio.println(result);
                    }
                  }
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Future<void> future): void {
                  select {
                    when await future: val result {
                      stdio.println(result);
                    }
                  }
                  return;
                }
                """)));
    }

    @Test
    void cancellationTokenFactoryAndMembersAreTyped() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc tokenOps(): bool {
                  val CancellationToken token = CancellationToken.new();
                  val bool first = token.cancel();
                  val bool state = token.is_cancelled();
                  return first && state;
                }
                """)));
    }

    @Test
    void sourceRuntimeSelectsCompletedFutureAndCancelledToken() throws Exception {
        String program = """
                define module app
                  async fnc ready(): int {
                    return 7;
                  }

                  pub fnc main(): void {
                    val Future<int> future = ready();
                    select first {
                      when await future: val result {
                        stdio.println(result);
                      }
                      when timeout 5s {
                        stdio.println("future-timeout");
                      }
                    }

                    val CancellationToken token = CancellationToken.new();
                    token.cancel();
                    select first {
                      when cancelled token {
                        stdio.println("cancelled");
                      }
                      when timeout 5s {
                        stdio.println("cancel-timeout");
                      }
                    }
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "select-readiness.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("7"));
        assertTrue(text.contains("cancelled"));
        assertFalse(text.contains("future-timeout"));
        assertFalse(text.contains("cancel-timeout"));
    }
}
