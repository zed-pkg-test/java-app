package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class ChannelStreamingLanguageTest {
    @Test
    void canonicalStreamingExampleRunsEndToEnd() throws Exception {
        assertEquals("123456", run(Files.readString(Path.of("examples/channel-streaming.ores"))));
    }

    @Test
    void rendezvousWritesRemainPendingUntilEachRead() throws Exception {
        assertEquals("123", run("""
                pub async routine main(): void {
                  val Channel<int> output = Channel.new<int>(0);
                  for const value of [1, 2, 3] {
                    val Future<void> pending = nb writech output, value;
                    stdio.stdout.write(readch output);
                    await pending;
                  }
                  return;
                }
                """));
    }

    @Test
    void ordinaryForLoopStreamsOrderedWrites() throws Exception {
        assertEquals("123", run("""
                pub routine main(): void {
                  val Channel<int> output = Channel.new<int>(3);
                  for const value of [1, 2, 3] do
                    writech output, value;
                  done
                  for let i = 0; i < 3; i++ do
                    stdio.stdout.write(readch output);
                  done
                  return;
                }
                """));
    }

    @Test
    void synchronousIteratorStreamsWritesThroughBracedSelect() throws Exception {
        assertEquals("123", run("""
                generator fnc values(): int {
                  yield 1;
                  yield 2;
                  yield 3;
                  return;
                }
                pub routine main(): void {
                  val Channel<int> output = Channel.new<int>(3);
                  for const value of values() {
                    select {
                      case writech output, value: {
                      }
                    }
                  }
                  for let i = 0; i < 3; i++ {
                    select {
                      case readch output: val value {
                        stdio.stdout.write(value);
                      }
                    }
                  }
                  return;
                }
                """));
    }

    @Test
    void asyncIteratorStreamsBlockingAndAwaitedNonblockingWrites() throws Exception {
        for (String write : new String[]{"writech output, value;", "await nb writech output, value;"}) {
            assertEquals("123", run("""
                    async generator fnc values(): int {
                      yield 1;
                      yield 2;
                      yield 3;
                      return;
                    }
                    pub async routine main(): void {
                      val Channel<int> output = Channel.new<int>(3);
                      val AsyncIterator<int> source = values();
                      for await const value of source do
                    """ + "    " + write + "\n" + """
                      done
                      for let i = 0; i < 3; i++ do
                        stdio.stdout.write(readch output);
                      done
                      return;
                    }
                    """));
        }
    }

    @Test
    void channelIsDirectlyAsyncIterableAndDrainsBeforeCompletion() throws Exception {
        assertEquals("123:true", run("""
                pub async routine main(): void {
                  val Channel<int> input = Channel.new<int>(3);
                  writech input, 1;
                  writech input, 2;
                  writech input, 3;
                  input.close();

                  for await const value of input {
                    stdio.stdout.write(value);
                  }
                  stdio.stdout.write(":");
                  stdio.stdout.write(input.is_closed());
                  return;
                }
                """));
    }

    @Test
    void breakingDirectChannelIterationDoesNotCloseOrDrainTheChannel() throws Exception {
        assertEquals("1:2:3", run("""
                pub async routine main(): void {
                  val Channel<int> input = Channel.new<int>(2);
                  writech input, 1;
                  writech input, 2;

                  for await const value of input {
                    stdio.stdout.write(value);
                    break;
                  }

                  stdio.stdout.write(":");
                  stdio.stdout.write(readch input);
                  writech input, 3;
                  stdio.stdout.write(":");
                  stdio.stdout.write(readch input);
                  input.close();
                  return;
                }
                """));
    }

    @Test
    void channelAsyncIterIsAFirstClassNonOwningAsyncIterator() throws Exception {
        assertEquals("12:3", run("""
                pub async routine main(): void {
                  val Channel<int> input = Channel.new<int>(3);
                  writech input, 1;
                  writech input, 2;

                  val AsyncIterator<int> iterator = input.async_iter();
                  val IteratorResult<int> first = await iterator.next();
                  val IteratorResult<int> second = await iterator.next();
                  stdio.stdout.write(first.value.unwrap());
                  stdio.stdout.write(second.value.unwrap());
                  iterator.close();

                  writech input, 3;
                  stdio.stdout.write(":");
                  stdio.stdout.write(readch input);
                  input.close();
                  return;
                }
                """));
    }

    @Test
    void asyncGeneratorYieldStarDelegatesAChannel() throws Exception {
        assertEquals("123", run("""
                async fnc gen* relay(Channel<int> input): AsyncIterator<int> {
                  yield* input;
                  return;
                }

                pub async routine main(): void {
                  val Channel<int> input = Channel.new<int>(3);
                  writech input, 1;
                  writech input, 2;
                  writech input, 3;
                  input.close();

                  for await const value of relay(input) {
                    stdio.stdout.write(value);
                  }
                  return;
                }
                """));
    }

    @Test
    void channelHandlesAreAsyncTaskSafeButNotGeneralCapabilityEscapeHatches() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                async fnc ok(Channel<int> input): void {
                  return;
                }
                """));

        IllegalArgumentException unsafePayload = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        async fnc bad(Channel<Future<int>> input): void {
                          return;
                        }
                        """));
        assertTrue(unsafePayload.getMessage().contains("task-safe"), unsafePayload::getMessage);

        IllegalArgumentException actorBoundary = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        actor fnc bad(Channel<int> input): void {
                          return;
                        }
                        """));
        assertTrue(actorBoundary.getMessage().contains("Channel"), actorBoundary::getMessage);
        assertTrue(actorBoundary.getMessage().contains("actor boundary"), actorBoundary::getMessage);
    }

    @Test
    void synchronousIterationRejectsChannels() {
        IllegalArgumentException loop = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Channel<int> input): void {
                          for const value of input {
                            stdio.stdout.write(value);
                          }
                          return;
                        }
                        """)));
        assertTrue(loop.getMessage().contains("for await"), loop::getMessage);

        IllegalArgumentException delegated = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc gen* bad(Channel<int> input): Iterator<int> {
                          yield* input;
                          return;
                        }
                        """)));
        assertTrue(delegated.getMessage().contains("for await"), delegated::getMessage);
    }

    @Test
    void awaitedStreamingWritesRespectBackpressureAndCloseIteratorOnBreak() throws Exception {
        assertEquals("1:closed|done", run("""
                async generator fnc values(): int {
                  try {
                    yield 1;
                    yield 2;
                  } catch (err) {
                  } finally {
                    stdio.stdout.write(":closed");
                  }
                  return;
                }
                pub async routine main(): void {
                  val Channel<int> output = Channel.new<int>(0);
                  for await const value of values() {
                    val Future<int> reader = nb readch output;
                    await nb writech output, value;
                    stdio.stdout.write(await reader);
                    break;
                  }
                  stdio.stdout.write("|done");
                  return;
                }
                """));
    }

    @Test
    void sustainedStreamsPreserveEveryValueAcrossBufferedAndRendezvousBackpressure() throws Exception {
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 128; i++) expected.append(i).append(',');
        for (int capacity : new int[]{0, 1}) {
            String writes = capacity == 0 ? """
                    val Future<void> first = nb writech output, value;
                    stdio.stdout.write(readch output);
                    stdio.stdout.write(",");
                    await first;
                    val Future<void> second = nb writech output, value + 1;
                    stdio.stdout.write(readch output);
                    stdio.stdout.write(",");
                    await second;
                    """ : """
                    writech output, value;
                    val Future<void> pending = nb writech output, value + 1;
                    stdio.stdout.write(readch output);
                    stdio.stdout.write(",");
                    await pending;
                    stdio.stdout.write(readch output);
                    stdio.stdout.write(",");
                    """;
            assertEquals(expected.toString(), run("""
                    async generator fnc values(): int {
                      for let i = 0; i < 64; i++ {
                        yield i * 2;
                      }
                      return;
                    }
                    pub async routine main(): void {
                    """ + "  val Channel<int> output = Channel.new<int>(" + capacity + ");\n"
                    + "  for await const value of values() {\n" + writes + """
                      }
                      return;
                    }
                    """));
        }
    }

    @Test
    void nonblockingBracedSelectRelaysFromActorContinuation() throws Exception {
        assertEquals("70", run("""
                actor fnc relay(): void {
                  val Channel<int> inbox = Channel.new<int>(1);
                  val Channel<int> replies = Channel.new<int>(1);
                  try writech inbox, 7;
                  nb select {
                    case readch inbox: val value {
                      nb writech replies, value * 10;
                      stdio.stdout.write(readch replies);
                    }
                  }
                  return;
                }
                pub routine main(): void {
                  relay();
                  return;
                }
                """));
    }

    @Test
    void nestedBracedSelectAndDefaultExecuteOnlySelectedBodies() throws Exception {
        assertEquals("70:busy", run("""
                pub routine main(): void {
                  val Channel<int> inbox = Channel.new<int>(1);
                  val Channel<int> replies = Channel.new<int>(1);
                  writech inbox, 7;
                  select {
                    case readch inbox: val value {
                      select {
                        case writech replies, value * 10: {
                          stdio.stdout.write(readch replies);
                        }
                      }
                    }
                  }
                  try select {
                    case readch inbox: {
                      stdio.stdout.write("unexpected");
                    }
                    default: {
                      stdio.stdout.write(":busy");
                    }
                  }
                  return;
                }
                """));
    }

    @Test
    void streamingRejectsWrongElementTypeAndSyncConsumptionOfAsyncIterator() {
        for (String loop : new String[]{
                "for const value of values() { writech output, value; }",
                "for await const value of values() { writech output, \"wrong\"; }"}) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                    async generator fnc values(): int { yield 1; return; }
                    async fnc bad(Channel<int> output): void {
                    """ + loop + "\nreturn;\n}")));
        }
    }

    @Test
    void everyStaticSelectModeRequiresBracesOnReadWriteAndDefaultArms() {
        for (String mode : new String[]{"", "nb ", "try "}) {
            for (String arm : new String[]{
                    "case readch input: val value stdio.println(value);",
                    "case writech input, 1: return;",
                    "default: return;"}) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> Parser.parse("fnc bad(Channel<int> input): void { "
                                + mode + "select { " + arm + " } return; }"));
                assertTrue(error.getMessage().contains("braced body"), error::getMessage);
            }
        }
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "channel-streaming.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
