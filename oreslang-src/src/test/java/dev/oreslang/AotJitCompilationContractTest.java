package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AotJitCompilationContractTest {

    private static final String STATIC_PROGRAM = """
            namespace demo;

            define module model
              pub interface Named {
                name: String
              }

              define class User implements Named as
                pub val String name;

                pub label() => String {
                  return self.name;
                }
              end

              pub fnc make() => void {
                return;
              }
            end
            """;

    @Test
    void jitAndAotUseTheSameStaticDeclarationManifest() {
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
        assertTrue(declarations.contains("demo", OresCompiler.DeclarationKind.NAMESPACE));
        assertTrue(declarations.contains("demo.model", OresCompiler.DeclarationKind.MODULE));
        assertTrue(declarations.contains("demo.model.Named", OresCompiler.DeclarationKind.INTERFACE));
        assertTrue(declarations.contains(
                "demo.model.Named.name",
                OresCompiler.DeclarationKind.INTERFACE_FIELD));
        assertTrue(declarations.contains("demo.model.User", OresCompiler.DeclarationKind.CLASS));
        assertTrue(declarations.contains(
                "demo.model.User.name",
                OresCompiler.DeclarationKind.CLASS_FIELD));
        assertTrue(declarations.contains(
                "demo.model.User.label",
                OresCompiler.DeclarationKind.CLASS_METHOD));
        assertTrue(declarations.contains(
                "demo.model.make",
                OresCompiler.DeclarationKind.FUNCTION));
    }

    @Test
    void declarationsCannotBeCreatedInsideRuntimeControlFlow() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc bad() => void {
                  define class RuntimeType as
                  end
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc bad() => void {
                  define module RuntimeModule
                  end
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc bad() => void {
                  define interface RuntimeContract {
                    fnc ping() => void;
                  }
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc bad() => void {
                  namespace runtime;
                  return;
                }
                """));
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
        assertTrue(ios.supportsSourceHotReload(),
                "source hot reload here is interpreted guest execution, not true guest AOT");
    }
}
