package dev.oreslang;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SerializationAnnotationTest {
    @Test
    void parsesFromJsonFieldAnnotationsAndColonFieldShorthand() {
        Ast.Program program = Parser.parse("""
                define class MyBlob as
                  @FromJson("foo")
                  foo: String
                  @FromJson('bar')
                  bar: bool
                end
                """);

        Ast.ClassDecl blob = firstClass(program);
        assertEquals(2, blob.fields().size());
        assertEquals(Ast.BindingKind.LET, blob.fields().get(0).bindingKind());
        assertEquals(Ast.BindingKind.LET, blob.fields().get(1).bindingKind());
        assertEquals("foo", AnnotationExpander.fromJsonKey(blob.fields().get(0)));
        assertEquals("bar", AnnotationExpander.fromJsonKey(blob.fields().get(1)));
    }

    @Test
    void expandsFromJsonIntoPublicTypedGettersAndSetters() {
        Ast.Program checked = TypeChecker.check(Parser.parse("""
                define class MyBlob as
                  @FromJson("foo")
                  foo: String;
                  @FromJson("bar")
                  bar: bool;
                end
                """));

        Ast.ClassDecl blob = firstClass(checked);
        Ast.MethodDecl getFoo = method(blob, "getFoo", 0);
        Ast.MethodDecl setFoo = method(blob, "setFoo", 1);
        Ast.MethodDecl getBar = method(blob, "getBar", 0);
        Ast.MethodDecl setBar = method(blob, "setBar", 1);

        assertEquals(Ast.Visibility.PUBLIC, getFoo.visibility());
        assertEquals("String", getFoo.returnType().name());
        assertEquals("String", setFoo.parameters().getFirst().type().name());
        assertEquals("bool", getBar.returnType().name());
        assertEquals("bool", setBar.parameters().getFirst().type().name());
        assertTrue(AnnotationExpander.isGeneratedFromJsonSetter(setFoo));
        assertTrue(AnnotationExpander.isGeneratedFromJsonSetter(setBar));

        List<AnnotationExpander.FromJsonBinding> bindings = AnnotationExpander.fromJsonBindings(blob);
        assertEquals(2, bindings.size());
        assertEquals("foo", bindings.get(0).jsonKey());
        assertEquals("setFoo", bindings.get(0).setterName());
        assertEquals("bar", bindings.get(1).jsonKey());
        assertEquals("setBar", bindings.get(1).setterName());
    }

    @Test
    void generatedAccessorsExecuteWithoutRuntimeReflection() throws Exception {
        String program = """
                define class MyBlob as
                  @FromJson("foo")
                  foo: String
                  @FromJson("bar")
                  bar: bool
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
    void rejectsMalformedDuplicateImmutableAndCollidingFromJsonDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Bad as
                  @FromJson(foo)
                  foo: String;
                end
                """)));

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
                  val String foo;
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
    void macroExpansionPreservesActorIsolationAndRejectsUnsafeWireState() {
        Ast.ClassDecl actor = new Ast.ClassDecl(
                "Worker",
                false,
                Ast.ActorKind.PRIVATE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        Ast.Program expanded = AnnotationExpander.expand(
                new Ast.Program(List.of(new Ast.ModuleDecl("root", List.of(actor)))));
        assertEquals(Ast.ActorKind.PRIVATE, firstClass(expanded).actorKind(),
                "macro expansion must never erase actor isolation kind");

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Inferred as
                  @FromJson("foo")
                  let foo = "value";
                end
                """)), "@FromJson must require an explicit field type");

        Ast.Annotation fromJson = new Ast.Annotation(
                "FromJson",
                List.of(Ast.TypeRef.stringLiteral("payload")));
        Ast.FieldDecl mailboxField = new Ast.FieldDecl(
                "payload",
                Ast.Visibility.PRIVATE,
                Ast.BindingKind.LET,
                Ast.TypeRef.simple("String"),
                List.of(fromJson),
                null);
        Ast.ClassDecl sharedActor = new Ast.ClassDecl(
                "Mailbox",
                false,
                Ast.ActorKind.SHARED,
                List.of(),
                List.of(),
                List.of(),
                List.of(mailboxField),
                List.of());
        assertThrows(IllegalArgumentException.class, () -> AnnotationExpander.expand(
                new Ast.Program(List.of(new Ast.ModuleDecl("root", List.of(sharedActor))))),
                "@FromJson must not generate mutation entry points for actor mailbox state");
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
