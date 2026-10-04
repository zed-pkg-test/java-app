package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class RwLockLanguageTest {

    @Test
    void ordinaryCodeMayReplaceExternalStateAndReadCopyValues() throws Exception {
        String program = """
                pub fnc main() => void {
                  val state = RwLock.new(1);

                  val writer = state.write_lock();
                  writer.replace(2);
                  writer.release();

                  val reader = state.read_lock();
                  val snapshot = reader.value();
                  reader.release();

                  stdio.println(snapshot);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "rw-lock.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("2"));
    }

    @Test
    void sharedActorMayReceiveRwLockAndReadButCannotWrite() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc read_external(RwLock<int> state) => int {
                  val reader = state.read_lock();
                  val snapshot = reader.value();
                  reader.release();
                  return snapshot;
                }
                """)));

        IllegalArgumentException write = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad(RwLock<int> state) => void {
                          val writer = state.write_lock();
                          writer.release();
                          return;
                        }
                        """)));
        assertTrue(write.getMessage().contains("write guard"));
    }

    @Test
    void privateAndUntrustedActorsCannotReceiveRwLockCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                isoactor fnc bad(RwLock<int> state) => void {
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                untrusted actor fnc bad(RwLock<int> state) => void {
                  return;
                }
                """)));
    }

    @Test
    void actorsCannotCreateExternalRwLockState() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad() => void {
                          val state = RwLock.new(1);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("cannot create external RwLock"));
    }

    @Test
    void compositeReadViewIsLexicalAndCannotBeBound() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(RwLock<List<int>> state) => void {
                          val reader = state.read_lock();
                          val leaked = reader.value();
                          reader.release();
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("lexical read-only view"));
    }

    @Test
    void noLockGuardMayCrossAwait() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        async fnc bad(RwLock<int> state, Future<int> work) => int {
                          val reader = state.read_lock();
                          val value = await work;
                          reader.release();
                          return value;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("cannot await while holding a lock guard"));
    }

    @Test
    void rwGuardsAreCompilerManagedAndCannotBeDeclaredByHand() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(RwReadGuard<int> guard) => void {
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(RwWriteGuard<int> guard) => void {
                  return;
                }
                """)));
    }
}
