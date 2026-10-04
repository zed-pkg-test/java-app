package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AotJitCompilationContractTest {

    private static final String STATIC_PROGRAM = """
            define namespace domain as
              type UserId = int;

              define interface Named
                String name;
              end

              define class Box as
                val int value;

                read(): int {
                  return self.value;
                }

                read(int delta): int {
                  return self.value + delta;
                }
              end
            end

            define module app
              pub fnc main(): void {
                return;
              }
            end
            """;

    @Test
    void jitAndAotConsumeTheSameStaticDeclarationManifest() {
        var jit = OresCompiler.validateForCompilation(
                STATIC_PROGRAM,
                IsolatePolicy.developer(),
                OresCompiler.CompilationMode.JIT);
        var aot = OresCompiler.validateForAot(
                STATIC_PROGRAM,
                IsolatePolicy.developer());

        assertEquals(jit.declarations(), aot.declarations());
        assertEquals(OresCompiler.CompilationMode.JIT, jit.mode());
        assertEquals(OresCompiler.CompilationMode.AOT, aot.mode());

        var declarations = aot.declarations();
        assertTrue(declarations.contains(
                "domain",
                OresCompiler.DeclarationKind.NAMESPACE));
        assertTrue(declarations.contains(
                "domain.UserId",
                OresCompiler.DeclarationKind.TYPE_ALIAS));
        assertTrue(declarations.contains(
                "domain.Named",
                OresCompiler.DeclarationKind.INTERFACE));
        assertTrue(declarations.contains(
                "domain.Named.name",
                OresCompiler.DeclarationKind.INTERFACE_FIELD));
        assertTrue(declarations.contains(
                "domain.Box",
                OresCompiler.DeclarationKind.CLASS));
        assertTrue(declarations.contains(
                "domain.Box.value",
                OresCompiler.DeclarationKind.CLASS_FIELD));
        assertTrue(declarations.contains(
                "domain.Box.read",
                OresCompiler.DeclarationKind.CLASS_METHOD));
        assertTrue(declarations.containsCallable(
                "domain.Box.read",
                OresCompiler.DeclarationKind.CLASS_METHOD,
                0));
        assertTrue(declarations.containsCallable(
                "domain.Box.read",
                OresCompiler.DeclarationKind.CLASS_METHOD,
                1));
        assertTrue(declarations.contains(
                "app",
                OresCompiler.DeclarationKind.MODULE));
        assertTrue(declarations.contains(
                "app.main",
                OresCompiler.DeclarationKind.FUNCTION));
        assertTrue(declarations.containsCallable(
                "app.main",
                OresCompiler.DeclarationKind.FUNCTION,
                0));
    }

    @Test
    void nativeHostAotDoesNotPretendGuestSourceWasAotCompiled() {
        ExecutionProfile ios =
                ExecutionProfile.mobileAot(ExecutionProfile.Platform.IOS);

        assertTrue(ios.hostAheadOfTime());
        assertFalse(ios.guestJitAllowed());
        assertEquals(
                ExecutionProfile.GuestRuntimeMode.INTERPRETED,
                ios.guestRuntimeMode());
        assertTrue(
                ios.supportsSourceHotReload(),
                "source hot reload here is interpreted guest execution, not true guest AOT");
    }
}
