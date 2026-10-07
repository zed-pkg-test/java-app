package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ExplicitStructDefiniteInitializationTest {
    @Test
    void oneIfPathCannotEstablishInitialization() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(bool choose): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  if choose; then
                    point.ready = true;
                  fi
                  return point.ready;
                }
                """)));
    }

    @Test
    void allIfPathsCanEstablishInitialization() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(bool choose): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  if choose; then
                    point.ready = true;
                  else
                    point.ready = false;
                  fi
                  return point.ready;
                }
                """)));
    }

    @Test
    void loopBodyCannotEstablishInitializationAfterLoop() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  for const item of arr[1] do
                    point.ready = true;
                  done
                  return point.ready;
                }
                """)));
    }

    @Test
    void ternaryRequiresInitializationOnBothAlternatives() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(bool choose): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  const ignored = choose ? (point.ready = true) : false;
                  return point.ready;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(bool choose): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  const ignored = choose ? (point.ready = true) : (point.ready = false);
                  return point.ready;
                }
                """)));
    }
    @Test
    void lambdaBodyCannotEstablishInitializationUntilItRuns() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  const initialize = || -> {
                    point.ready = true;
                    return;
                  };
                  return point.ready;
                }
                """)));
    }

}
