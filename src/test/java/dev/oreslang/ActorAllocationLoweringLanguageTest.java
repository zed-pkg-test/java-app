package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ActorAllocationLoweringLanguageTest {

    @Test
    void scalarOnlyActorWorkIsCopyElidedAndDoesNotChargeLocalHeap() throws Exception {
        String output = run("""
                pub shared actor fnc scalar_only(): int {
                  val int before = actor.local_memory_bytes as int;
                  val int a = 41;
                  val int b = a + 1;
                  val bool flag = true;
                  val float ratio = 1.5;
                  val int after = actor.local_memory_bytes as int;
                  return after - before;
                }

                pub routine main(): void {
                  stdio.stdout.write(scalar_only());
                  return;
                }
                """);

        assertEquals("0", output,
                "value-semantic scalar work must not manufacture actor-local heap storage");
    }

    @Test
    void listBackingIsChargedToSharedPrivateAndUntrustedActorDomains() throws Exception {
        String output = run("""
                pub shared actor fnc shared_list(): int {
                  val int before = actor.local_memory_bytes as int;
                  val values = [1, 2, 3, 4];
                  val int after = actor.local_memory_bytes as int;
                  return after - before;
                }

                pub actor fnc private_list(): int {
                  val int before = actor.local_memory_bytes as int;
                  val values = [1, 2, 3, 4];
                  val int after = actor.local_memory_bytes as int;
                  return after - before;
                }

                pub untrusted actor fnc untrusted_list(): int {
                  val int before = actor.local_memory_bytes as int;
                  val values = [1, 2, 3, 4];
                  val int after = actor.local_memory_bytes as int;
                  return after - before;
                }

                pub routine main(): void {
                  stdio.stdout.write(shared_list());
                  stdio.stdout.write(":");
                  stdio.stdout.write(private_list());
                  stdio.stdout.write(":");
                  stdio.stdout.write(untrusted_list());
                  return;
                }
                """);

        String[] parts = output.split(":");
        assertEquals(3, parts.length, output);
        for (String part : parts) {
            assertTrue(Long.parseLong(part) > 0L, output);
        }
    }

    @Test
    void classTupleObjectAndClosureStorageChargeActorLocalHeap() throws Exception {
        String output = run("""
                define class Box as
                  pub val int value = 42;
                end

                pub shared actor fnc class_value(): int {
                  val int before = actor.local_memory_bytes as int;
                  val Box box = new Box();
                  val int after = actor.local_memory_bytes as int;
                  return after - before;
                }

                pub shared actor fnc tuple_value(): int {
                  val int before = actor.local_memory_bytes as int;
                  val tuple = (1, 2, 3);
                  val int after = actor.local_memory_bytes as int;
                  return after - before;
                }

                pub shared actor fnc object_value(): int {
                  val int before = actor.local_memory_bytes as int;
                  val record = obj{name: "ores", version: 1};
                  val int after = actor.local_memory_bytes as int;
                  return after - before;
                }

                pub shared actor fnc closure_value(): int {
                  val int captured = 42;
                  val int before = actor.local_memory_bytes as int;
                  val callback = || -> {
                    return captured;
                  };
                  val int after = actor.local_memory_bytes as int;
                  return after - before;
                }

                pub routine main(): void {
                  stdio.stdout.write(class_value());
                  stdio.stdout.write(":");
                  stdio.stdout.write(tuple_value());
                  stdio.stdout.write(":");
                  stdio.stdout.write(object_value());
                  stdio.stdout.write(":");
                  stdio.stdout.write(closure_value());
                  return;
                }
                """);

        String[] parts = output.split(":");
        assertEquals(4, parts.length, output);
        for (String part : parts) {
            assertTrue(Long.parseLong(part) > 0L, output);
        }
    }

    @Test
    void actorMemoryDiagnosticCannotBeExtractedAsAuthority() {
        assertThrows(Exception.class, () -> run("""
                pub untrusted actor fnc bad(): int {
                  val facade = actor;
                  return 0;
                }

                pub routine main(): void {
                  stdio.stdout.write(bad());
                  return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "actor-allocation-lowering.ores")
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
