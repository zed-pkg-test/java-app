package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class QuantumExecutionTest {
    @Test
    void quantumIsAReservedExplicitExecutionTarget() {
        Ast.Program program = Parser.parse("""
                quantum fnc qpu(int shots): int {
                  return shots;
                }

                fnc cpu(int value): int {
                  return value;
                }
                """);

        Ast.FunctionDecl qpu = (Ast.FunctionDecl) program.modules().getFirst().declarations().get(0);
        Ast.FunctionDecl cpu = (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);

        assertEquals(Ast.ExecutionTarget.QUANTUM, Ast.executionTarget(qpu.annotations()));
        assertTrue(Ast.hasQuantumPlacement(qpu.annotations()));
        assertFalse(Ast.hasGpuPlacement(qpu.annotations()));
        assertEquals(Ast.ExecutionTarget.CPU, Ast.executionTarget(cpu.annotations()));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): void {
                  val quantum = 1;
                  return;
                }
                """));
    }

    @Test
    void modifierOrderIsFlexibleButQuantumSurfaceIsConservative() {
        assertDoesNotThrow(() -> Parser.parse("""
                quantum pub fnc first(int shots): int { return shots; }
                pub quantum fnc second(int shots): int { return shots; }

                define class QuantumOps as
                  pub quantum static fnc solve(int shots): int { return shots; }
                  static quantum fnc solve2(int shots): int { return shots; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("quantum quantum fnc bad(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("quantum routine bad(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("quantum async fnc bad(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("quantum generator fnc bad(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("quantum nlex fnc bad(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("quantum structural fnc bad(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("quantum actor fnc bad(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("quantum fnc bad = || -> void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("""
                        define class Bad as
                          quantum run(): void { return; }
                        end
                        """));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("""
                        quantum define class Bad as
                        end
                        """));
    }

    @Test
    void executionTargetMetadataFailsClosedOnConflictingPlacement() {
        List<Ast.Annotation> annotations = List.of(
                new Ast.Annotation(Ast.GPU_ANNOTATION, List.of()),
                new Ast.Annotation(Ast.QUANTUM_ANNOTATION, List.of()));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Ast.executionTarget(annotations));
        assertTrue(error.getMessage().contains("both GPU and quantum"));
    }

    @Test
    void quantumInvocationNeverFallsBackToCpuOrGpu() throws Exception {
        String program = """
                quantum fnc kernel(int value): int {
                  return value + 1;
                }

                pub routine main(): void {
                  kernel(41);
                  return;
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "quantum-no-fallback.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> context.eval(source));
            assertTrue(failure.getMessage().contains("no QPU backend")
                    || failure.getMessage().contains("never falls back to CPU or GPU"));
        }
    }

    @Test
    void quantumPlacementParticipatesInIncrementalAbi() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> cpu = Map.of(
                "service.ores", """
                        pub fnc solve(int shots): int {
                          return shots;
                        }
                        """,
                "consumer.ores", """
                        import fnc {solve} from "./service.ores";
                        pub routine main(): void { return; }
                        """);

        compiler.compile(cpu);

        Map<String, String> qpu = Map.of(
                "service.ores", """
                        pub quantum fnc solve(int shots): int {
                          return shots;
                        }
                        """,
                "consumer.ores", cpu.get("consumer.ores"));

        var result = compiler.compile(qpu);
        assertTrue(result.rebuilt("service.ores"));
        assertTrue(result.rebuilt("consumer.ores"),
                "CPU -> QPU placement changes the exported execution contract");
    }
}
