package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

final class ConcurrencySyntaxContractTest {

    @Test
    void nbReadchAndNbWritechAreFutureReturningExpressions() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc arm(
                    Channel<int> input,
                    Channel<int> output
                ): void {
                  val Future<int> pending_read = nb readch input;
                  val Future<void> pending_write = nb writech output, 42;
                  return;
                }
                """)));
    }

    @Test
    void nbSelectIsActorOwnedAndReturnsImmediately() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc arm(Channel<int> input): void {
                  nb select {
                  case readch input: const value
                    stdio.println(value);
                  }
                  stdio.println("turn continues after arming select");
                  return;
                }
                """)));
    }

    @Test
    void nbCbWritechIsVoidCallbackSurfaceRatherThanFutureSurface() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                actor fnc arm(Channel<int> output): void {
                  nb cb writech output, 42 || -> {
                    stdio.println("write completed");
                  };
                  return;
                }
                """));

        Ast.FunctionDecl fn =
                assertInstanceOf(
                        Ast.FunctionDecl.class,
                        program.modules().getFirst().declarations().getFirst());
        Ast.ExprStmt statement =
                assertInstanceOf(Ast.ExprStmt.class, fn.body().getFirst());
        Ast.ChannelOpExpr write =
                assertInstanceOf(Ast.ChannelOpExpr.class, statement.expression());

        assertEquals(Ast.ChannelOperation.WRITE, write.operation());
        assertEquals(Ast.WaitMode.NONBLOCKING, write.mode());
        assertTrue(write.callback());
        assertEquals(1, write.callbackBody().size());
    }

    @Test
    void nbCbWritechRequiresActorExecutionDomain() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc wrong(Channel<int> output): void {
                  nb cb writech output, 42 || -> {
                    stdio.println("wrong domain");
                  };
                  return;
                }
                """)));
    }
}
