package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class LinkedProgramRunnerActorTest {

    @TempDir
    Path tempDir;

    @Test
    void officialLauncherRootMaySpawnThroughItsContextsRuntimeOnSameVm() throws Exception {
        Path source = tempDir.resolve("actor-launcher.ores");
        Files.writeString(source, """
                pub actor fnc shared_double(int value) => int {
                  return value * 2;
                }

                pub isoactor fnc private_add_one(int value) => int {
                  return value + 1;
                }

                pub routine main() => void {
                  val shared_pending = spawn shared_double(21);
                  val private_pending = spawn private_add_one(41);
                  stdio.println(await shared_pending.result);
                  stdio.println(await private_pending.result);
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertDoesNotThrow(() -> LinkedProgramRunner.run(
                source,
                IsolatePolicy.developer(),
                ExecutionProfile.parse("jit", "linux"),
                output,
                System.err));

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("42"), rendered);
    }

    @Test
    void controlRootCannotCrossToActorRuntimeOwnedByDifferentVm() {
        ActorRuntime processRuntime = ActorRuntime.processShared(
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct());
        ActorRuntime dedicated = new ActorRuntime();

        try {
            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> processRuntime.executeRootTask(() -> {
                        dedicated.syncCell(1);
                        return null;
                    }));
            assertTrue(denied.getMessage().contains("unrelated"));
        } finally {
            dedicated.close();
            processRuntime.close();
        }
    }

    @Test
    void controlRootCannotUseSameVmRuntimeToWidenItsPolicy() {
        ActorRuntime narrowRoot = ActorRuntime.processShared(
                IsolatePolicy.strictFaas(),
                ActorRuntime.TurnExecutor.direct());
        ActorRuntime privilegedTarget = ActorRuntime.processShared(
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct());

        try {
            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> narrowRoot.executeRootTask(() -> {
                        privilegedTarget.shareReadonly("denied");
                        return null;
                    }));
            assertTrue(denied.getMessage().contains("more-privileged"));
        } finally {
            privilegedTarget.close();
            narrowRoot.close();
        }
    }
}
