package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                pub fnc greeting() : string {
                  return "manifest-path";
                }
                """);
        Files.writeString(main, """
                import fnc greeting from "pkg/greeting";

                pub fnc main() : void {
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
    void importedWildcardActorCallableCannotBypassActorCompositionBoundary() throws Exception {
        Path app = temp.resolve("actor-import");
        Files.createDirectories(app);
        Path child = app.resolve("child.ores");
        Path main = app.resolve("main.ores");

        Files.writeString(child, """
                pub actor fnc child(int value): int {
                  return value + 1;
                }
                """);

        Files.writeString(main, """
                import * as external from "./child.ores";

                actor fnc parent(int value): int {
                  return external.child(value);
                }

                pub routine main(): void {
                  stdio.stdout.write(parent(1));
                  return;
                }
                """);

        PolyglotException failure = assertThrows(
                PolyglotException.class,
                () -> LinkedProgramRunner.run(
                        main,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));

        assertTrue(failure.getMessage().contains("mailbox-oriented actor composition"));
    }

    @Test
    void importedTailCallsPreserveCallerReturnContracts() throws Exception {
        Path app = temp.resolve("tail-contract-import");
        Files.createDirectories(app);
        Path child = app.resolve("child.ores");
        Path namedMain = app.resolve("named-main.ores");
        Path wildcardMain = app.resolve("wildcard-main.ores");

        Files.writeString(child, """
                pub fnc wrong(): String {
                  return "not-an-int";
                }
                """);

        Files.writeString(namedMain, """
                import fnc wrong from "./child.ores";

                fnc wrapped(): int {
                  return wrong();
                }

                pub routine main(): void {
                  stdio.stdout.write(wrapped());
                  return;
                }
                """);

        Files.writeString(wildcardMain, """
                import * as external from "./child.ores";

                fnc wrapped(): int {
                  return external.wrong();
                }

                pub routine main(): void {
                  stdio.stdout.write(wrapped());
                  return;
                }
                """);

        for (Path entry : List.of(namedMain, wildcardMain)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> LinkedProgramRunner.run(
                            entry,
                            IsolatePolicy.developer(),
                            ExecutionProfile.serverJit(),
                            Set.of(),
                            Map.of(),
                            new ByteArrayOutputStream(),
                            new ByteArrayOutputStream()));
            assertTrue(failure.getMessage().contains("declared int"));
        }
    }

    @Test
    void oreslangPathResolvesBareImportsWithoutAManifest() throws Exception {
        Path app = temp.resolve("app");
        Path shared = temp.resolve("shared-root");
        Files.createDirectories(app);
        Files.createDirectories(shared.resolve("common"));
        Path main = app.resolve("main.ores");

        Files.writeString(shared.resolve("common/message.ores"), """
                pub fnc message() : string {
                  return "oreslang-path";
                }
                """);
        Files.writeString(main, """
                import fnc message from "common/message";

                pub fnc main() : void {
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
