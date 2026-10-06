package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class PointerlessFncOwnershipTest {

    @Test
    void firstClassFncCallsTemporarilyReadBorrowNonCopyArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub let int value = 7;
                end

                fnc ok(): int {
                  let Box box = new Box();
                  val Fnc<Box, int> read = |value| -> {
                    return value.value;
                  };
                  val int before = read(box);
                  box.value = 9;
                  val int after = read(box);
                  return before + after;
                }
                """)));
    }

    @Test
    void directCallsTemporarilyBorrowNonCopyArguments() throws Exception {
        String output = run("""
                define class Box as
                  pub let int value = 7;
                end

                fnc read(Box box): int {
                  return box.value;
                }

                pub routine main(): void {
                  let Box box = new Box();
                  stdio.stdout.write(read(box));
                  box.value = 9;
                  stdio.stdout.write(read(box));
                  return;
                }
                """);

        assertEquals("79", output);
    }

    @Test
    void typeMutParameterMutatesSameRetainedReference() throws Exception {
        String output = run("""
                define class Box as
                  pub let int value = 7;
                end

                fnc update(Box mut box): void {
                  box.value = 11;
                  return;
                }

                pub routine main(): void {
                  let Box box = new Box();
                  update(box);
                  stdio.stdout.write(box.value);
                  return;
                }
                """);

        assertEquals("11", output);
    }

    @Test
    void rtTakeStillExplicitlyTransfersIntoOrdinaryParameter() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc consume(Box box): void { return; }

                        fnc bad(): void {
                          let Box box = new Box();
                          consume(rt take box);
                          stdio.println(box.value);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("moved value"), error.getMessage());
    }

    @Test
    void overlappingMutArgumentsFailClosed() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc pair(Box mut left, Box mut right): void {
                          left.value = 1;
                          right.value = 2;
                          return;
                        }

                        fnc bad(): void {
                          let Box box = new Box();
                          pair(box, box);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error.getMessage());
    }
    @Test
    void storedRtBorrowBlocksMutationUntilItsScopeEnds() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val view = rt borrow box;
                          box.value = 9;
                          stdio.println(view.value);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error.getMessage());
    }

    @Test
    void rtTakeTransfersOwnershipAndInvalidatesTheSourceBinding() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val Box moved = rt take box;
                          stdio.println(box.value);
                          stdio.println(moved.value);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("moved"), error.getMessage());
    }

    @Test
    void rtCopyAndRtShareAreSafeForCopyValues() throws Exception {
        String output = run("""
                pub routine main(): void {
                  val int source = 7;
                  val int copied = rt copy source;
                  val int shared = rt share source;
                  stdio.stdout.write(copied + shared);
                  return;
                }
                """);
        assertEquals("14", output);
    }

    @Test
    void moveOnlyCopyAndShareFailClosedUntilTheirFullContractsConverge() {
        IllegalArgumentException copied = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val other = rt copy box;
                          stdio.println(other.value);
                          return;
                        }
                        """)));
        assertTrue(copied.getMessage().contains("rt copy"), copied.getMessage());

        IllegalArgumentException shared = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val alias = rt share box;
                          stdio.println(alias.value);
                          return;
                        }
                        """)));
        assertTrue(shared.getMessage().contains("rt share"), shared.getMessage());
    }

    private String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(true)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder("ores", code, "pointerless-fnc-ownership.ores").build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
