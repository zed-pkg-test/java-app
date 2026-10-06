package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import dev.oreslang.compiler.OresCompiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class FutureCompositionLanguageTest {

    @Test
    void mapComposeFlatMapAndOnSuccessStayOnOresFutureSurface() throws Exception {
        String program = """
                async fnc base(): int {
                  return 20;
                }

                async fnc twice(int value): int {
                  return value * 2;
                }

                pub fnc main(): void {
                  let int observed = 0;

                  val Future<int> mapped = base().map(|int value| -> {
                    return value + 1;
                  });

                  val Future<int> composed = mapped.compose(|int value| -> {
                    return twice(value);
                  });

                  val Future<int> observed_future = composed.onSuccess(|int value| -> {
                    observed = value;
                    return;
                  });

                  val int answer = await observed_future;
                  stdio.stdout.write(answer);
                  stdio.stdout.write(":");
                  stdio.stdout.write(observed);

                  val Future<int> flat = base().flatMap(|int value| -> {
                    return twice(value);
                  });
                  stdio.stdout.write(":");
                  stdio.stdout.write(await flat);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42:42:40", run(program));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "future-compose.ores")
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
