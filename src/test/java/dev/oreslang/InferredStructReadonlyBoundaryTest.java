package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class InferredStructReadonlyBoundaryTest {
    @Test void mutableDestructureAliasIsRejectedAtBinding() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val [item] = [struct infer{foo: "a"}];
                  return;
                }
                """)));
    }

    @Test void mutableForOfAliasIsRejectedAtBinding() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  for val item of arr[struct infer{foo: "a"}] do
                    stdio.println(item.foo);
                  done
                  return;
                }
                """)));
    }

    @Test void mutableGenericParameterCannotReceiveReadonlyInferredRecord() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc touch<T>(T mut value): void {
                  return;
                }
                fnc bad(): void {
                  touch(struct infer{foo: "a"});
                  return;
                }
                """)));
    }
    @Test void structuralCastCannotUpgradeReadonlyRecord() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const source = struct infer{foo: "a"};
                  let mut alias = source as {foo: string};
                  alias.foo = "b";
                  return;
                }
                """)));
    }

    @Test void returnCannotEraseReadonlyCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc leak(): {foo: string} {
                  return struct infer{foo: "a"};
                }
                """)));
    }

    @Test void fixedStructuralParameterCannotRoundTripReadonlyAsMutableShape() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc identity({foo: string} value): {foo: string} {
                  return value;
                }
                fnc bad(): void {
                  const result = identity(struct infer{foo: "a"});
                  return;
                }
                """)));
    }

    @Test void collectionInsertionCannotEraseNestedReadonlyCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val items = arr[struct{foo: string}{foo: "mutable"}];
                  items.add(struct infer{foo: "readonly"});
                  return;
                }
                """)));
    }

    @Test void genericIdentityPreservesReadonlyCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc identity<T>(T value): T {
                  return value;
                }
                fnc bad(): void {
                  let mut upgraded = identity(struct infer{foo: "a"});
                  upgraded.foo = "b";
                  return;
                }
                """)));
    }

    @Test void structuralBorrowViewMayReadReadonlyInferredRecord() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc read(structural {foo: string} value): string {
                  return value.foo;
                }
                fnc ok(): string {
                  return read(struct infer{foo: "a"});
                }
                """)));
    }

}
