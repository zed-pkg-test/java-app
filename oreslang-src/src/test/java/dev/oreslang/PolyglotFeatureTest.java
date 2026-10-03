package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class PolyglotFeatureTest {
    @Test
    void ordinaryModuleInitRunsOnceAcrossRepeatedExecutionInOneContext() throws Exception {
        String program = """
                define module local_state as
                  let int count = 0;

                  init routine() => void {
                    count = count + 10;
                    return;
                  }

                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app as
                  pub routine main() => void {
                    stdio.println(local_state.next());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "context-init-once.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("11"), text);
        assertTrue(text.contains("12"), text);
    }

    @Test
    void executesNamespacesCollectionsAssignmentAndInheritedMethods() throws Exception {
        String program = """
                define module math as
                  pub fnc add(int a, int b) => int { return a + b; }
                end

                define module model as
                  define class A as
                    pub value() => int { return 7; }
                  end
                  define class B extends A as
                  end
                end

                define module app as
                  pub fnc main() => void {
                    let answer = math.add(1, 2);
                    answer = answer + 4;
                    val values = arr[answer, 9];
                    val person = obj{name: "ores"};
                    val inherited = new B();
                    stdio.println(values[0]);
                    stdio.println(person.name);
                    stdio.println(inherited.value());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "features.ores")
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
        assertTrue(text.contains("ores"));
    }
}
