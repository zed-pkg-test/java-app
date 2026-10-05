package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class AsyncSchedulerLanguageTest {

    @Test
    void ordinaryAwaitRequiresAsyncCallable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Future<int> work) => int {
                          return await work;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("await is only legal"));
    }

    @Test
    void asyncCallProducesFutureOfLogicalReturnType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                async fnc produce() => int {
                  return 41;
                }

                fnc expose() => Future<int> {
                  return produce();
                }

                async fnc consume() => int {
                  return await produce() + 1;
                }
                """)));

        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        async fnc produce() => int {
                          return 41;
                        }

                        fnc bad() => int {
                          return produce();
                        }
                        """)));
        assertTrue(mismatch.getMessage().contains("return"));
    }

    @Test
    void asyncVoidUsesFutureVoidCompletionType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                async fnc perform() => void {
                  return;
                }

                fnc expose() => Future<void> {
                  return perform();
                }
                """)));
    }

    @Test
    void parserMarksActorCallablesAndMethodsImplicitlyAsync() {
        Ast.Program program = Parser.parse("""
                actor fnc worker() => void {
                  return;
                }

                shared actor Inbox {
                  pub receive_message(int value) => void {
                    return;
                  }
                }
                """);

        Ast.FunctionDecl callable =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(0);
        Ast.ClassDecl klass =
                (Ast.ClassDecl) program.modules().getFirst().declarations().get(1);

        assertTrue(callable.async());
        assertTrue(klass.methods().getFirst().async());
    }

    @Test
    void explicitAsyncActorSpellingsAreRejectedAsRedundant() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                async actor fnc worker() => void {
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared actor Inbox {
                  pub async receive_message(int value) => void {
                    return;
                  }
                }
                """));
    }

    @Test
    void asyncLambdaCarriesSuspensionEffectAndRequiresFutureContext() {
        Ast.Program parsed = Parser.parse("""
                fnc holder() => void {
                  val callback = async || -> {
                    return;
                  };
                  return;
                }
                """);

        Ast.FunctionDecl holder =
                (Ast.FunctionDecl) parsed.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt binding = (Ast.BindingStmt) holder.body().getFirst();
        Ast.LambdaExpr lambda = (Ast.LambdaExpr) binding.initializer();
        assertTrue(lambda.async());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc invoke(Fnc<int, Future<int>> callback) => Future<int> {
                  return callback(41);
                }

                fnc good() => Future<int> {
                  return invoke(async |value| -> {
                    return value + 1;
                  });
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invoke(Fnc<int, int> callback) => int {
                  return callback(41);
                }

                fnc bad() => int {
                  return invoke(async |value| -> {
                    return value + 1;
                  });
                }
                """)));
    }

    @Test
    void synchronousLambdaCannotHideAwaitInsideAsyncOuterCallable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        async fnc outer(Future<int> work) => void {
                          val callback = || -> {
                            val value = await work;
                            return;
                          };
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("await is only legal"));
    }
    @Test
    void sourceCanCreateSchedulerStartAsyncLambdaAndAwaitResult() throws Exception {
        String program = """
                pub async routine main() => void {
                  val scheduler = new OresScheduler(2);
                  val work = scheduler.start(async || -> {
                    val local = Mutex.new(41);
                    val guard = await local.lock_async();
                    guard.release();
                    return 41;
                  });
                  val value = await work;
                  stdio.println(value);
                  stdio.println(scheduler.parallelism());
                  scheduler.close();
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "scheduler-source.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("41"));
        assertTrue(rendered.contains("2"));
    }

    @Test
    void schedulerStartAcceptsSyncOrAsyncButRequiresZeroArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc good_sync() => void {
                  val scheduler = new OresScheduler(2);
                  val work = scheduler.start(|| -> {
                    return;
                  });
                  scheduler.close();
                  return;
                }

                async fnc good_async() => void {
                  val scheduler = new OresScheduler(2);
                  val work = scheduler.start(async || -> {
                    return;
                  });
                  await work;
                  scheduler.close();
                  return;
                }
                """)));

        IllegalArgumentException parameterized = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          val scheduler = new OresScheduler(2);
                          val work = scheduler.start(async |value| -> {
                            return value;
                          });
                          scheduler.close();
                          return;
                        }
                        """)));
        assertTrue(parameterized.getMessage().contains("zero-argument"));
    }

    @Test
    void actorsCannotCreateOrReceiveOrdinarySchedulers() {
        IllegalArgumentException create = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad() => void {
                          val scheduler = new OresScheduler(2);
                          return;
                        }
                        """)));
        assertTrue(create.getMessage().contains("actors cannot create OresScheduler"));

        IllegalArgumentException receive = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad(OresScheduler scheduler) => void {
                          return;
                        }
                        """)));
        assertTrue(receive.getMessage().contains("cannot transport OresScheduler"));
    }

    @Test
    void schedulerTaskMutableCaptureMovesOwnershipOutOfParent() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          let counter = 0;
                          val scheduler = new OresScheduler(2);
                          val work = scheduler.start(async || -> {
                            counter = counter + 1;
                            return;
                          });
                          counter = counter + 1;
                          scheduler.close();
                          return;
                        }
                        """)));

        assertTrue(
                failure.getMessage().contains("moved")
                        || failure.getMessage().contains("capture"),
                () -> "unexpected ownership error: " + failure.getMessage());
    }

    @Test
    void schedulerTaskCannotCaptureLockGuardAcrossPoolBoundary() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          val mutex = Mutex.new(1);
                          val guard = mutex.lock();
                          val scheduler = new OresScheduler(2);
                          val work = scheduler.start(async || -> {
                            guard.release();
                            return;
                          });
                          scheduler.close();
                          return;
                        }
                        """)));

        assertTrue(
                failure.getMessage().contains("guard")
                        || failure.getMessage().contains("capture"),
                () -> "unexpected guard-capture error: " + failure.getMessage());
    }

    @Test
    void synchronousSchedulerTaskCannotRaceMutableParentCapture() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          let counter = 0;
                          val scheduler = new OresScheduler(2);
                          val work = scheduler.start(|| -> {
                            counter = counter + 1;
                            return;
                          });
                          counter = counter + 1;
                          scheduler.close();
                          return;
                        }
                        """)));

        assertTrue(
                failure.getMessage().contains("moved")
                        || failure.getMessage().contains("capture"),
                () -> "unexpected sync scheduler ownership error: " + failure.getMessage());
    }

    @Test
    void synchronousSchedulerTaskCannotCaptureLinearGuard() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          val mutex = Mutex.new(1);
                          val guard = mutex.lock();
                          val scheduler = new OresScheduler(2);
                          val work = scheduler.start(|| -> {
                            guard.release();
                            return;
                          });
                          scheduler.close();
                          return;
                        }
                        """)));

        assertTrue(
                failure.getMessage().contains("guard")
                        || failure.getMessage().contains("capture"),
                () -> "unexpected sync scheduler guard error: " + failure.getMessage());
    }

    @Test
    void synchronousSchedulerTaskCannotAwait() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        async fnc bad() => void {
                          val scheduler = new OresScheduler(1);
                          val work = scheduler.start(|| -> {
                            val ready = Future.from_callback<int>(|cb| -> {
                              cb.resolve(1);
                              return;
                            });
                            val value = await ready;
                            return;
                          });
                          await work;
                          scheduler.close();
                          return;
                        }
                        """)));

        assertTrue(
                failure.getMessage().contains("await is only legal")
                        || failure.getMessage().contains("async"),
                () -> "unexpected sync scheduler await error: " + failure.getMessage());
    }

    @Test
    void synchronousSchedulerTaskRunsOnCustomPoolAndReturnsFuture() throws Exception {
        String program = """
                pub async routine main() => void {
                  val scheduler = new OresScheduler(2);
                  val work = scheduler.start(|| -> {
                    return 7;
                  });
                  val value = await work;
                  stdio.println(value);
                  scheduler.close();
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "scheduler-sync.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("7"));
    }

    @Test
    void schedulerStartPreservesInferredLambdaResultType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc sync_work() => Future<int> {
                  val scheduler = new OresScheduler(2);
                  return scheduler.start(|| -> {
                    return 7;
                  });
                }

                fnc async_work() => Future<int> {
                  val scheduler = new OresScheduler(2);
                  return scheduler.start(async || -> {
                    return 41;
                  });
                }
                """)));

        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => Future<string> {
                          val scheduler = new OresScheduler(2);
                          return scheduler.start(async || -> {
                            return 41;
                          });
                        }
                        """)));
        assertTrue(mismatch.getMessage().contains("return"));
    }

}
