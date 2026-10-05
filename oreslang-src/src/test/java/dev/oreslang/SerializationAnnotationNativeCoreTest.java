package dev.oreslang;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class SerializationAnnotationNativeCoreTest {

    @Test
    void expandsTypedClassFieldIntoOrdinaryAccessors() {
        Ast.Program checked = TypeChecker.check(Parser.parse("""
                define class Blob as
                  @FromJson("foo")
                  let String foo;
                end
                """));

        Ast.ClassDecl blob = firstClass(checked);
        Ast.FieldDecl field = blob.fields().getFirst();
        assertEquals("foo", AnnotationExpander.fromJsonKey(field));

        Ast.MethodDecl getter = method(blob, "getFoo", 0);
        Ast.MethodDecl setter = method(blob, "setFoo", 1);
        assertEquals(Ast.Visibility.PUBLIC, getter.visibility());
        assertEquals("String", getter.returnType().name());
        assertEquals("String", setter.parameters().getFirst().type().name());
        assertTrue(AnnotationExpander.isGeneratedFromJsonSetter(setter));
    }

    @Test
    void generatedSetterRequiresMutableOwner() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Blob as
                  @FromJson("foo")
                  let String foo;
                end

                pub fnc main() => void {
                  val Blob blob = new Blob("before");
                  blob.setFoo("after");
                  return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Blob as
                  @FromJson("foo")
                  let String foo;
                end

                pub fnc main() => void {
                  let Blob blob = new Blob("before");
                  blob.setFoo("after");
                  return;
                }
                """)));
    }

    @Test
    void rejectsActorStateAndNonClassUses() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor Bad {
                  @FromJson("secret")
                  let String secret;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                @FromJson("not-a-field")
                let String value = "x";
                """)));
    }

    @Test
    void rejectsDuplicateWireKeysAndImmutableFields() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad as
                  @FromJson("same")
                  let String first;
                  @FromJson("same")
                  let String second;
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad as
                  @FromJson("foo")
                  val String foo;
                end
                """)));
    }

    @Test
    void wireKeyParticipatesInIncrementalAbi() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        var first = compiler.compile(Map.of("blob.ores", """
                define class Blob as
                  @FromJson("foo")
                  let String foo;
                end
                """));

        var second = compiler.compile(Map.of("blob.ores", """
                define class Blob as
                  @FromJson("renamed")
                  let String foo;
                end
                """));

        assertNotEquals(
                first.units().get("blob.ores").abiDigest(),
                second.units().get("blob.ores").abiDigest());
    }

    private static Ast.ClassDecl firstClass(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) return klass;
            }
        }
        throw new AssertionError("expected class");
    }

    private static Ast.MethodDecl method(Ast.ClassDecl klass, String name, int arity) {
        return klass.methods().stream()
                .filter(method -> method.name().equals(name) && method.arity() == arity)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing method " + name + "/" + arity));
    }
}
