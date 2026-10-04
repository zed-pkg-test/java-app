package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProperTailCallTest {

    @Test
    void deepTailCallsStayConstantStackAcrossCallableKinds() throws Exception {
        String output = run("""
                fnc fnc_down(int n): int {
                  if n == 0; do
                    return 0;
                  else
                    return fnc_down(n - 1);
                  fi
                }

                routine routine_down(int n): int {
                  if n == 0; do
                    return 0;
                  else
                    return routine_down(n - 1);
                  fi
                }

                fnc is_even(int n): bool {
                  if n == 0; do
                    return true;
                  else
                    return is_odd(n - 1);
                  fi
                }

                fnc is_odd(int n): bool {
                  if n == 0; do
                    return false;
                  else
                    return is_even(n - 1);
                  fi
                }

                define class Counter as
                  pub down(int n): int {
                    if n == 0; do
                      return 0;
                    else
                      return self.down(n - 1);
                    fi
                  }

                  pub static fnc static_down(int n): int {
                    if n == 0; do
                      return 0;
                    else
                      return Counter.static_down(n - 1);
                    fi
                  }
                end

                pub routine main(): void {
                  let Fnc<int, int> lambda_down = |int n| -> {
                    if n == 0; do
                      return 0;
                    else
                      return lambda_down(n - 1);
                    fi
                  };

                  val counter = new Counter();

                  stdio.stdout.write(fnc_down(50000));
                  stdio.stdout.write("|");
                  stdio.stdout.write(routine_down(50000));
                  stdio.stdout.write("|");
                  stdio.stdout.write(is_even(50000));
                  stdio.stdout.write("|");
                  stdio.stdout.write(counter.down(50000));
                  stdio.stdout.write("|");
                  stdio.stdout.write(Counter.static_down(50000));
                  stdio.stdout.write("|");
                  stdio.stdout.write(lambda_down(50000));
                  return;
                }
                """);

        assertEquals("0|0|true|0|0|0", output);
    }

    @Test
    void conditionalTailPositionAlsoUsesTheTrampoline() throws Exception {
        String output = run("""
                fnc down(int n): int {
                  return n == 0 ? 0 : down(n - 1);
                }

                pub routine main(): void {
                  stdio.stdout.write(down(50000));
                }
                """);

        assertEquals("0", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "proper-tail-call.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
