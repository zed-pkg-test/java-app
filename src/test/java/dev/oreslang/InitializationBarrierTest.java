package dev.oreslang;

import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class InitializationBarrierTest {
    @Test
    void fileRootBindingsAreForbiddenButNamedModuleConstantsAreLegal() throws Exception {
        IllegalArgumentException root = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module Foo as
                        end

                        pub const foo = "bar";
                        pub routine main(): void { return; }
                        """));
        assertTrue(root.getMessage().contains("file/root bindings are forbidden"));

        String output = run("""
                define module Foo
                  pub const foo = "bar";
                end

                pub routine main(): void {
                  stdio.stdout.write(Foo.foo);
                  return;
                }
                """);
        assertEquals("bar", output);
    }

    @Test
    void initNamedFunctionsAreOrdinaryAndNeverRunOnLoad() throws Exception {
        String output = run("""
                fnc init(): void {
                  stdio.stdout.write("I");
                  return;
                }

                pub routine main(): void {
                  stdio.stdout.write("M");
                  return;
                }
                """);

        assertEquals("M", output);
    }

    @Test
    void initCanBeCalledExplicitlyLikeAnyOtherFunction() throws Exception {
        String output = run("""
                fnc init(): void {
                  stdio.stdout.write("I");
                  return;
                }

                pub routine main(): void {
                  init();
                  stdio.stdout.write("M");
                  return;
                }
                """);

        assertEquals("IM", output);
    }

    @Test
    void linkingRunsNoGuestCodeAndMainNeedsNoInitBarrier() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, """
                fnc init(): void {
                  stdio.stdout.write("I");
                  return;
                }

                pub routine main(): void {
                  stdio.stdout.write("M");
                  return;
                }
                """, "inert-load.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            Value unit = context.parse(source);
            unit.execute(OresEvalRootNode.LINK_ONLY_COMMAND);
            assertEquals("", output.toString(StandardCharsets.UTF_8));

            unit.execute(OresEvalRootNode.MAIN_ONLY_COMMAND);
            assertEquals("M", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void compatibilityInitControlIsANoOp() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, """
                fnc init(): void {
                  stdio.stdout.write("I");
                  return;
                }

                pub routine main(): void {
                  stdio.stdout.write("M");
                  return;
                }
                """, "no-init-hook.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            Value unit = context.parse(source);
            unit.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            unit.execute(OresEvalRootNode.MAIN_ONLY_COMMAND);
            assertEquals("M", output.toString(StandardCharsets.UTF_8));
        }
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "load-semantics.ores")
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
