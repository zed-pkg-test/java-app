package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class FutureAllLanguageTest {

    @Test
    void awaitFutureAllRunsIndependentAsyncCallsAndPreservesOrder() throws Exception {
        String program = """
                async fnc left(): int {
                  return 20;
                }

                async fnc right(): int {
                  return 22;
                }

                pub fnc main(): void {
                  val values = await Future.all([left(), right()]);
                  stdio.println(values[0] + values[1]);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "future-all.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("42"));
    }
}
