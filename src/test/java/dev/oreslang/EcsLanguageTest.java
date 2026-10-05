package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.EcsEffectAnalyzer;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class EcsLanguageTest {
    @Test
    void parsesStrictDataOnlyComponentsAndExtractsSystemEffects() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub define component Position as
                  x: f32
                  y: f32
                end

                pub define component Velocity as
                  x: f32
                  y: f32
                end

                @system
                @reads(Velocity)
                @writes(Position)
                @gpu_eligible
                pub fnc movement(Query<Position, Velocity> rows): void {
                  return;
                }
                """);

        assertInstanceOf(Ast.ComponentDecl.class, program.modules().getFirst().declarations().getFirst());
        Ast.ComponentDecl position =
                (Ast.ComponentDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.Visibility.PUBLIC, position.visibility());
        assertEquals(List.of("x", "y"), position.fields().stream().map(Ast.FieldDecl::name).toList());

        Map<String, EcsEffectAnalyzer.SystemEffects> effects = OresCompiler.ecsEffects(program);
        EcsEffectAnalyzer.SystemEffects movement = effects.get("movement");
        assertNotNull(movement);
        assertEquals(Set.of("Velocity"), movement.reads());
        assertEquals(Set.of("Position"), movement.writes());
        assertTrue(movement.gpuEligible());
        assertFalse(movement.structuralCommands());
    }

    @Test
    void systemPlannerSeparatesConflictsAndCoSchedulesIndependentWrites() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                define component Position as
                  x: f32
                end
                define component Velocity as
                  x: f32
                end
                define component Health as
                  value: f32
                end

                @system
                @reads(Velocity)
                @writes(Position)
                fnc movement(Query<Position, Velocity> rows): void {
                  return;
                }

                @system
                @writes(Health)
                fnc regen(Query<Health> rows): void {
                  return;
                }

                @system
                @reads(Position)
                fnc render(Query<Position> rows): void {
                  return;
                }
                """);

        List<List<EcsEffectAnalyzer.SystemEffects>> batches = OresCompiler.ecsParallelBatches(program);
        assertEquals(List.of("movement", "regen"),
                batches.getFirst().stream().map(EcsEffectAnalyzer.SystemEffects::qualifiedName).toList());
        assertEquals(List.of("render"),
                batches.get(1).stream().map(EcsEffectAnalyzer.SystemEffects::qualifiedName).toList());
    }

    @Test
    void componentsRejectBehaviorInitializersAndNonPodState() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define component Bad as
                  x: f32 = 1
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define component Bad as
                  run() {
                    return;
                  }
                end
                """));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define component Bad as
                          name: String
                        end
                        """));
        assertTrue(error.getMessage().contains("non-POD"));
    }

    @Test
    void systemsRequireExactQueryEffectsAndDisallowConflictingAccess() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define component Position as
                  x: f32
                end
                @system
                @reads(Position)
                @writes(Position)
                fnc bad(Query<Position> rows): void {
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define component Position as
                  x: f32
                end
                define component Velocity as
                  x: f32
                end
                @system
                @writes(Position)
                fnc bad(Query<Position, Velocity> rows): void {
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define component Position as
                  x: f32
                end
                @writes(Position)
                fnc bad(Query<Position> rows): void {
                  return;
                }
                """));
    }

    @Test
    void gpuEligibleSystemsCannotPerformStructuralMutation() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define component Health as
                  value: f32
                end
                @system
                @writes(Health)
                @gpu_eligible
                fnc damage(Query<Health> rows, Commands commands): void {
                  return;
                }
                """));
    }
}
