package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class PointerlessMutableReceiverTest {

    @Test
    void mutSelfMutatesTheSameOwnedObjectWithoutPointerSyntax() throws Exception {
        String output = run("""
                define class Counter as
                  pub let int value = 0;

                  pub bump(mut self)(): int {
                    self.value = self.value + 1;
                    return self.value;
                  }
                end

                pub routine main(): void {
                  let Counter counter = new Counter();
                  stdio.stdout.write(counter.bump());
                  stdio.stdout.write(counter.bump());
                  return;
                }
                """);
        assertEquals("12", output);
    }

    @Test
    void mutSelfWorksThroughNestedManagedReferences() throws Exception {
        String output = run("""
                define class Counter as
                  pub let int value = 0;

                  pub bump(mut self)(): int {
                    self.value = self.value + 1;
                    return self.value;
                  }
                end

                define class Holder as
                  pub val Counter counter;

                  constructor() {
                    self.counter = new Counter();
                  }

                  pub bump_nested(mut self)(): int {
                    return self.counter.bump();
                  }
                end

                pub routine main(): void {
                  let Holder holder = new Holder();
                  stdio.stdout.write(holder.bump_nested());
                  return;
                }
                """);
        assertEquals("1", output);
    }

    @Test
    void mutableReceiverRejectsImmutableRoot() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Counter as
                          pub let int value = 0;

                          pub bump(mut self)(): int {
                            self.value = self.value + 1;
                            return self.value;
                          }
                        end

                        fnc bad(): int {
                          val Counter counter = new Counter();
                          return counter.bump();
                        }
                        """)));
        assertTrue(error.getMessage().contains("immutable"), error.getMessage());
        assertFalse(error.getMessage().contains("&mut"), error.getMessage());
    }

    private String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(true)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder("ores", code, "pointerless-mutable-receiver.ores").build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
