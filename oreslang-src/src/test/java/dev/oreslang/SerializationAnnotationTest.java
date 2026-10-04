package dev.oreslang;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class SerializationAnnotationTest {

    @Test
    void expandsFromJsonIntoTypedAccessors() {
        Ast.Program checked = TypeChecker.check(Parser.parse("""
                define class MyBlob as
                  @FromJson("foo")
                  foo: String;
                  @FromJson("bar")
                  bar: bool;
                end
                """));

        Ast.ClassDecl blob = firstClass(checked);
        assertEquals("foo", AnnotationExpander.fromJsonKey(blob.fields().get(0)));
        assertEquals("bar", AnnotationExpander.fromJsonKey(blob.fields().get(1)));

        Ast.MethodDecl getFoo = method(blob, "getFoo", 0);
        Ast.MethodDecl setFoo = method(blob, "setFoo", 1);
        assertEquals(Ast.Visibility.PUBLIC, getFoo.visibility());
        assertEquals("String", getFoo.returnType().name());
        assertEquals("String", setFoo.parameters().getFirst().type().name());
        assertTrue(AnnotationExpander.isGeneratedFromJsonSetter(setFoo));
    }

    @Test
    void generatedAccessorsExecuteWithoutRuntimeReflection() throws Exception {
        String program = """
                define class MyBlob as
                  @FromJson("foo")
                  foo: String;
                  @FromJson("bar")
                  bar: bool;
                end

                pub fnc main() => void {
                  let MyBlob blob = new MyBlob("before", false);
                  blob.setFoo("after");
                  blob.setBar(true);
                  stdio.println(blob.getFoo());
                  stdio.println(blob.getBar());
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "serialization.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("after"));
        assertTrue(text.contains("true"));
    }

    @Test
    void rejectsDuplicateImmutableStaticAndCollidingDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad as
                  @FromJson("same")
                  first: String;
                  @FromJson("same")
                  second: String;
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad as
                  @FromJson("foo")
                  val foo: String;
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad as
                  @FromJson("foo")
                  static foo: String;
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad as
                  @FromJson("foo")
                  foo: String;
                  getFoo() => String { return self.foo; }
                end
                """)));
    }

    @Test
    void generatedSetterRequiresMutableOwner() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class MyBlob as
                  @FromJson("foo")
                  foo: String;
                end

                pub fnc main() => void {
                  val MyBlob blob = new MyBlob("before");
                  blob.setFoo("after");
                  return;
                }
                """)));
    }

    @Test
    void jsonWireKeyParticipatesInIncrementalAbi() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        var first = compiler.compile(Map.of("blob.ores", """
                define class Blob as
                  @FromJson("foo")
                  foo: String;
                end
                """));

        var second = compiler.compile(Map.of("blob.ores", """
                define class Blob as
                  @FromJson("renamed")
                  foo: String;
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
