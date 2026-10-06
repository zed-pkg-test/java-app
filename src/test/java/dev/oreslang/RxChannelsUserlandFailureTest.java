package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
final class RxChannelsUserlandFailureTest {
    @TempDir Path temp;
    private record Case(String name, String source, String expected) {}
    private static final List<Case> CASES = List.of(
        new Case("failure", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';
async fnc failed(): int {
  val Result<int, String> error = Err("boom");
  return error.expect("source failed");
}
pub async fnc main(): void {
  val Future<int> pending = failed();
  val Observable<int> source = rx_channels.from_future(pending);
  val Subscription<int> sub = source.subscribe();
  val Option<int> value = sub.next();
  stdio.println("unexpected success");
  return;
}

""", "source failed: boom"),
        new Case("negative_take", """
import module rx_channels from '../src/rx.ores';
import class Observable, Subscription from '../src/rx.ores';
pub async fnc main(): void {
  val Observable<int> source = rx_channels.from_values([1]);
  val Observable<int> limited = rx_channels.take(source, -1);
  val Subscription<int> sub = limited.subscribe();
  return;
}

""", "take count must be nonnegative")
    );

    @Test
    void upstreamFailureProgramsFailForExpectedReason() throws Exception {
        Path src = temp.resolve("src");
        Path tests = temp.resolve("tests");
        Files.createDirectories(src);
        Files.createDirectories(tests);
        Files.copy(Path.of("src/test/resources/rx-channels/rx.ores"), src.resolve("rx.ores"));
        for (Case c : CASES) {
            Path entry = tests.resolve(c.name() + ".ores");
            Files.writeString(entry, c.source());
            Throwable failure = assertThrows(Throwable.class, () -> run(entry), c.name());
            assertTrue(messages(failure).contains(c.expected()),
                    c.name() + ": " + messages(failure));
        }
    }

    private static void run(Path entry) throws Exception {
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), new ByteArrayOutputStream(), new ByteArrayOutputStream());
    }

    private static String messages(Throwable failure) {
        StringBuilder out = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) out.append(current.getMessage()).append("\n");
        }
        return out.toString();
    }
}
