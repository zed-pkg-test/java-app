package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SourceActorRequestExecutionTest {
    @Test
    void privateRunReturnsTypedReplyAndPersistsActorLocalState() throws Exception {
        assertEquals("5|12", run("""
                define actor Worker as
                  let int count;

                  on_start(): void {
                    self.count = 0;
                    return;
                  }

                  fnc run(int job): int {
                    self.count = self.count + job;
                    return self.count;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  val a: int = await worker.request(5);
                  val b: int = await worker.request(7);
                  stdio.stdout.write(a);
                  stdio.stdout.write("|");
                  stdio.stdout.write(b);
                  worker.stop();
                  await worker.done;
                  return;
                }
                """));
    }

    @Test
    void annotatedInheritedUnaryHandlerUsesRuntimeDispatch() throws Exception {
        assertEquals("6", run("""
                define actor BaseWorker as
                  @Implementation
                  fnc run(int value): int {
                    return value;
                  }
                end

                define actor Worker extends BaseWorker as
                  @Implementation
                  @Override
                  fnc run(int value): int {
                    return value + 1;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  val result = await worker.request(5);
                  stdio.stdout.write(result);
                  worker.stop();
                  await worker.done;
                  return;
                }
                """));
    }

    @Test
    void requestSuspensionResumesBeforeFollowingRequest() throws Exception {
        assertEquals("3|7", run("""
                define isoactor Worker as
                  let int count;

                  on_start(): void {
                    self.count = 0;
                    return;
                  }

                  fnc run(int delta): int {
                    self.count = self.count + delta;
                    rt cooperate;
                    return self.count;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  val first = worker.request(3);
                  val second = worker.request(4);
                  stdio.stdout.write(await first);
                  stdio.stdout.write("|");
                  stdio.stdout.write(await second);
                  worker.stop();
                  await worker.done;
                  return;
                }
                """));
    }

    @Test
    void eventActorsCannotBeInvokedAsRequestActors() {
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<int> mail): void { return; }
                        end
                        pub async routine main(): void {
                          val worker = spawn Worker();
                          val outcome = await worker.request(3);
                          return;
                        }
                        """)));
        assertTrue(bad.getMessage().contains("private run"), bad.getMessage());
    }

    @Test
    void requestActorsCannotBeUsedThroughOneWaySend() {
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          fnc run(int job): int { return job; }
                        end
                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send(3);
                          return;
                        }
                        """)));
        assertTrue(bad.getMessage().contains("receive(ActorMail"), bad.getMessage());
    }

    @Test
    void replyTypeMismatchIsRejectedAtCompileTime() {
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          fnc run(int job): int { return job; }
                        end
                        pub async routine main(): void {
                          val worker = spawn Worker();
                          val wrong: String = await worker.request(3);
                          return;
                        }
                        """)));
        assertTrue(bad.getMessage().contains("assignable")
                        || bad.getMessage().contains("String")
                        || bad.getMessage().contains("type"), bad.getMessage());
    }

    private static String run(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, text,
                    "source-actor-request.ores").mimeType(OresLanguage.MIME_TYPE).build());
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
