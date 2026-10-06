package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class StableDemoCorpusCompatibilityTest {
    private static final List<String> DEMOS = List.of(
            "00_hello.ores",
            "01_modules_namespaces_callables.ores",
            "02_structural_types.ores",
            "03_classes_and_methods.ores",
            "04_control_flow.ores",
            "05_ownership_and_borrows.ores",
            "06_closures_and_recursive_lambdas.ores",
            "07_iterators_and_scheduler_safepoints.ores",
            "08_typed_returns_and_destructuring.ores",
            "09_destructure_discards.ores",
            "10_inheritance_and_collections.ores",
            "11_generics_and_inference.ores",
            "12_generic_inheritance.ores",
            "13_logical_operators.ores",
            "14_bitwise_operators.ores");

    @Test
    void stableDemoCorpusParsesAndTypeChecks() {
        assertAll(DEMOS.stream().map(name -> () -> assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck(
                        Files.readString(Path.of("demo-examples", name))),
                name)));
    }
}
