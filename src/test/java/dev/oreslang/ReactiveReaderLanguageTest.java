package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ReactiveReaderLanguageTest {
    @Test
    void sourceStreamReaderHandoffKeepsCursorAndTypeChecks() throws Exception {
        assertEquals("12true", run("""
                pub routine main() => void {
                  val values = Stream.from_values([1, 2]);
                  val first = values.get_reader();
                  stdio.stdout.write((await first.next()).value);
                  first.release_lock();
                  val second = values.get_reader();
                  stdio.stdout.write((await second.next()).value);
                  stdio.stdout.write((await second.next()).is_complete());
                  second.release_lock();
                  return;
                }
                """));
    }

    @Test
    void observableSubscriptionsAllowIndependentReaders() throws Exception {
        assertEquals("11", run("""
                pub routine main() => void {
                  val values = Observable.from_values([1, 2]);
                  val first = values.subscribe().get_reader();
                  val second = values.subscribe().get_reader();
                  stdio.stdout.write((await first.next()).value);
                  stdio.stdout.write((await second.next()).value);
                  first.release_lock();
                  second.release_lock();
                  return;
                }
                """));
    }

    @Test
    void observableDoesNotHaveGlobalReaderLock() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main() => void {
                  val values = Observable.from_values([1]);
                  val invalid = values.get_reader();
                  return;
                }
                """)));
    }

    @Test
    void readerCannotCrossActorCallableBoundary() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(Reader<int> lease): int { return 1; }
                """)));
    }

    @Test
    void readerTypeResolvesAsAnExplicitOpaqueCapability() {
        TypeChecker.check(Parser.parse("""
                pub routine main() => void {
                  val values = Observable.from_values([1]);
                  val Reader<int> lease = values.subscribe().get_reader();
                  lease.release_lock();
                  return;
                }
                """));
    }


    @Test
    void readerTypesAreNonSendableAcrossActorBoundaries() {
        for (String resultType : new String[]{"Reader<int>", "Option<Reader<int>>"}) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                    actor fnc invalid(): TYPE {
                      return 1;
                    }
                    """.replace("TYPE", resultType))));
        }
    }

    @Test
    void readerTypeHasExactlyOneNonVoidPayload() {
        for (String type : new String[]{"Reader", "Reader<int, int>", "Reader<void>"}) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                    pub routine main() => void {
                      val TYPE r = 1;
                      return;
                    }
                    """.replace("TYPE", type))));
        }
    }

    private static String run(String source) throws Exception {
        var program = Parser.parse(source);
        TypeChecker.check(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "rx-reader-lease.ores")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
