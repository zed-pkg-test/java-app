package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class LinkedProgramRunnerTest {

    @Test
    void sandboxSafeOutputForwardsWithoutClosingCallerOwnedStream()
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicBoolean targetClosed = new AtomicBoolean();

        OutputStream target = new OutputStream() {
            @Override
            public void write(int value) {
                bytes.write(value);
            }

            @Override
            public void write(byte[] source, int offset, int length) {
                bytes.write(source, offset, length);
            }

            @Override
            public void close() {
                targetClosed.set(true);
            }
        };

        OutputStream redirected = LinkedProgramRunner.sandboxSafeOutput(target);
        assertNotSame(target, redirected,
                "UNTRUSTED sandbox must not receive System.out/System.err directly");

        redirected.write("ores".getBytes(StandardCharsets.UTF_8));
        redirected.flush();
        redirected.close();

        assertEquals("ores", bytes.toString(StandardCharsets.UTF_8));
        assertFalse(targetClosed.get(),
                "closing a Context must not close a caller-owned launcher stream");
    }

    @Test
    void sandboxSafeOutputRejectsNullTarget() {
        assertThrows(
                NullPointerException.class,
                () -> LinkedProgramRunner.sandboxSafeOutput(null));
    }
    @Test
    void linkedLauncherFailsClosedOnAsyncMainUntilStructuredLifecycleLowering(
            @TempDir Path tempDir) throws Exception {
        Path entry = tempDir.resolve("main.ores");
        Files.writeString(entry, """
                pub async fnc main() => int {
                  return 42;
                }
                """);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> LinkedProgramRunner.run(
                        entry,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));

        assertTrue(failure.getMessage().contains("async main"));
        assertTrue(failure.getMessage().contains("structured launcher/CPS"));
    }


}
