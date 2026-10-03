package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OptionPatternMatchingTest {
    @Test
    void exhaustiveMatchRefinesPayloadAndExecutes() throws Exception {
        String program = """
                define module app as
                  fnc value_or_zero(Option<int> maybe) => int {
                    match maybe {
                      Some(value) => {
                        return value;
                      }
                      None => {
                        return 0;
                      }
                    }
                  }

                  pub routine main() => void {
                    stdio.println(value_or_zero(Some(42)));
                    stdio.println(value_or_zero(None));
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "option-match.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("42"), text);
        assertTrue(text.contains("0"), text);
    }

    @Test
    void optionMatchMustBeExhaustiveAndUnique() {
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(Option<int> maybe) => int {
                          match maybe {
                            Some(value) => { return value; }
                          }
                        }
                        """));
        assertTrue(missing.getMessage().contains("exhaustive"));

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(Option<int> maybe) => int {
                          match maybe {
                            Some(value) => { return value; }
                            Some(other) => { return other; }
                            None => { return 0; }
                          }
                        }
                        """));
        assertTrue(duplicate.getMessage().contains("duplicate Some"));
    }

    @Test
    void matchRequiresOptionAndUncheckedPayloadAccessIsRejected() {
        IllegalArgumentException wrongType = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(int value) => int {
                          match value {
                            Some(inner) => { return inner; }
                            None => { return 0; }
                          }
                        }
                        """)));
        assertTrue(wrongType.getMessage().contains("requires Option"));

        IllegalArgumentException unchecked = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad(Option<Box> maybe) => int {
                          return maybe.value;
                        }
                        """)));
        assertTrue(unchecked.getMessage().contains("nullable until proven Some"));
    }

    @Test
    void defaultMatchBorrowsButTakeMatchConsumesNonCopyOption() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub val int value = 7;
                end

                fnc ok() => int {
                  let Box box = new Box();
                  let Option<Box> maybe = Some(box);

                  match maybe {
                    Some(value) => { stdio.println(value.value); }
                    None => { }
                  }

                  match take(maybe) {
                    Some(value) => { return value.value; }
                    None => { return 0; }
                  }
                }
                """)));

        IllegalArgumentException moved = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          let Option<Box> maybe = Some(box);
                          match take(maybe) {
                            Some(value) => { stdio.println(value.value); }
                            None => { }
                          }
                          stdio.println(maybe);
                          return;
                        }
                        """)));
        assertTrue(moved.getMessage().contains("moved"));
    }

    @Test
    void optionCopyIsConditionalOnPayloadCopy() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc copied(Option<int> maybe) => int {
                  match copy(maybe) {
                    Some(value) => { return value; }
                    None => { return 0; }
                  }
                }
                """)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          let Option<Box> maybe = Some(box);
                          match copy(maybe) {
                            Some(value) => { stdio.println(value.value); }
                            None => { }
                          }
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("proven Copy"));
    }

    @Test
    void someOwnsNonCopyPayloadAndCannotHideABorrow() {
        IllegalArgumentException moved = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val maybe = Some(box);
                          stdio.println(box.value);
                          return;
                        }
                        """)));
        assertTrue(moved.getMessage().contains("moved"));

        IllegalArgumentException hiddenBorrow = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val maybe = Some(borrow(box));
                          return;
                        }
                        """)));
        assertTrue(hiddenBorrow.getMessage().contains("cannot store a borrow"));
    }

    @Test
    void mutParameterReborrowsCannotAliasWithinNestedCall() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 0;
                        end

                        fnc mixed(mut Box write, Box read) => void {
                          write.value = read.value;
                          return;
                        }

                        fnc nested(mut Box box) => void {
                          mixed(box, box);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void optionAndConstructorsCannotBeShadowed() {
        IllegalArgumentException optionType = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Option as
                        end
                        """)));
        assertTrue(optionType.getMessage().contains("built-in type 'Option'"));

        IllegalArgumentException constructor = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc Some(int value) => int {
                          return value;
                        }
                        """)));
        assertTrue(constructor.getMessage().contains("built-in Option constructor 'Some'"));
    }
}
