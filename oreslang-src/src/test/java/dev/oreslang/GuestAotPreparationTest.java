package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class GuestAotPreparationTest {

    @Test
    void compilesOreslangRootsWithoutPriorExecution() throws Exception {
        String program = """
                pub fnc add(int a, int b) => int {
                  return a + b;
                }

                pub routine main() => void {
                  stdio.println(add(40, 2));
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "guest-aot.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .allowExperimentalOptions(true)
                .option("engine.CompileAOTOnCreate", "true")
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(
                output.toString(StandardCharsets.UTF_8).contains("42"),
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void compilesActorProgramRootsWithoutWarmup() throws Exception {
        String program = """
                pub actor fnc shared_double(int value) => int {
                  return value * 2;
                }

                pub isoactor fnc private_add_one(int value) => int {
                  return value + 1;
                }

                pub routine main() => void {
                  val shared_pending = spawn shared_double(21);
                  val private_pending = spawn private_add_one(41);
                  stdio.println(await shared_pending.result);
                  stdio.println(await private_pending.result);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "guest-aot-actors.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .allowExperimentalOptions(true)
                .option("engine.CompileAOTOnCreate", "true")
                .out(output)
                .build()) {
            context.eval(source);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("42"), rendered);
    }
}
