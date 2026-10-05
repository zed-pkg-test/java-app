package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FutureCombinatorLanguageTest {

    @Test
    void futureAllRunsIndependentAsyncCallsAndPreservesOrder() throws Exception {
        String program = """
                define module app
                  async fnc one(): int {
                    return 1;
                  }

                  async fnc two(): int {
                    return 2;
                  }

                  pub fnc main(): void {
                    val values = await Future.all([one(), two()]);
                    stdio.println(values);
                    return;
                  }
                end
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

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("[1, 2]"));
    }

    @Test
    void futureRaceReturnsFirstSettledFuture() throws Exception {
        String program = """
                define module app
                  async fnc first(): int {
                    return 9;
                  }

                  pub fnc main(): void {
                    val winner = await Future.race([first()]);
                    stdio.println(winner);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "future-race.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("9"));
    }

    @Test
    void futureAllRejectsNonFutureListsStatically() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val bad = Future.all([1, 2]);
                          return;
                        }
                        """));
    }
}
