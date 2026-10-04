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
    void schedulerStartRequiresInlineAsyncZeroArgumentLambda() {
        IllegalArgumentException synchronous = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          val scheduler = new OresScheduler(2);
                          val work = scheduler.start(|| -> {
                            return;
                          });
                          scheduler.close();
                          return;
                        }
                        """)));
        assertTrue(synchronous.getMessage().contains("inline async zero-argument lambda"));

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

}
