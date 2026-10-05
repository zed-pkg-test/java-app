package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class FutureLanguageTest {

    @Test
    void futuresAllRaceAndStateMethodsTypeCheck() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc collect(Future<int> first, Future<int> second) => List<int> {
                    val both = Futures.all([first, second]);
                    return await both;
                  }

                  fnc fastest(Future<int> first, Future<int> second) => int {
                    return await Futures.race([first, second]);
                  }

                  fnc observe(Future<int> work) => bool {
                    if work.is_cancelled(); do
                      return false;
                    fi
                    return work.is_done();
                  }
                end
                """)));
    }

    @Test
    void futuresAllRejectsNonFutureElementsAtTypeCheckTime() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad() => void {
                            val nope = Futures.all([1, 2, 3]);
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("Future"));
    }

    @Test
    void futureOfMutexGuardMayBeAggregatedButAcquiredGuardMayNot() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc aggregate_pending(Mutex<int> first_mutex, Mutex<int> second_mutex) => void {
                    val first = first_mutex.lock_async();
                    val second = second_mutex.lock_async();
                    val pending = [first, second];
                    return;
                  }
                end
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc aggregate_acquired(Mutex<int> mutex) => void {
                            val guard = mutex.lock();
                            val invalid = [guard];
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("MutexGuard cannot be stored in an array/list"));
    }

    @Test
    void futuresAllRunsThroughLanguageRuntimeWithoutBlockingRootCarrier() throws Exception {
        String program = """
                pub routine main() => void {
                  val first_mutex = Mutex.new(1);
                  val second_mutex = Mutex.new(2);
                  val first = first_mutex.lock_async();
                  val second = second_mutex.lock_async();
                  val guards = await Futures.all([first, second]);
                  stdio.println("futures-all-ok");
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "futures.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("futures-all-ok"));
    }
    @Test
    void awaitableProtocolProjectsFuturePayload() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Deferred implements Awaitable<int> as
                    pub val Future<int> pending;

                    pub getAwait() => Future<int> {
                      return self.pending;
                    }
                  end

                  fnc consume(Deferred value) => int {
                    return await value;
                  }

                  fnc consume_interface(Awaitable<int> value) => int {
                    return await value;
                  }
                end
                """)));
    }

    @Test
    void awaitableGetAwaitMayBeAsyncAndDeclareThePayloadType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class LazyValue implements Awaitable<int> as
                    pub async getAwait() => int {
                      return 42;
                    }
                  end

                  fnc consume(LazyValue value) => int {
                    return await value;
                  }
                end
                """)));
    }

    @Test
    void userInterfacesMayExtendAwaitable() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface NamedTask<T> extends Awaitable<T> {
                    fnc label() => string;
                  }

                  define class Task implements NamedTask<int> as
                    pub async getAwait() => int {
                      return 7;
                    }

                    pub label() => string {
                      return "task";
                    }
                  end

                  fnc consume(Task value) => int {
                    return await value;
                  }
                end
                """)));
    }

    @Test
    void malformedAwaitableContractsAreRejected() {
        IllegalArgumentException wrongSyncReturn = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          define class Bad implements Awaitable<int> as
                            pub getAwait() => int {
                              return 1;
                            }
                          end
                        end
                        """)));
        assertTrue(wrongSyncReturn.getMessage().contains("Awaitable<T>.getAwait()"));

        IllegalArgumentException wrongAsyncReturn = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          define class Bad implements Awaitable<int> as
                            pub async getAwait() => string {
                              return "nope";
                            }
                          end
                        end
                        """)));
        assertTrue(wrongAsyncReturn.getMessage().contains("Awaitable<T>.getAwait()"));
    }

    @Test
    void awaitRejectsValuesWithoutAwaitableProtocol() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad(int value) => int {
                            return await value;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("Future<T> or Awaitable<T>"));
    }


    @Test
    void asyncCallExpressionsProduceFutureValues() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  async fnc work() => int {
                    return 42;
                  }

                  fnc expose() => Future<int> {
                    return work();
                  }

                  fnc consume() => int {
                    return await work();
                  }
                end
                """)));
    }

    @Test
    void asyncGetAwaitIsExplicitlyCallableAsFutureProjection() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class LazyValue implements Awaitable<int> as
                    pub async getAwait() => int {
                      return 42;
                    }
                  end

                  fnc project(LazyValue value) => Future<int> {
                    return value.getAwait();
                  }

                  fnc consume(LazyValue value) => int {
                    return await value;
                  }

                  fnc project_future(Future<int> value) => Future<int> {
                    return value.getAwait();
                  }
                end
                """)));
    }


    @Test
    void asyncFunctionProducesRuntimeFutureAndCanBeAwaitedByHostEmbedder() throws Exception {
        String program = """
                pub async fnc work() => int {
                  return 42;
                }

                pub fnc main() => int {
                  return await work();
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "async-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            Value result = context.eval(source);
            assertEquals(42, result.asInt());
        }
    }

    @Test
    void asyncGetAwaitConstructsARealRuntimeFuture() throws Exception {
        String program = """
                define class LazyValue implements Awaitable<int> as
                  pub async getAwait() => int {
                    return 42;
                  }
                end

                pub fnc main() => int {
                  val value = new LazyValue();
                  return await value;
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "async-awaitable-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            Value result = context.eval(source);
            assertEquals(42, result.asInt());
        }
    }


    @Test
    void futureIsNominallyAssignableToAwaitable() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc consume(Awaitable<int> value) => int {
                    return await value;
                  }

                  fnc pass(Future<int> value) => int {
                    return consume(value);
                  }
                end
                """)));
    }


    @Test
    void compilerKnownAsyncProtocolTypeNamesCannotBeImportedAsClasses() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import class Future from "./foreign.ores";

                        fnc main() => void {
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("compiler/runtime built-in type"));
    }

    @Test
    void compilerKnownAsyncProtocolTypeNamesCannotBeShadowed() {
        for (String declaration : java.util.List.of(
                "define class Awaitable as end",
                "define interface Future<T> { fnc nope() => void; }",
                "type ActorSpawn = int;",
                "define class ActorRef as end")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "define module app\n" + declaration + "\nend\n")));
            assertTrue(failure.getMessage().contains("compiler/runtime built-in type"));
        }
    }


    @Test
    void asyncCallableBoundaryRejectsBorrowedParameters() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        async fnc bad(&int value) => int {
                          return 1;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("async"));
        assertTrue(failure.getMessage().contains("borrowed"));
    }

    @Test
    void asyncInstanceMethodMovesOwnedReceiverIntoTask() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Worker as
                          pub async run() => int {
                            return 1;
                          }
                        end

                        fnc bad() => void {
                          val worker = new Worker();
                          val first = worker.run();
                          val second = worker.run();
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("moved value"));
    }

    @Test
    void asyncInstanceMethodRejectsBorrowedSelfReceiver() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Worker as
                          pub async run() => int {
                            return 1;
                          }

                          pub start() => Future<int> {
                            return self.run();
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("borrowed receiver"));
    }


    @Test
    void ownershipCheckerUsesAwaitablePayloadTypeAfterAwait() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class IntBox implements Awaitable<int> as
                  pub async getAwait() => int {
                    return 7;
                  }
                end

                fnc use_twice(IntBox box) => int {
                  val value = await box;
                  val first = value;
                  val second = value;
                  return first + second;
                }
                """)));
    }


    @Test
    void compilerKnownAsyncProtocolTypesCannotBeShadowedByGenerics() {
        for (String source : java.util.List.of(
                """
                fnc bad<Future>(Future value) => Future {
                  return value;
                }
                """,
                """
                define class Box<Awaitable> as
                end
                """,
                """
                define interface Box<ActorSpawn> {
                }
                """,
                """
                type Box<ActorRef> = ActorRef;
                """,
                """
                define class Box as
                  pub static fnc bad<Future>(Future value) => Future {
                    return value;
                  }
                end
                """,
                """
                define interface Box {
                  fnc bad<Awaitable>(Awaitable value) => Awaitable;
                }
                """)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)));
            assertTrue(
                    failure.getMessage().contains("compiler/runtime built-in type"),
                    failure::getMessage);
        }
    }


}
