package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DoChannelStatementTest {
    @Test
    void parsesAllFourExplicitDiscardFormsWithoutChangingWaitMode() {
        Ast.Program parsed = TypeChecker.check(Parser.parse("""
                fnc consume(Channel<int> incoming, Channel<int> outgoing): void {
                  do readch incoming;
                  do writech outgoing, 7;
                  do nb readch incoming;
                  do nb writech outgoing, 9;
                  return;
                }
                """));
        Ast.FunctionDecl function =
                (Ast.FunctionDecl) parsed.modules().getFirst().declarations().getFirst();

        for (int index = 0; index < 4; index++) {
            Ast.ExprStmt statement =
                    assertInstanceOf(Ast.ExprStmt.class, function.body().get(index));
            Ast.ChannelOpExpr operation =
                    assertInstanceOf(Ast.ChannelOpExpr.class, statement.expression());
            assertEquals(index % 2 == 0
                            ? Ast.ChannelOperation.READ
                            : Ast.ChannelOperation.WRITE,
                    operation.operation());
            assertEquals(index < 2
                            ? Ast.WaitMode.BLOCKING
                            : Ast.WaitMode.NONBLOCKING,
                    operation.mode());
        }
    }

    @Test
    void doChannelStatementCannotBecomeAValueExpressionOrAnImmediateProbe() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Channel<int> incoming): void {
                  val result = do readch incoming;
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Channel<int> incoming): void {
                  do try readch incoming;
                  return;
                }
                """));
    }

    @Test
    void executesDiscardFormsOnBufferedAndRendezvousChannels() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> buffered = Channel.new<int>(1);
                  do writech buffered, 11;
                  do readch buffered;
                  do nb writech buffered, 22;
                  val int second = readch buffered;

                  val Channel<int> zero = Channel.new<int>(0);
                  do nb readch zero;
                  do writech zero, 33;
                  do nb writech zero, 44;
                  val int last = readch zero;

                  stdio.stdout.write(second);
                  stdio.stdout.write(":");
                  stdio.stdout.write(last);
                  return;
                }
                """;
        assertEquals("22:44", run(program));
    }

    @Test
    void explicitDiscardFormsWorkInAsyncSourceTasks() throws Exception {
        String program = """
                pub async fnc main(): void {
                  val Channel<int> channel = Channel.new<int>(0);
                  do nb writech channel, 51;
                  val int result = readch channel;
                  do nb readch channel;
                  do writech channel, 52;
                  stdio.stdout.write(result);
                  return;
                }
                """;
        assertEquals("51", run(program));
    }

    private static String run(String program) throws Exception {
        OresCompiler.parseAndTypeCheck(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "do-channel-statements.ores")
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
}
