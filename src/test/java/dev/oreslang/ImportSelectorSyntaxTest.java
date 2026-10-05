package dev.oreslang;

import dev.oreslang.ast.Ast;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ImportSelectorSyntaxTest {
    @TempDir
    Path temp;

    @Test
    void commaAndParenthesizedTypeSelectionsAreEquivalent() {
        Ast.ImportDecl comma = Parser.parse("""
                import types X, Y, Z from '../foo';
                pub routine main(): void { return; }
                """).imports().getFirst();

        Ast.ImportDecl parenthesized = Parser.parse("""
                import types (X, Y, Z) from '../foo';
                pub routine main(): void { return; }
                """).imports().getFirst();

        assertEquals(Ast.ImportKind.TYPES, comma.kind());
        assertEquals(List.of("X", "Y", "Z"), comma.names());
        assertEquals(comma, parenthesized);
    }

    @Test
    void parsesTheCompleteExplicitImportVocabulary() {
        Ast.Program program = Parser.parse("""
                import * as package from './dep';
                import module Service from './dep';
                import actor Worker from './dep';
                import class Box from './dep';
                import fnc make from './dep';
                import interface Api from './dep';
                import trait Retryable from './dep';
                import struct Point from './dep';
                import type Identifier from './dep';
                import types A, B, C from './dep';

                pub routine main(): void { return; }
                """);

        assertEquals(List.of(
                        Ast.ImportKind.ALL,
                        Ast.ImportKind.MODULE,
                        Ast.ImportKind.ACTOR,
                        Ast.ImportKind.CLASS,
                        Ast.ImportKind.FUNCTION,
                        Ast.ImportKind.INTERFACE,
                        Ast.ImportKind.TRAIT,
                        Ast.ImportKind.STRUCT,
                        Ast.ImportKind.TYPE,
                        Ast.ImportKind.TYPES),
                program.imports().stream().map(Ast.ImportDecl::kind).toList());
    }

    @Test
    void linkerDistinguishesActorClassInterfaceAndTypeSelections() throws Exception {
        Path child = temp.resolve("models.ores");
        Path main = temp.resolve("main.ores");

        Files.writeString(child, """
                define module Service
                  pub fnc value(): int { return 1; }
                end

                shared actor Worker {
                  pub fnc value(): int { return 2; }
                }

                define class Box as
                end

                pub interface Api {
                  fnc value() => int;
                }

                pub interface ExtraApi {
                  fnc value() => int;
                }

                type Identifier = int;
                type OtherIdentifier = int;
                """);

        Files.writeString(main, """
                import module Service from './models';
                import actor Worker from './models';
                import class Box from './models';
                import interface Api from './models';
                import type Identifier from './models';
                import types ExtraApi, OtherIdentifier from './models';

                pub routine main(): void { return; }
                """);

        LinkedProgramRunner.validate(main);
    }

    @Test
    void classAndActorSelectorsDoNotAliasEachOther() throws Exception {
        Path child = temp.resolve("actors.ores");
        Files.writeString(child, """
                shared actor Worker {
                  pub fnc value(): int { return 1; }
                }

                define class Box as
                end
                """);

        Path actorAsClass = temp.resolve("actor-as-class.ores");
        Files.writeString(actorAsClass, """
                import class Worker from './actors';
                pub routine main(): void { return; }
                """);

        Path classAsActor = temp.resolve("class-as-actor.ores");
        Files.writeString(classAsActor, """
                import actor Box from './actors';
                pub routine main(): void { return; }
                """);

        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(actorAsClass));
        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(classAsActor));
    }

    @Test
    void importedClassRemainsAUsableRuntimeNamespace() throws Exception {
        Path child = temp.resolve("class-runtime.ores");
        Path main = temp.resolve("class-runtime-main.ores");

        Files.writeString(child, """
                define class Box as
                  pub static fnc answer(): int { return 42; }
                end
                """);

        Files.writeString(main, """
                import class Box from './class-runtime';

                pub routine main(): void {
                  stdio.stdout.write(Box.answer());
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
    void actorSelectorStaysTypeOnlyUntilActorSpawnNamespaceLands() throws Exception {
        Path child = temp.resolve("actor-type-only.ores");
        Path main = temp.resolve("actor-type-only-main.ores");

        Files.writeString(child, """
                shared actor Worker {
                  pub fnc value(): int { return 2; }
                }
                """);

        Files.writeString(main, """
                import actor Worker from './actor-type-only';

                pub routine main(): void {
                  val runtimeValue = Worker;
                  return;
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(main));
    }

    @Test
    void typesSelectorDoesNotMatchRuntimeClassesOrActors() throws Exception {
        Path child = temp.resolve("runtime-decls.ores");
        Files.writeString(child, """
                define class Box as
                end

                shared actor Worker {
                  pub fnc value(): int { return 2; }
                }
                """);

        for (String name : List.of("Box", "Worker")) {
            Path main = temp.resolve("types-" + name + ".ores");
            Files.writeString(main, """
                    import types %s from './runtime-decls';
                    pub routine main(): void { return; }
                    """.formatted(name));
            assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(main));
        }
    }

}
