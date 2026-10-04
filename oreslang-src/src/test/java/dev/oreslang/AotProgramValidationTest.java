package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class AotProgramValidationTest {

    @TempDir
    Path temp;

    @Test
    void aotAdmissionInventoriesTheWholePathImportClosure() throws Exception {
        Path dep = temp.resolve("dep.ores");
        Files.writeString(dep, """
                define namespace domain as
                  type UserId = int;
                end
                """);

        Path main = temp.resolve("main.ores");
        Files.writeString(main, """
                import * as dep from "./dep.ores";

                define module app
                  pub fnc main(): void {
                    return;
                  }
                end
                """);

        LinkedProgramRunner.AotValidationResult result =
                LinkedProgramRunner.validateForAot(
                        main,
                        IsolatePolicy.developer());

        assertEquals(2, result.build().units().size());
        assertEquals(2, result.declarationsByUnit().size());

        var depEntry = result.declarationsByUnit().entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("dep.ores"))
                .findFirst()
                .orElseThrow();
        assertTrue(depEntry.getValue().contains(
                "domain",
                OresCompiler.DeclarationKind.NAMESPACE));
        assertTrue(depEntry.getValue().contains(
                "domain.UserId",
                OresCompiler.DeclarationKind.TYPE_ALIAS));

        var mainEntry = result.declarationsByUnit().entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("main.ores"))
                .findFirst()
                .orElseThrow();
        assertTrue(mainEntry.getValue().contains(
                "app",
                OresCompiler.DeclarationKind.MODULE));
        assertTrue(mainEntry.getValue().containsCallable(
                "app.main",
                OresCompiler.DeclarationKind.FUNCTION,
                0));
    }

    @Test
    void aotAdmissionRejectsRuntimeMixedJavaCompilationUntilPrecompiled() throws Exception {
        Path main = temp.resolve("mixed.ores");
        Files.writeString(main, """
                java {
                  final class Helper {
                    static int answer() { return 42; }
                  }
                }

                pub fnc main(): void {
                  return;
                }
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validateForAot(
                        main,
                        IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("precompiled and linked"));
        assertTrue(error.getMessage().contains("JavaCompiler/URLClassLoader"));
    }
    @Test
    void aotAdmissionRecordsStaticallyKnownJavaHostReachability() throws Exception {
        Path main = temp.resolve("java-import.ores");
        Files.writeString(main, """
                import class {ArrayList} from "java:java.util.ArrayList";

                define module app
                  pub fnc main(): void {
                    return;
                  }
                end
                """);

        assertThrows(
                SecurityException.class,
                () -> LinkedProgramRunner.validateForAot(
                        main,
                        IsolatePolicy.developer()));

        IsolatePolicy javaPolicy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP);
        LinkedProgramRunner.AotValidationResult result =
                LinkedProgramRunner.validateForAot(main, javaPolicy);

        assertEquals(
                java.util.Set.of("java.util.ArrayList"),
                result.requiredHostClasses());
    }


}
