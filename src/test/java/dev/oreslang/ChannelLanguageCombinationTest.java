package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class ChannelLanguageCombinationTest {
    @Test
    void asyncAwaitComposesPendingReadAndWriteFutures() throws Exception {
        assertEquals("42", run("""
                async fnc transfer(): int {
                  val Channel<int> channel = Channel.new<int>(0);
                  val Future<int> read = nb readch channel;
                  val Future<void> write = nb writech channel, 42;
                  await write;
                  return await read;
                }
                pub async fnc main(): void {
                  stdio.stdout.write(await transfer());
                  return;
                }
                """));
    }

    @Test
    void asyncAwaitComposesPendingDynamicSelectWithWriteFuture() throws Exception {
        assertEquals("42", run("""
                pub async fnc main(): void {
                  val Channel<int> channel = Channel.new<int>(0);
                  val Future<void> written = nb writech channel, 42;
                  val Array<SelectCase> cases = [SelectCase.read(channel)];
                  val Future<SelectResult> selected = nb select from cases;
                  await written;
                  val result = await selected;
                  stdio.stdout.write(result.value);
                  return;
                }
                """));
    }

    @Test
    void blockingReadWriteAndSelectExecuteAgainstBufferedChannels() throws Exception {
        assertEquals("4142", run("""
                pub routine main(): void {
                  val Channel<int> channel = Channel.new<int>(1);
                  writech channel, 41;
                  stdio.stdout.write(readch channel);
                  select {
                  case writech channel, 42:
                    stdio.stdout.write(readch channel);
                  }
                  return;
                }
                """));
    }

    @Test
    void actorAndIsoactorCallablesExecuteChannelOperations() throws Exception {
        assertEquals("4142", run("""
                actor fnc worker_shared(): int {
                  val Channel<int> channel = Channel.new<int>(1);
                  writech channel, 41;
                  return readch channel;
                }
                isoactor fnc worker_private(): int {
                  val Channel<int> channel = Channel.new<int>(1);
                  writech channel, 42;
                  return readch channel;
                }
                fnc identity(int value): int { return value; }
                pub routine main(): void {
                  stdio.stdout.write(worker_shared());
                  stdio.stdout.write(identity(worker_private()));
                  return;
                }
                """));
    }

    @Test
    void actorNbCallbackWriteExecutesCompletionBeforeCallableTeardown() throws Exception {
        assertEquals("callback42", run("""
                actor fnc worker(): int {
                  val Channel<int> channel = Channel.new<int>(0);
                  nb cb writech channel, 42 || -> {
                    stdio.stdout.write("callback");
                  };
                  return readch channel;
                }
                pub routine main(): void {
                  stdio.stdout.write(worker());
                  return;
                }
                """));
    }

    @Test
    void actorNbSelectExecutesDetachedReadArmThroughActorMailbox() throws Exception {
        assertEquals("4242", run("""
                actor fnc worker(): int {
                  val Channel<int> channel = Channel.new<int>(0);
                  nb select {
                  case readch channel: val value
                    stdio.stdout.write(value);
                  }
                  val Future<void> written = nb writech channel, 42;
                  await written;
                  return 42;
                }
                pub routine main(): void {
                  stdio.stdout.write(worker());
                  return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "channel-combinations.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
