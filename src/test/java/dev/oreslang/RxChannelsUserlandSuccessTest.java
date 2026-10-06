package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(90)
final class RxChannelsUserlandSuccessTest {
    @TempDir Path temp;
    private record Case(String name, String source, String expected) {}
    private static final List<Case> CASES = List.of(
        new Case("smoke", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';
pub async fnc main(): void {
  val Channel<Option<int>> input = Channel.new<Option<int>>(3);
  val Future<void> a = rx_channels.send(input, 10);
  await a;
  val Future<void> b = rx_channels.send(input, 20);
  await b;
  val Future<void> c = rx_channels.complete(input);
  await c;
  val Observable<int> source = rx_channels.from_channel(input);
  val Subscription<int> sub = source.subscribe();
  val Option<int> first = sub.next();
  stdio.println(first.unwrap());
  val Option<int> second = sub.next();
  stdio.println(second.unwrap());
  val Option<int> terminal = sub.next();
  stdio.println(terminal.is_none());
  sub.cancel();
  return;
}

""", """
10
20
true

"""),
        new Case("cold", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';
pub async fnc main(): void {
  val Observable<String> source = rx_channels.from_values(["a", "b"]);
  val Subscription<String> first = source.subscribe();
  val Subscription<String> second = source.subscribe();
  val Option<String> a = first.next();
  val Option<String> b = first.next();
  val Option<String> c = second.next();
  stdio.println(a.unwrap());
  stdio.println(b.unwrap());
  stdio.println(c.unwrap());
  first.cancel();
  second.cancel();
  val Observable<int> empty = rx_channels.from_values([]);
  val Subscription<int> none = empty.subscribe();
  val Option<int> terminal = none.next();
  stdio.println(terminal.is_none());
  return;
}

""", """
a
b
a
true

"""),
        new Case("operators", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';
pub async fnc main(): void {
  val Observable<int> source = rx_channels.from_values([1, 2, 3, 4, 5]);
  val Fnc<int, int> twice = |x| -> { return x * 2; };
  val Observable<int> doubled = rx_channels.map(source, twice);
  val Fnc<&int, bool> large = |x| -> { val int value = x as int; return value > 4; };
  val Observable<int> selected = rx_channels.filter(doubled, large);
  val Observable<int> limited = rx_channels.take(selected, 2);
  val Subscription<int> sub = limited.subscribe();
  val Option<int> first = sub.next();
  val Option<int> second = sub.next();
  val Option<int> terminal = sub.next();
  stdio.println(first.unwrap());
  stdio.println(second.unwrap());
  stdio.println(terminal.is_none());
  stdio.println(sub.cancel());
  stdio.println(sub.cancel());
  return;
}

""", """
6
8
true
true
false

"""),
        new Case("cancel", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';
pub async fnc main(): void {
  val Channel<Option<int>> input = Channel.new<Option<int>>(2);
  writech input, Some(7);
  writech input, Some(8);
  val Observable<int> source = rx_channels.from_channel(input);
  val Subscription<int> sub = source.subscribe();
  stdio.println(sub.cancel());
  stdio.println(sub.cancel());
  val Option<int> terminal = sub.next();
  stdio.println(terminal.is_none());
  val Option<int> untouched = readch input;
  stdio.println(untouched.unwrap());
  val Observable<int> again = rx_channels.from_channel(input);
  val Observable<int> limited = rx_channels.take(again, 1);
  val Subscription<int> one = limited.subscribe();
  val Option<int> value = one.next();
  stdio.println(value.unwrap());
  // take(1) cancels immediately after yielding one value, without another pull.
  writech input, Some(9);
  val Option<int> done_frame = one.next();
  stdio.println(done_frame.is_none());
  val Option<int> remaining = readch input;
  stdio.println(remaining.unwrap());
  return;
}

""", """
true
false
true
7
8
true
9

"""),
        new Case("merge", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';
pub async fnc main(): void {
  val Channel<Option<int>> left = Channel.new<Option<int>>(3);
  val Channel<Option<int>> right = Channel.new<Option<int>>(4);
  writech left, Some(1);
  writech left, Some(3);
  val Option<int> terminal = None;
  writech left, terminal;
  writech right, Some(2);
  writech right, Some(4);
  writech right, Some(6);
  writech right, terminal;
  val Observable<int> merged = rx_channels.merge_channels(left, right);
  val Subscription<int> sub = merged.subscribe();
  loop {
    val Option<int> frame = sub.next();
    if frame.is_none(); then
      break;
    fi
    stdio.println(frame.unwrap());
  }
  stdio.println("complete");
  return;
}

""", """
1
2
3
4
6
complete

"""),
        new Case("rendezvous", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';
pub async fnc main(): void {
  val Channel<Option<int>> input = Channel.new<Option<int>>(0);
  val Future<void> producing = rx_channels.send(input, 42);
  val Observable<int> source = rx_channels.from_channel(input);
  val Subscription<int> sub = source.subscribe();
  val Option<int> value = sub.next();
  stdio.println(value.unwrap());
  await producing;
  val Future<void> finishing = rx_channels.complete(input);
  val Option<int> terminal = sub.next();
  stdio.println(terminal.is_none());
  await finishing;
  return;
}

""", """
42
true

"""),
        new Case("future", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';
async fnc answer(): int { return 42; }
pub async fnc main(): void {
  val Future<int> pending = answer();
  val Observable<int> source = rx_channels.from_future(pending);
  val Subscription<int> sub = source.subscribe();
  val Option<int> value = sub.next();
  stdio.println(value.unwrap());
  val Option<int> terminal = sub.next();
  stdio.println(terminal.is_none());
  val Observable<int> cold = rx_channels.from_values([7, 8]);
  val Option<int> first = rx_channels.first(cold);
  stdio.println(first.unwrap());
  return;
}

""", """
42
true
7

"""),
        new Case("take_zero", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';
pub async fnc main(): void {
  val Channel<Option<int>> input = Channel.new<Option<int>>(1);
  writech input, Some(11);
  val Observable<int> source = rx_channels.from_channel(input);
  val Observable<int> limited = rx_channels.take(source, 0);
  val Subscription<int> sub = limited.subscribe();
  val Option<int> terminal = sub.next();
  stdio.println(terminal.is_none());
  val Option<int> remaining = readch input;
  stdio.println(remaining.unwrap());
  return;
}

""", """
true
11

""")
    );

    @Test
    void upstreamSuccessProgramsPassAgainstPost308Compiler() throws Exception {
        Path src = temp.resolve("src");
        Path tests = temp.resolve("tests");
        Files.createDirectories(src);
        Files.createDirectories(tests);
        Files.copy(Path.of("src/test/resources/rx-channels/rx.ores"), src.resolve("rx.ores"));
        for (Case c : CASES) {
            Path entry = tests.resolve(c.name() + ".ores");
            Files.writeString(entry, c.source());
            assertEquals(c.expected().strip(), run(entry).strip(), c.name());
        }
    }

    private String run(Path entry) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), output, new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }
}
