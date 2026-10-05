package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ImportKindsTest {
    @TempDir Path temp;

    @Test
    void parserAcceptsTypedActorAndEquivalentTypeLists() {
        Ast.Program program = Parser.parse("""
                import actor worker from "./actors.ores";
                import class Box from "./classes.ores";
                import interface Shape from "./types.ores";
                import type Count from "./types.ores";
                import types A, B, C from "./types.ores";
                import types (D, E, F) from "./types.ores";
                import trait FutureTrait from "./future.ores";
                import struct FutureStruct from "./future.ores";
                import * as all from "./everything.ores";

                pub routine main(): void { return; }
                """);

        assertEquals(Ast.ImportKind.ACTOR, program.imports().get(0).kind());
        assertEquals(Ast.ImportKind.CLASS, program.imports().get(1).kind());
        assertEquals(Ast.ImportKind.INTERFACE, program.imports().get(2).kind());
        assertEquals(Ast.ImportKind.TYPE, program.imports().get(3).kind());
        assertEquals(Ast.ImportKind.TYPES, program.imports().get(4).kind());
        assertEquals(java.util.List.of("A", "B", "C"), program.imports().get(4).names());
        assertEquals(Ast.ImportKind.TYPES, program.imports().get(5).kind());
        assertEquals(java.util.List.of("D", "E", "F"), program.imports().get(5).names());
        assertEquals(Ast.ImportKind.TRAIT, program.imports().get(6).kind());
        assertEquals(Ast.ImportKind.STRUCT, program.imports().get(7).kind());
        assertEquals(Ast.ImportKind.ALL, program.imports().get(8).kind());

        assertDoesNotThrow(() -> Parser.parse("""
                pub routine main(): void {
                  val int types = 1;
                  stdio.stdout.write(types);
                  return;
                }
                """));
    }

    @Test
    void linkedTypesFeedTheImporterTypeChecker() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("/app/types.ores", """
                pub interface HasValue {
                  value: int;
                }

                type Count = int;
                """);
        sources.put("/app/main.ores", """
                import types HasValue, Count from "./types.ores";

                define class Box implements HasValue as
                  pub let int value = 1;
                end

                pub fnc identity(Count value): Count {
                  return value;
                }
                """);

        var build = new IncrementalCompiler().compile(sources);
        assertEquals(2, build.units().size());
    }

    @Test
    void classImportConstructsAcrossFiles() throws Exception {
        Path defs = temp.resolve("classes.ores");
        Path main = temp.resolve("main-class.ores");
        Files.writeString(defs, """
                define class Box as
                  pub let int value = 0;
                end
                """);
        Files.writeString(main, """
                import class Box from "./classes.ores";

                pub routine main(): void {
                  val box = new Box(7);
                  stdio.stdout.write(box.value);
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());
        assertEquals("7", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void actorImportPreservesSchedulerDispatch() throws Exception {
        Path defs = temp.resolve("actors.ores");
        Path main = temp.resolve("main-actor.ores");
        Files.writeString(defs, """
                pub actor fnc add_one(int value): int {
                  return value + 1;
                }
                """);
        Files.writeString(main, """
                import actor add_one from "./actors.ores";

                pub routine main(): void {
                  stdio.stdout.write(add_one(41));
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void importKindsFailClosedOnWrongDeclarationCategory() {
        Map<String, String> sources = Map.of(
                "/app/defs.ores", """
                        define class Box as
                        end

                        pub actor fnc worker(): void { return; }
                        """,
                "/app/main.ores", """
                        import class worker from "./defs.ores";
                        import actor Box from "./defs.ores";
                        pub routine main(): void { return; }
                        """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("does not match an exported declaration"));
    }

    @Test
    void futureTraitAndStructImportsParseButFailClosedUntilDeclarationsExist() {
        Map<String, String> sources = Map.of(
                "/app/defs.ores", "pub fnc placeholder(): void { return; }",
                "/app/main.ores", """
                        import trait MissingTrait from "./defs.ores";
                        import struct MissingStruct from "./defs.ores";
                        pub routine main(): void { return; }
                        """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("does not match an exported declaration"));
    }
}
