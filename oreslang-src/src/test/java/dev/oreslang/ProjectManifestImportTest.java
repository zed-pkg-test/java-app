package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectManifestImportTest {
    @TempDir Path temp;

    @Test
    void manifestSourceRootsResolveBareImportsThroughCompileAndRuntimeLinking() throws Exception {
        Path project = temp.resolve("project");
        Path src = project.resolve("src");
        Files.createDirectories(src.resolve("pkg"));
        Path main = src.resolve("main.ores");

        Files.writeString(project.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"

                [project]
                name = "manifest-demo"
                root = "."

                [source]
                roots = ["src"]

                [entrypoints]
                main = "src/main.ores"
                """);
        Files.writeString(src.resolve("pkg/greeting.ores"), """
                pub fnc greeting() => string {
                  return "manifest-path";
                }
                """);
        Files.writeString(main, """
                import fnc greeting from "pkg/greeting";

                pub fnc main() => void {
                  stdio.println(greeting());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals(2, build.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("manifest-path"));
    }

    @Test
    void oreslangPathResolvesBareImportsWithoutAManifest() throws Exception {
        Path app = temp.resolve("app");
        Path shared = temp.resolve("shared-root");
        Files.createDirectories(app);
        Files.createDirectories(shared.resolve("common"));
        Path main = app.resolve("main.ores");

        Files.writeString(shared.resolve("common/message.ores"), """
                pub fnc message() => string {
                  return "oreslang-path";
                }
                """);
        Files.writeString(main, """
                import fnc message from "common/message";

                pub fnc main() => void {
                  stdio.println(message());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH, shared.toString()),
                out,
                new ByteArrayOutputStream());

        assertEquals(2, build.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("oreslang-path"));
    }
}
