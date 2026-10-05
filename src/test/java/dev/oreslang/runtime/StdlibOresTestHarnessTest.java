package dev.oreslang.runtime;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic host bootstrap for Oreslang-native stdlib tests.
 *
 * <p>Assertions, suites, filtering, reporting, and failure policy live in
 * std/testing. Java only discovers .ores programs and executes them through the
 * normal linked-program path. New stdlib packages should add Oreslang tests
 * under src/test/oreslang/stdlib rather than creating package-specific JUnit
 * harnesses.</p>
 */
final class StdlibOresTestHarnessTest {

    @TestFactory
    Stream<DynamicTest> stdlibOresPrograms() throws IOException {
        Path root = Path.of("src", "test", "oreslang", "stdlib");
        assertTrue(Files.isDirectory(root), "missing Oreslang stdlib test directory");

        final List<Path> programs;
        try (Stream<Path> files = Files.walk(root)) {
            programs = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ores"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        assertTrue(!programs.isEmpty(), "stdlib harness must discover at least one .ores test program");
        return programs.stream().map(path ->
                DynamicTest.dynamicTest(root.relativize(path).toString(), () -> runProgram(path)));
    }

    private static void runProgram(Path source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        var build = assertDoesNotThrow(
                () -> LinkedProgramRunner.run(
                        source,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        out,
                        err),
                () -> "Oreslang stdlib test crashed: " + source
                        + "\nstderr:\n" + err.toString(StandardCharsets.UTF_8));

        assertTrue(build.units().containsKey("std/testing.ores"),
                () -> source + " must execute through the bundled std/testing harness");

        String stdout = out.toString(StandardCharsets.UTF_8);
        String stderr = err.toString(StandardCharsets.UTF_8);
        String summary = stdout.lines()
                .filter(line -> line.startsWith("ORES_TEST|SUMMARY|"))
                .reduce((ignored, latest) -> latest)
                .orElse("");

        assertTrue(!summary.isEmpty(),
                () -> source + " produced no ORES_TEST summary\nstdout:\n" + stdout
                        + "\nstderr:\n" + stderr);
        assertTrue(summary.contains("|failed=0|"),
                () -> source + " reported failing Oreslang tests: " + summary
                        + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr);
    }
}
