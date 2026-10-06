package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimeProxyLanguageTest {

    @Test
    void rtProxySynchronizesClassFieldAndMethodAccess() throws Exception {
        String output = run("""
                define class Counter as
                  pub let int value = 1;

                  pub inc(mut self)(): int {
                    self.value = self.value + 1;
                    return self.value;
                  }
                end

                pub routine main(): void {
                  val counter = rt proxy new Counter();
                  counter.value = 10;
                  stdio.stdout.write(counter.value);
                  stdio.stdout.write(counter.inc());
                  return;
                }
                """);

        assertEquals("1011", output);
    }

    @Test
    void callFormAndCommandFormBothParseAndTypecheck() {
        assertDoesNotThrow(() -> check("""
                define class Box as
                  pub let int value = 1;
                end

                fnc build(): void {
                  val first = rt proxy new Box();
                  val second = rt proxy(new Box());
                  stdio.println(first.value);
                  stdio.println(second.value);
                  return;
                }
                """));
    }

    @Test
    void rtProxyConsumesOriginalOwner() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        define class Box as
                          pub let int value = 1;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val guarded = rt proxy box;
                          stdio.println(guarded.value);
                          stdio.println(box.value);
                          return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("moved value 'box'"));
    }

    @Test
    void proxyDoesNotLeakMoveOnlyFields() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        define class Inner as
                          pub let int value = 1;
                        end

                        define class Outer as
                          pub let Inner inner = new Inner();
                        end

                        fnc bad(): void {
                          val guarded = rt proxy new Outer();
                          val escaped = guarded.inner;
                          stdio.println(escaped.value);
                          return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("cannot extract move-only field"));
    }

    @Test
    void sharedActorCanUseProxyWithoutBroadSharedMemoryCapability() {
        assertDoesNotThrow(() -> {
            var program = check("""
                    define class Counter as
                      pub let int value = 0;
                    end

                    shared actor Worker {
                      pub fnc run(): void {
                        val guarded = rt proxy new Counter();
                        guarded.value = 1;
                        stdio.println(guarded.value);
                        return;
                      }
                    }
                    """);
            CapabilityChecker.check(program, IsolatePolicy.developer());
        });
    }

    @Test
    void sharedActorSharedMutexIsNoLongerImplicitlyAuthorized() {
        var program = check("""
                shared actor Worker {
                  pub fnc run(): void {
                    val guarded = SharedMutex.new(1);
                    stdio.println(guarded);
                    return;
                  }
                }
                """);

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(failure.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotUseRtProxy() {
        var program = check("""
                define class Counter as
                  pub let int value = 0;
                end

                isoactor Worker {
                  pub fnc run(): void {
                    val guarded = rt proxy new Counter();
                    stdio.println(guarded.value);
                    return;
                  }
                }
                """);

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(failure.getMessage().contains("ACTOR_SHARED_PROXY"));
    }

    private static dev.oreslang.ast.Ast.Program check(String source) {
        var program = Parser.parse(source);
        TypeChecker.check(program);
        OwnershipChecker.check(program);
        return program;
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "runtime-proxy.ores")
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
