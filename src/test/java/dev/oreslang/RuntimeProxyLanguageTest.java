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

                  pub current(): int {
                    return self.value;
                  }
                end

                pub routine main(): void {
                  val counter = rt proxy new Counter();
                  counter.value = 10;
                  stdio.stdout.write(counter.value);
                  stdio.stdout.write(counter.current());
                  return;
                }
                """);

        assertEquals("1010", output);
    }

    @Test
    void proxyDisposeIsExplicitAndRevokesGuestAccess() throws Exception {
        String output = run("""
                define class Box as
                  pub let int value = 1;
                end

                pub routine main(): void {
                  val guarded = rt proxy new Box();
                  guarded.dispose();
                  stdio.stdout.write("done");
                  return;
                }
                """);

        assertEquals("done", output);

        IllegalArgumentException shape = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        define class Box as
                          pub let int value = 1;
                        end

                        fnc bad(): void {
                          val guarded = rt proxy new Box();
                          guarded.dispose(1);
                          return;
                        }
                        """));
        assertTrue(shape.getMessage().contains("dispose"), shape.getMessage());
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
    void proxyAssignmentCannotReturnMoveOnlyValueOutsideLock() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        define class Inner as
                          pub let int value = 1;
                        end

                        define class Outer as
                          pub let Inner inner = new Inner();
                        end

                        fnc bad(): Inner {
                          val guarded = rt proxy new Outer();
                          return guarded.inner = new Inner();
                        }
                        """));

        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("void") || message.contains("return"),
                failure.getMessage());
    }

    @Test
    void rtProxyRejectsCapabilityLaunderingPayloadGraphs() {
        IllegalArgumentException localMutex = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        define class Bag as
                          pub let Mutex<int> lock = Mutex.new(1);
                        end

                        fnc bad(): void {
                          val guarded = rt proxy new Bag();
                          return;
                        }
                        """));
        assertTrue(localMutex.getMessage().contains("transport-safe")
                        || localMutex.getMessage().contains("capabilities"),
                localMutex.getMessage());

        IllegalArgumentException sharedMutex = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        define class Bag as
                          pub let SharedMutex<int> lock = SharedMutex.new(1);
                        end

                        fnc bad(): void {
                          val guarded = rt proxy new Bag();
                          return;
                        }
                        """));
        assertTrue(sharedMutex.getMessage().contains("transport-safe")
                        || sharedMutex.getMessage().contains("capabilities"),
                sharedMutex.getMessage());
    }

    @Test
    void proxyDynamicStructNestedClassValuesRemainSynchronizedProxyViews() {
        assertDoesNotThrow(() -> check("""
                define class Inner as
                  pub let int value = 1;
                end

                fnc good(): void {
                  let DynamicStruct<Inner> bag = new DynamicStruct<Inner>();
                  bag["child"] = new Inner();
                  val guarded = rt proxy bag;
                  val Proxy<Inner> by_member = guarded.child;
                  val Proxy<Inner> by_index = guarded["child"];
                  return;
                }
                """));
    }

    @Test
    void helperReturnedProxyKeepsTheSameProtectionRules() {
        assertDoesNotThrow(() -> check("""
                define class Box as
                  pub let int value = 7;

                  pub current(): int {
                    return self.value;
                  }
                end

                fnc make(): Proxy<Box> {
                  return rt proxy new Box();
                }

                fnc good(): int {
                  return make().current();
                }
                """));

        assertDoesNotThrow(() -> check("""
                define class Inner as
                  pub let int value = 1;
                end

                define class Outer as
                  pub let Inner inner = new Inner();
                end

                fnc make(): Proxy<Outer> {
                  return rt proxy new Outer();
                }

                fnc good_nested(): int {
                  val protected_inner = make().inner;
                  return protected_inner.value;
                }
                """));
    }

    @Test
    void nestedProxyFieldNeverDowngradesToRawMoveOnlyOwner() {
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
                          let Inner escaped = guarded.inner;
                          stdio.println(escaped.value);
                          return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("Proxy")
                        || failure.getMessage().contains("assign"),
                failure.getMessage());
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

    @Test
    void nestedClassViewsStayProxiedAndSupportSynchronizedMutation() throws Exception {
        String output = run("""
                define class Inner as
                  pub let int value = 1;

                  pub fnc bump(mut self): int {
                    self.value = self.value + 1;
                    return self.value;
                  }
                end

                define class Outer as
                  pub val Inner inner = new Inner();
                end

                pub routine main(): void {
                  val guarded = rt proxy new Outer();
                  stdio.stdout.write(guarded.inner.bump());
                  guarded.inner.value = 7;
                  stdio.stdout.write(":");
                  stdio.stdout.write(guarded.inner.value);
                  return;
                }
                """);

        assertEquals("2:7", output);
    }

    @Test
    void nestedProxyTypeCannotBeAssignedToRawChildOwner() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        define class Inner as
                          pub let int value = 1;
                        end

                        define class Outer as
                          pub val Inner inner = new Inner();
                        end

                        fnc bad(): void {
                          val guarded = rt proxy new Outer();
                          let Inner raw = guarded.inner;
                          stdio.println(raw.value);
                          return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("assign")
                        || failure.getMessage().contains("Proxy"),
                failure.getMessage());
    }


    @Test
    void optionWrappedNestedClassIsRewrappedAsChildProxy() throws Exception {
        String output = run("""
                define class Inner as
                  pub let int value = 3;
                end

                define class Outer as
                  pub val Inner inner = new Inner();

                  pub maybe_inner(): Option<Inner> {
                    return Some(self.inner);
                  }
                end

                pub routine main(): void {
                  val guarded = rt proxy new Outer();
                  val nested = guarded.maybe_inner().unwrap();
                  nested.value = 8;
                  stdio.stdout.write(nested.value);
                  return;
                }
                """);

        assertEquals("8", output);
    }

}
