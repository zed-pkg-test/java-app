package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class OwnershipIntrinsicsHardeningTest {

    @Test
    void structCopyIsDerivedAndPreservesOriginalOwner() throws Exception {
        assertEquals("52", run("""
                struct Point {
                  x: int;
                  y: int;
                }

                fnc consume(Point value) => int {
                  return value.x + value.y;
                }

                pub routine main() => void {
                  let Point p = Point { x = 2, y = 3 };
                  stdio.stdout.write(consume(copy(p)));
                  stdio.stdout.write(p.x);
                  return;
                }
                """));
    }

    @Test
    void genericStructCopyIsDerivedAfterTypeSubstitution() throws Exception {
        assertEquals("77", run("""
                struct Box<T> {
                  value: T;
                }

                pub routine main() => void {
                  let Box<int> a = Box<int> { value = 7 };
                  let Box<int> b = copy(a);
                  stdio.stdout.write(a.value);
                  stdio.stdout.write(b.value);
                  return;
                }
                """));
    }

    @Test
    void classRequiresExplicitHumanCopyContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 1;
                        end

                        fnc bad() => void {
                          let Buffer b = new Buffer();
                          let Buffer c = copy(b);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("copy() => Self"), error.getMessage());
    }

    @Test
    void mutableReceiverCopyMethodIsNotACopyContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 1;

                          pub copy(self &mut self)() => Buffer {
                            return new Buffer(self.value);
                          }
                        end

                        fnc bad() => void {
                          let Buffer b = new Buffer();
                          let Buffer c = copy(b);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("immutable receiver"), error.getMessage());
    }

    @Test
    void honestClassCopyCreatesIndependentOwner() throws Exception {
        assertEquals("79", run("""
                define class Buffer as
                  pub let int value = 0;

                  pub copy() => Buffer {
                    return new Buffer(self.value);
                  }
                end

                pub routine main() => void {
                  let Buffer original = new Buffer(7);
                  let Buffer duplicate = copy(original);
                  duplicate.value = 9;
                  stdio.stdout.write(original.value);
                  stdio.stdout.write(duplicate.value);
                  return;
                }
                """));
    }

    @Test
    void classCopyCannotReturnSelf() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                define class Buffer as
                  pub let int value = 0;

                  pub copy() => Buffer {
                    return self;
                  }
                end

                pub routine main() => void {
                  let Buffer original = new Buffer(7);
                  let Buffer duplicate = copy(original);
                  stdio.stdout.write(duplicate.value);
                  return;
                }
                """));

        assertTrue(rootMessage(error).contains("Copy contract")
                || rootMessage(error).contains("retaining mutable storage"));
    }

    @Test
    void classCopyCannotHideNestedMutableAlias() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                define class Bucket as
                  pub let Array<int> values = arr[1];

                  pub copy() => Bucket {
                    return new Bucket(self.values);
                  }
                end

                pub routine main() => void {
                  let Bucket original = new Bucket(arr[1, 2]);
                  let Bucket duplicate = copy(original);
                  stdio.stdout.write(duplicate.values[0]);
                  return;
                }
                """));

        assertTrue(rootMessage(error).contains("retaining mutable storage"));
    }

    @Test
    void classCopyCanExplicitlyCopyNestedMutableStorage() throws Exception {
        assertEquals("11", run("""
                define class Bucket as
                  pub let Array<int> values = arr[1];

                  pub copy() => Bucket {
                    return new Bucket(copy(self.values));
                  }
                end

                pub routine main() => void {
                  let Bucket original = new Bucket(arr[1, 2]);
                  let Bucket duplicate = copy(original);
                  stdio.stdout.write(original.values[0]);
                  stdio.stdout.write(duplicate.values[0]);
                  return;
                }
                """));
    }

    @Test
    void immutableCopyReceiverCannotMutateNestedObjectThroughMethod() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Child as
                          pub let int value = 0;

                          pub bump(self &mut self)() => void {
                            self.value = self.value + 1;
                            return;
                          }

                          pub copy() => Child {
                            return new Child(self.value);
                          }
                        end

                        define class Parent as
                          pub let Child child = new Child();

                          pub copy() => Parent {
                            self.child.bump();
                            return new Parent(copy(self.child));
                          }
                        end

                        fnc bad() => void {
                          let Parent p = new Parent();
                          let Parent q = copy(p);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("immutable self")
                || error.getMessage().contains("immutable root"), error.getMessage());
    }

    @Test
    void copyFromTemporaryBorrowProducesNewOwner() throws Exception {
        assertEquals("77", run("""
                define class Buffer as
                  pub let int value = 0;

                  pub copy() => Buffer {
                    return new Buffer(self.value);
                  }
                end

                pub routine main() => void {
                  let Buffer original = new Buffer(7);
                  let Buffer duplicate = copy(borrow(original));
                  stdio.stdout.write(original.value);
                  stdio.stdout.write(duplicate.value);
                  return;
                }
                """));
    }

    @Test
    void persistentBorrowIntrinsicBlocksMutationUntilScopeEnds() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        fnc bad() => void {
                          let Buffer original = new Buffer(7);
                          val &Buffer view = borrow(original);
                          original.value = 9;
                          stdio.println(view.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("borrow"), error.getMessage());
    }

    @Test
    void takeExplicitlyMovesAndInvalidatesOldOwner() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 1;
                        end

                        fnc consume(Buffer value) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Buffer b = new Buffer();
                          consume(take(b));
                          stdio.println(b.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved value 'b'"), error.getMessage());
    }

    @Test
    void takeRejectsBorrowedValues() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 1;
                        end

                        fnc bad(&Buffer b) => void {
                          let Buffer owned = take(b);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("take requires an owned value"), error.getMessage());
    }

    @Test
    void shareIsDetachedReadonlySnapshotNotABorrowOfOriginal() throws Exception {
        assertEquals("79", run("""
                define class Buffer as
                  pub let int value = 0;

                  pub copy() => Buffer {
                    return new Buffer(self.value);
                  }
                end

                fnc inspect(&Buffer value) => int {
                  return value.value;
                }

                pub routine main() => void {
                  let Buffer original = new Buffer(7);
                  val &Buffer shared = share(original);
                  original.value = 9;
                  stdio.stdout.write(inspect(shared));
                  stdio.stdout.write(original.value);
                  return;
                }
                """));
    }

    @Test
    void ordinaryBorrowCannotEscapeLocalViaIntrinsic() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        fnc bad() => &Buffer {
                          let Buffer local = new Buffer(7);
                          return borrow(local);
                        }
                        """)));

        assertTrue(error.getMessage().contains("outlive its owner"), error.getMessage());
    }

    @Test
    void borrowReturningFunctionCannotLaunderLocalBorrow() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        fnc identity(&Buffer value) => &Buffer {
                          return value;
                        }

                        fnc bad() => &Buffer {
                          let Buffer local = new Buffer(7);
                          return identity(borrow(local));
                        }
                        """)));

        assertTrue(error.getMessage().contains("outlive its owner"), error.getMessage());
    }

    @Test
    void nestedBorrowReturningCallsPreserveLocalProvenance() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        fnc identity(&Buffer value) => &Buffer {
                          return value;
                        }

                        fnc again(&Buffer value) => &Buffer {
                          return value;
                        }

                        fnc bad() => &Buffer {
                          let Buffer local = new Buffer(7);
                          return again(identity(borrow(local)));
                        }
                        """)));

        assertTrue(error.getMessage().contains("outlive its owner"), error.getMessage());
    }

    @Test
    void borrowReturningCallCanBeConsumedTemporarilyWithoutExtendingBorrow() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Buffer as
                  pub let int value = 0;
                end

                fnc identity(&Buffer value) => &Buffer {
                  return value;
                }

                fnc inspect(&Buffer value) => int {
                  return value.value;
                }

                fnc ok() => void {
                  let Buffer local = new Buffer(7);
                  stdio.println(inspect(identity(borrow(local))));
                  local.value = 9;
                  return;
                }
                """)));
    }

    @Test
    void borrowReturningMethodViewKeepsReceiverBorrowedWhenPersisted() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;

                          pub view() => &Buffer {
                            return &self;
                          }
                        end

                        fnc bad() => void {
                          let Buffer local = new Buffer(7);
                          val &Buffer view = local.view();
                          local.value = 9;
                          stdio.println(view.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("borrow"), error.getMessage());
    }

    @Test
    void borrowReturningMethodCannotLaunderTemporaryReceiver() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;

                          pub view() => &Buffer {
                            return &self;
                          }
                        end

                        fnc bad() => &Buffer {
                          return new Buffer(7).view();
                        }
                        """)));

        assertTrue(error.getMessage().contains("temporary")
                || error.getMessage().contains("unrooted receiver"), error.getMessage());
    }

    @Test
    void ordinaryBorrowCannotBeHiddenInsideOwningContainer() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        fnc bad() => void {
                          let Buffer local = new Buffer(7);
                          val values = arr[borrow(local)];
                          stdio.println(values);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot store an ordinary borrow"), error.getMessage());
    }

    @Test
    void sharedSnapshotCanBeStoredAndCapturedBecauseItIsDetached() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Buffer as
                  pub let int value = 0;

                  pub copy() => Buffer {
                    return new Buffer(self.value);
                  }
                end

                fnc ok() => (() -> int) {
                  let Buffer local = new Buffer(7);
                  val &Buffer detached = share(local);
                  val values = arr[detached];
                  return || -> {
                    return detached.value;
                  };
                }
                """)));
    }

    @Test
    void immutableBorrowAliasesKeepOwnerBorrowedUntilAllAliasesLeaveScope() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        fnc bad() => void {
                          let Buffer local = new Buffer(7);
                          val &Buffer first = borrow(local);
                          val &Buffer second = first;
                          local.value = 9;
                          stdio.println(second.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("borrow"), error.getMessage());
    }

    @Test
    void immutableBorrowAliasCountsReleaseAtLexicalScopeEnd() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Buffer as
                  pub let int value = 0;
                end

                fnc ok(bool condition) => void {
                  let Buffer local = new Buffer(7);
                  if condition; do
                    val &Buffer first = borrow(local);
                    val &Buffer second = first;
                    stdio.println(second.value);
                  fi
                  local.value = 9;
                  return;
                }
                """)));
    }

    @Test
    void shareSnapshotMayEscapeSourceLocalLifetime() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Buffer as
                  pub let int value = 0;

                  pub copy() => Buffer {
                    return new Buffer(self.value);
                  }
                end

                fnc snapshot() => &Buffer {
                  let Buffer local = new Buffer(7);
                  return share(local);
                }
                """)));
    }

    @Test
    void shareSnapshotCannotBeMutated() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;

                          pub copy() => Buffer {
                            return new Buffer(self.value);
                          }
                        end

                        fnc bad() => void {
                          let Buffer original = new Buffer(7);
                          val &Buffer shared = share(original);
                          shared.value = 9;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("immutable borrow")
                || error.getMessage().contains("immutable root")
                || error.getMessage().contains("immutable parameter/binding"), error.getMessage());
    }

    @Test
    void traitProvidedCopyDoesNotSatisfyClassIdentityCopyContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define trait Copier as
                          pub copy() => Widget {
                            return new Widget(1);
                          }
                        end

                        define class Widget with Copier as
                          pub val int value = 1;
                        end

                        fnc bad() => void {
                          let Widget original = new Widget(7);
                          let Widget duplicate = copy(original);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("declared directly on the class"), error.getMessage());
    }

    @Test
    void moduleOwnedMoveOnlyValuesCannotAcquirePersistentAliasesByInitializer() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        define module store as
                          let Buffer original = new Buffer(7);
                          let Buffer alias = original;
                        end
                        """)));

        assertTrue(error.getMessage().contains("cannot move module-owned value"), error.getMessage());
        assertTrue(error.getMessage().contains("copy(...) or share(...)"), error.getMessage());
    }

    @Test
    void classFieldInitializerCannotPersistOrdinaryModuleBorrow() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module store as
                          define class Buffer as
                            pub let int value = 0;
                          end

                          let Buffer source = new Buffer(7);

                          define class Holder as
                            pub val &Buffer view = borrow(source);
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("cannot store an ordinary borrow"), error.getMessage());
    }

    @Test
    void assignmentCannotHideOrdinaryBorrowInsideContainer() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;

                          pub copy() => Buffer {
                            return new Buffer(self.value);
                          }
                        end

                        fnc bad() => void {
                          let Buffer first = new Buffer(1);
                          let Buffer second = new Buffer(2);
                          let values = arr[share(first)];
                          values[0] = borrow(second);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot store an ordinary borrow"), error.getMessage());
    }

    @Test
    void assignmentMayStoreDetachedShareInsideContainer() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Buffer as
                  pub let int value = 0;

                  pub copy() => Buffer {
                    return new Buffer(self.value);
                  }
                end

                fnc ok() => void {
                  let Buffer first = new Buffer(1);
                  let Buffer second = new Buffer(2);
                  let values = arr[share(first)];
                  values[0] = share(second);
                  return;
                }
                """)));
    }

    @Test
    void takeOfCallReturningOwnedValueChecksSideEffectsOnce() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Buffer as
                  pub let int value = 1;
                end

                fnc identity(Buffer value) => Buffer {
                  return value;
                }

                fnc ok() => int {
                  let Buffer original = new Buffer(7);
                  let Buffer moved = take(identity(original));
                  return moved.value;
                }
                """)));
    }

    @Test
    void contextualIntrinsicNamesDoNotHijackUserFunctions() throws Exception {
        assertEquals("42", run("""
                fnc copy(int value) => int {
                  return value + 1;
                }

                pub routine main() => void {
                  stdio.stdout.write(copy(41));
                  return;
                }
                """));
    }

    @Test
    void localBindingCanShadowContextualIntrinsicName() throws Exception {
        assertEquals("42", run("""
                pub routine main() => void {
                  val copy = (int value) -> {
                    return value + 1;
                  };
                  stdio.stdout.write(copy(41));
                  return;
                }
                """));
    }

    @Test
    void inheritedClassCopyDoesNotSatisfySubclassIdentityContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Base as
                          pub copy() => Base {
                            return new Base();
                          }
                        end

                        define class Child extends Base as
                        end

                        fnc bad() => void {
                          let Child child = new Child();
                          let Child duplicate = copy(child);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("declared directly on the class"), error.getMessage());
    }

    @Test
    void structCopyRecursesThroughCopyableClassFields() throws Exception {
        assertEquals("79", run("""
                define class Buffer as
                  pub let int value = 0;

                  pub copy() => Buffer {
                    return new Buffer(self.value);
                  }
                end

                struct Holder {
                  buffer: Buffer;
                }

                pub routine main() => void {
                  let Holder original = Holder { buffer = new Buffer(7) };
                  let Holder duplicate = copy(original);
                  duplicate.buffer.value = 9;
                  stdio.stdout.write(original.buffer.value);
                  stdio.stdout.write(duplicate.buffer.value);
                  return;
                }
                """));
    }

    @Test
    void structCopyRejectsNestedClassWithoutCopyContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        struct Holder {
                          buffer: Buffer;
                        }

                        fnc bad() => void {
                          let Holder original = Holder { buffer = new Buffer(7) };
                          let Holder duplicate = copy(original);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("class 'Buffer' is not copyable"), error.getMessage());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "ownership-hardening.ores")
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

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return String.valueOf(current.getMessage());
    }
}
