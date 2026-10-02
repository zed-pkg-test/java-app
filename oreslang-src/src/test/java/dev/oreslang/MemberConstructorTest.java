package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class MemberConstructorTest {
    @Test
    void classMembersAreNameFirstAndCarryStaticMetadata() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class MyBlob as
                  pub foo: String = "seed";
                  pub static bar: bool = false;

                  constructor(String value) {
                    self.foo = value;
                  }
                end
                """));

        Ast.ClassDecl klass = program.modules().getFirst().declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(2, klass.fields().size());
        assertEquals("foo", klass.fields().get(0).name());
        assertFalse(klass.fields().get(0).isStatic());
        assertEquals(Ast.BindingKind.LET, klass.fields().get(0).bindingKind());
        assertEquals("bar", klass.fields().get(1).name());
        assertTrue(klass.fields().get(1).isStatic());
        assertTrue(klass.methods().stream().anyMatch(method -> method.name().equals("constructor")));
    }

    @Test
    void preservesAnnotationsOnNameFirstStaticAndInstanceMembers() {
        Ast.Program program = Parser.parse("""
                define class MyBlob as
                  @FromJson("foo")
                  foo: String

                  @Wire("bar")
                  static bar: bool = false
                end
                """);

        Ast.ClassDecl klass = program.modules().getFirst().declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals("FromJson", klass.fields().get(0).annotations().getFirst().name());
        assertFalse(klass.fields().get(0).isStatic());
        assertEquals("Wire", klass.fields().get(1).annotations().getFirst().name());
        assertTrue(klass.fields().get(1).isStatic());
    }

    @Test
    void moduleMembersPreserveAnnotationsToo() {
        Ast.Program program = Parser.parse("""
                define module config as
                  @Config("endpoint")
                  endpoint: String = "local"

                  @Config("ready")
                  static ready: bool = true
                end
                """);

        Ast.ModuleDecl module = program.modules().stream()
                .filter(m -> m.name().equals("config"))
                .findFirst()
                .orElseThrow();
        var fields = module.declarations().stream()
                .filter(Ast.FieldDecl.class::isInstance)
                .map(Ast.FieldDecl.class::cast)
                .toList();

        assertEquals(2, fields.size());
        assertEquals("Config", fields.get(0).annotations().getFirst().name());
        assertFalse(fields.get(0).isStatic());
        assertEquals("Config", fields.get(1).annotations().getFirst().name());
        assertTrue(fields.get(1).isStatic());
    }

    @Test
    void constructorsInitializeInstancesAndStaticFieldsLiveOnClassNamespace() throws Exception {
        String output = run("""
                define class MyBlob as
                  pub foo: String;
                  pub static bar: bool = false;

                  constructor(String value) {
                    self.foo = value;
                  }
                end

                pub routine main() => void {
                  val MyBlob blob = new MyBlob("json");
                  MyBlob.bar = true;
                  stdio.stdout.write(blob.foo);
                  stdio.stdout.write(MyBlob.bar);
                  return;
                }
                """);

        assertEquals("jsontrue", output);
    }

    @Test
    void constructorsMayOverloadByArity() throws Exception {
        String output = run("""
                define class Box as
                  pub value: String;

                  constructor() {
                    self.value = "default";
                  }

                  constructor(String value) {
                    self.value = value;
                  }
                end

                pub routine main() => void {
                  val Box a = new Box();
                  val Box b = new Box("custom");
                  stdio.stdout.write(a.value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(b.value);
                  return;
                }
                """);

        assertEquals("default:custom", output);
    }

    @Test
    void moduleMembersUseTheSameNameFirstSurfaceAndCanInitializeInInit() throws Exception {
        String output = run("""
                define module state as
                  pub value: String;
                  pub static ready: bool;

                  init routine() => void {
                    value = "ready";
                    ready = true;
                    return;
                  }
                end

                pub routine main() => void {
                  stdio.stdout.write(state.value);
                  stdio.stdout.write(state.ready);
                  return;
                }
                """);

        assertEquals("readytrue", output);
    }

    @Test
    void moduleInitCanInitializeAnUninitializedClassStatic() throws Exception {
        String output = run("""
                define class Flags as
                  pub static ready: bool;
                end

                define module state as
                  init routine() => void {
                    Flags.ready = true;
                    return;
                  }

                  pub fnc read() => bool {
                    return Flags.ready;
                  }
                end

                pub routine main() => void {
                  stdio.stdout.write(state.read());
                  return;
                }
                """);

        assertEquals("true", output);
    }

    @Test
    void explicitConstructorMustInitializeEveryInstanceFieldAtRuntime() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run("""
                define class Broken as
                  value: String;

                  constructor() {
                    return;
                  }
                end

                pub routine main() => void {
                  val Broken broken = new Broken();
                  return;
                }
                """));

        assertTrue(causeChainContains(failure, "did not initialize field 'value'"), String.valueOf(failure));
    }

    @Test
    void oldTypeFirstFieldSyntaxRemainsAcceptedDuringMigration() throws Exception {
        String output = run("""
                define class Legacy as
                  pub val int value = 7;
                end

                pub routine main() => void {
                  val Legacy legacy = new Legacy();
                  stdio.stdout.write(legacy.value);
                  return;
                }
                """);

        assertEquals("7", output);
    }

    @Test
    void constructorMayInitializeValExactlyOnce() throws Exception {
        String output = run("""
                define class ImmutableName as
                  pub val name: String;

                  constructor(String name) {
                    self.name = name;
                  }
                end

                pub routine main() => void {
                  val ImmutableName value = new ImmutableName("ores");
                  stdio.stdout.write(value.name);
                  return;
                }
                """);

        assertEquals("ores", output);
    }

    @Test
    void constructorCannotInitializeTheSameValTwice() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run("""
                define class Broken as
                  val name: String;

                  constructor(String name) {
                    self.name = name;
                    self.name = "again";
                  }
                end

                pub routine main() => void {
                  val Broken value = new Broken("first");
                  return;
                }
                """));

        assertTrue(causeChainContains(failure, "val fields may be initialized only once"), String.valueOf(failure));
    }

    @Test
    void initializedValCannotBeOverwrittenByConstructor() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class AlreadyInitialized as
                          val name: String = "seed";

                          constructor(String name) {
                            self.name = name;
                          }
                        end
                        """)));

        assertTrue(failure.getMessage().contains("cannot reassign val field"), failure.getMessage());
    }

    @Test
    void constFieldsRequireDeclarationInitializers() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Constants as
                          const token: String;
                        end
                        """)));

        assertTrue(failure.getMessage().contains("requires a declaration initializer"), failure.getMessage());
    }

    @Test
    void staticFieldsCannotDependOnClassTypeParameters() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box<T> as
                          static value: T;
                        end
                        """)));

        assertTrue(failure.getMessage().contains("static storage is shared across all instances"), failure.getMessage());
    }

    @Test
    void inheritanceCannotBypassAnExplicitParentConstructor() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Base as
                          value: String;

                          constructor(String value) {
                            self.value = value;
                          }
                        end

                        define class Child extends Base as
                        end
                        """)));

        assertTrue(failure.getMessage().contains("until super(...) is supported"), failure.getMessage());
    }

    private static boolean causeChainContains(Throwable failure, String text) {
        Throwable current = failure;
        while (current != null) {
            if (String.valueOf(current.getMessage()).contains(text)) return true;
            current = current.getCause();
        }
        return false;
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "member-constructors.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
