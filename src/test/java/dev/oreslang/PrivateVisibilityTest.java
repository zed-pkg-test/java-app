package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class PrivateVisibilityTest {

    @TempDir
    Path temp;

    @Test
    void declaringClassMayAccessPrivateMembersAcrossInstancesAndClosures() throws Exception {
        String output = run("""
                define class Vault as
                  private let int secret = Vault.initial_secret();

                  private static fnc initial_secret(): int {
                    return 1;
                  }

                  private reveal(): int {
                    return self.secret;
                  }

                  private static fnc hidden(): int {
                    return 7;
                  }

                  pub bump_and_read(Vault mut other): int {
                    other.secret = other.secret + 1;
                    return other.reveal();
                  }

                  pub destructured(Vault other): int {
                    val { secret } = other;
                    return secret;
                  }

                  pub static fnc expose_hidden(): int {
                    return Vault.hidden();
                  }

                  pub static fnc hidden_callback(): (() => int) {
                    return || -> {
                      return Vault.hidden();
                    };
                  }
                end

                pub routine main(): void {
                  val left = new Vault();
                  val right = new Vault();
                  val third = new Vault();
                  val Fnc<int> callback = Vault.hidden_callback();

                  stdio.stdout.write(left.bump_and_read(right));
                  stdio.stdout.write(":");
                  stdio.stdout.write(left.destructured(third));
                  stdio.stdout.write(":");
                  stdio.stdout.write(Vault.expose_hidden());
                  stdio.stdout.write(":");
                  stdio.stdout.write(callback());
                  return;
                }
                """);

        assertEquals("2:1:7:7", output);
    }

    @Test
    void externalAndSubclassPrivateAccessIsRejectedStatically() {
        IllegalArgumentException privateField = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Vault as
                          private val int secret = 1;
                        end

                        fnc bad(Vault vault): int {
                          return vault.secret;
                        }
                        """)));
        assertTrue(privateField.getMessage().contains("private field"));

        IllegalArgumentException privateMethod = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Vault as
                          private reveal(): int {
                            return 1;
                          }
                        end

                        fnc bad(Vault vault): int {
                          return vault.reveal();
                        }
                        """)));
        assertTrue(privateMethod.getMessage().contains("private method"));

        IllegalArgumentException privateStatic = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Vault as
                          private static fnc hidden(): int {
                            return 1;
                          }
                        end

                        fnc bad(): int {
                          return Vault.hidden();
                        }
                        """)));
        assertTrue(privateStatic.getMessage().contains("private static function"));

        IllegalArgumentException privateStaticValue = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Vault as
                          private static fnc hidden(): int {
                            return 1;
                          }
                        end

                        fnc bad(): void {
                          val Fnc<int> callback = Vault.hidden;
                          return;
                        }
                        """)));
        assertTrue(privateStaticValue.getMessage().contains("private static function"));

        IllegalArgumentException subclass = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Vault as
                          private reveal(): int {
                            return 1;
                          }
                        end

                        define class Child extends Vault as
                          pub expose(): int {
                            return self.reveal();
                          }
                        end
                        """)));
        assertTrue(subclass.getMessage().contains("private method"));
    }

    @Test
    void nlexLambdaDoesNotRetainDeclaringClassPrivateAuthority() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Vault as
                  private static fnc hidden(): int {
                    return 1;
                  }

                  pub static fnc lexical(): (() => int) {
                    return || -> {
                      return Vault.hidden();
                    };
                  }
                end
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Vault as
                          private static fnc hidden(): int {
                            return 1;
                          }

                          pub static fnc isolated(): (() => int) {
                            return nlex || -> {
                              return Vault.hidden();
                            };
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("private static function"));
    }

    @Test
    void wildcardLinkedCodeCannotBypassPrivateRuntimeVisibility() throws Exception {
        Path child = temp.resolve("vault.ores");
        Files.writeString(child, """
                define class Vault as
                  private val int secret = 11;

                  private reveal(): int {
                    return self.secret;
                  }

                  private static fnc hidden(): int {
                    return 13;
                  }

                  private [Symbol.iterator](): Array<int> {
                    return arr[1, 2, 3];
                  }
                end

                pub fnc make_vault(): Vault {
                  return new Vault();
                }
                """);

        Path staticMain = temp.resolve("static-main.ores");
        Files.writeString(staticMain, """
                import * as external from "./vault.ores";

                pub routine main(): void {
                  stdio.stdout.write(external.Vault.hidden());
                  return;
                }
                """);

        PolyglotException staticFailure = assertThrows(
                PolyglotException.class,
                () -> runLinked(staticMain));
        assertTrue(staticFailure.getMessage().contains("private static function"));

        Path methodMain = temp.resolve("method-main.ores");
        Files.writeString(methodMain, """
                import * as external from "./vault.ores";

                pub routine main(): void {
                  val vault = external.make_vault();
                  stdio.stdout.write(vault.reveal());
                  return;
                }
                """);

        PolyglotException methodFailure = assertThrows(
                PolyglotException.class,
                () -> runLinked(methodMain));
        assertTrue(methodFailure.getMessage().contains("private method"));

        Path destructureMain = temp.resolve("destructure-main.ores");
        Files.writeString(destructureMain, """
                import * as external from "./vault.ores";

                pub routine main(): void {
                  val vault = external.make_vault();
                  val { secret } = vault;
                  stdio.stdout.write(secret);
                  return;
                }
                """);

        PolyglotException destructureFailure = assertThrows(
                PolyglotException.class,
                () -> runLinked(destructureMain));
        assertTrue(destructureFailure.getMessage().contains("private field"));

        Path iteratorMain = temp.resolve("iterator-main.ores");
        Files.writeString(iteratorMain, """
                import * as external from "./vault.ores";

                pub routine main(): void {
                  val vault = external.make_vault();
                  for value of vault do
                    stdio.stdout.write(value);
                  done
                  return;
                }
                """);

        IllegalArgumentException iteratorFailure = assertThrows(
                IllegalArgumentException.class,
                () -> runLinked(iteratorMain));
        assertTrue(
                iteratorFailure.getMessage().contains("private")
                        || iteratorFailure.getMessage().contains("for-of requires"));
        assertTrue(iteratorFailure.getMessage().contains("Symbol.iterator"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "private-visibility.ores")
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

    private static String runLinked(Path entry) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                output,
                new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }
}
