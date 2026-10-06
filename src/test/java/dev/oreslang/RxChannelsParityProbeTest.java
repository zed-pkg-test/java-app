package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(90)
final class RxChannelsParityProbeTest {
    @TempDir Path temp;

    @Test
    void cancellingOneSharedFutureSubscriptionDoesNotCancelProducer() throws Exception {
        assertEquals("true\ntrue\n99", run("shared_future", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';

pub async fnc main(): void {
  val Channel<int> gate = Channel.new<int>(0);
  val Future<int> shared = nb readch gate;
  val Observable<int> source = rx_channels.from_future(shared);
  val Subscription<int> one = source.subscribe();
  val Subscription<int> two = source.subscribe();

  stdio.println(one.cancel());
  val Future<void> release = nb writech gate, 99;
  val Option<int> second = two.next();
  await release;
  val Option<int> cancelled = one.next();

  stdio.println(cancelled.is_none());
  stdio.println(second.unwrap());
  return;
}
""").strip());
    }

    @Test
    void subscriptionCannotBeAliasedAcrossAsyncTaskBoundary() throws Exception {
        Throwable failure = assertThrows(Throwable.class, () -> run("task_safety", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';

async fnc pull_one(Subscription<int> sub): Option<int> {
  return sub.next();
}

pub async fnc main(): void {
  val Observable<int> source = rx_channels.from_values([7]);
  val Subscription<int> sub = source.subscribe();
  val Future<Option<int>> pending = pull_one(sub);
  val Option<int> value = await pending;
  stdio.println(value.unwrap());
  return;
}
"""));
        assertTrue(messages(failure).contains("must be concrete owned task-safe data"), messages(failure));
    }

    @Test
    void firstOnEmptyCurrentlyReturnsNoneRatherThanFailing() throws Exception {
        assertEquals("true", run("first_empty", """
import module rx_channels from '../src/rx.ores';
import class Observable from '../src/rx.ores';

pub async fnc main(): void {
  val Observable<int> empty = rx_channels.from_values([]);
  val Option<int> first = rx_channels.first(empty);
  stdio.println(first.is_none());
  return;
}
""").strip());
    }

    @Test
    void completionSentinelDoesNotCloseProducerChannel() throws Exception {
        assertEquals("1\ntrue\n2", run("terminal_sentinel", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';

pub async fnc main(): void {
  val Channel<Option<int>> input = Channel.new<Option<int>>(3);

  val Future<void> first_write = rx_channels.send(input, 1);
  await first_write;
  val Future<void> finishing = rx_channels.complete(input);
  await finishing;
  val Future<void> trailing = rx_channels.send(input, 2);
  await trailing;

  val Observable<int> source = rx_channels.from_channel(input);
  val Subscription<int> sub = source.subscribe();
  val Option<int> first = sub.next();
  val Option<int> terminal = sub.next();
  val Option<int> trailing_frame = readch input;

  stdio.println(first.unwrap());
  stdio.println(terminal.is_none());
  stdio.println(trailing_frame.unwrap());
  return;
}
""").strip());
    }

    private String run(String name, String program) throws Exception {
        Path src = temp.resolve(name).resolve("src");
        Path tests = temp.resolve(name).resolve("tests");
        Files.createDirectories(src);
        Files.createDirectories(tests);
        Files.copy(Path.of("src/test/resources/rx-channels/rx.ores"), src.resolve("rx.ores"));
        Path entry = tests.resolve(name + ".ores");
        Files.writeString(entry, program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), output, new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String messages(Throwable failure) {
        StringBuilder out = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) out.append(current.getMessage()).append("\n");
        }
        return out.toString();
    }
}
