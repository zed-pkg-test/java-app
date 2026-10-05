package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class FutureAllAsyncMethodLanguageTest {

    @Test
    void futureAllIsTypedLikePromiseAllAndPreservesElementPayload() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                async fnc left() => int {
                  return 21;
                }

                async fnc right() => int {
                  return 22;
                }

                async fnc collect() => List<int> {
                  return await Future.all([left(), right()]);
                }
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        async fnc bad() => List<int> {
                          return await Future.all([1, 2]);
                        }
                        """)));
        assertTrue(failure.getMessage().contains("Future.all"));
    }

    @Test
    void asyncInstanceMethodCanSuspendResumeAndJoinFutureAll() throws Exception {
        String program = """
                async fnc base_value() => int {
                  return 20;
                }

                async fnc peer_value() => int {
                  return 22;
                }

                define class Worker as
                  pub async plus_one() => int {
                    val value = await base_value();
                    return value + 1;
                  }
                end

                pub async routine main() => void {
                  val worker = new Worker();
                  val values = await Future.all([
                    worker.plus_one(),
                    peer_value()
                  ]);
                  stdio.println(values);
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "future-all-async-method.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("21"));
        assertTrue(rendered.contains("22"));
    }
}
