package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TopLevelDeclarationTest {

    @Test
    void topLevelClassesAndModulesRemainValid() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Box as
                end

                define module app as
                  pub fnc ping() => int {
                    return 1;
                  }
                end
                """));
    }

    @Test
    void rejectsNestedClassOrModuleDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module outer as
                  define class Nested as
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Outer as
                  define class Nested as
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc run() => void {
                  define module Nested as
                  end
                  return;
                }
                """));
    }
}
