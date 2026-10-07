package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import dev.oreslang.compiler.OresCompiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class DynamicSelectOptionLanguageTest {

    @Test
    void blockingAndNonblockingDynamicSelectUseOptionResults() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> first = Channel.new<int>(1);
                  writech first, 7;
                  val SelectSet blocking_cases =
                      SelectSet.new([SelectCase.read(first)]);
                  val Option<SelectResult> blocking =
                      select first from blocking_cases;

                  stdio.stdout.write(blocking.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(blocking.unwrap().value);

                  val Channel<int> second = Channel.new<int>(1);
                  writech second, 9;
                  val SelectSet async_cases =
                      SelectSet.new([SelectCase.read(second)]);
                  val Future<Option<SelectResult>> pending =
                      nb select first from async_cases;
                  val Option<SelectResult> async_result = await pending;

                  stdio.stdout.write(":");
                  stdio.stdout.write(async_result.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(async_result.unwrap().value);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("true:7:true:9", run(program));
    }


    @Test
    void selectPlanReusesFairCursorAcrossLoopIterations() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> first = Channel.new<int>(2);
                  val Channel<int> second = Channel.new<int>(2);
                  writech first, 10;
                  writech first, 11;
                  writech second, 20;
                  writech second, 21;

                  val SelectPlan plan = SelectPlan.new([
                    SelectCase.read(first),
                    SelectCase.read(second)
                  ]);

                  val Option<SelectResult> a = try select from plan;
                  val Option<SelectResult> b = try select from plan;
                  val SelectResult selected_a = a.unwrap();
                  val SelectResult selected_b = b.unwrap();

                  stdio.stdout.write(selected_a.index);
                  stdio.stdout.write(":");
                  stdio.stdout.write(selected_a.value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(selected_b.index);
                  stdio.stdout.write(":");
                  stdio.stdout.write(selected_b.value);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("0:10:1:20", run(program));
    }

    @Test
    void selectPlanAcceptsSelectSetAndHeterogeneousReadinessCases() {
        String program = """
                async fnc ready(): int {
                  return 9;
                }

                fnc make(): SelectPlan {
                  val CancellationToken token = CancellationToken.new();
                  val Future<int> future = ready();
                  val SelectSet set = SelectSet.new([
                    SelectCase.await(future),
                    SelectCase.timeout(1000000),
                    SelectCase.cancelled(token)
                  ]);
                  return SelectPlan.new(set);
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void typedSelectResultSupportsPatternMatchingAcrossReadinessKinds() throws Exception {
        String program = """
                async fnc ready(): int {
                  return 9;
                }

                pub fnc main(): void {
                  val Channel<int> messages = Channel.new<int>(1);
                  writech messages, 7;
                  val Future<int> future = ready();
                  val CancellationToken token = CancellationToken.new();

                  val SelectPlan<int> plan = SelectPlan.new([
                    SelectCase.read(messages),
                    SelectCase.await(future),
                    SelectCase.timeout(1000000000),
                    SelectCase.cancelled(token)
                  ]);

                  val Option<Select<int>> result = try select first from plan;

                  match result over
                    on Some(Read(int value)) -> {
                      stdio.stdout.write("read:");
                      stdio.stdout.write(value);
                    }
                    on Some(Await(int value)) -> {
                      stdio.stdout.write("await:");
                      stdio.stdout.write(value);
                    }
                    on Some(Write) -> {
                      stdio.stdout.write("write");
                    }
                    on Some(Timeout) -> {
                      stdio.stdout.write("timeout");
                    }
                    on Some(Cancelled) -> {
                      stdio.stdout.write("cancelled");
                    }
                    on Some(Default) -> {
                      stdio.stdout.write("default");
                    }
                    on None -> {
                      stdio.stdout.write("none");
                    }
                  end
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("read:7", run(program));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "select-option.ores")
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
