package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PointerlessOwnershipTest {
    @Test
    void ordinaryParametersBorrowInsteadOfMoving() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub val int value = 7;
                end

                fnc read(Box box) => int {
                  return box.value;
                }

                fnc ok() => void {
                  let Box box = new Box();
                  stdio.println(read(box));
                  stdio.println(box.value);
                  return;
                }
                """)));
    }

    @Test
    void takeParametersMoveOwnership() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc consume(take Box box) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Box box = new Box();
                          consume(box);
                          stdio.println(box.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved"));
    }

    @Test
    void mutableArgumentsAreExclusiveForTheWholeCall() {
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

                        fnc bad() => void {
                          let Box box = new Box();
                          mixed(box, box);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void nestedMutableReborrowsRemainExclusive() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 0;
                        end

                        fnc inner(mut Box left, Box right) => void {
                          left.value = right.value;
                          return;
                        }

                        fnc outer(mut Box box) => void {
                          inner(box, box);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow")
                || error.getMessage().toLowerCase().contains("reborrow"));
    }

    @Test
    void takeSelfTransfersReceiverOwnership() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub val int value = 7;

                  pub identity(take self)() => self {
                    return self;
                  }
                end

                fnc ok() => void {
                  let Box box = new Box();
                  val Box moved = box.identity();
                  stdio.println(moved.value);
                  return;
                }
                """)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;

                          pub identity(take self)() => self {
                            return self;
                          }
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val Box moved = box.identity();
                          stdio.println(box.value);
                          stdio.println(moved.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved"));
    }

    @Test
    void boundMethodsCannotEscapeReceiverLifetime() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;

                          pub read() => int {
                            return self.value;
                          }
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val callback = box.read;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("bound instance method")
                && error.getMessage().contains("cannot be extracted"));
    }

    @Test
    void takeRejectsBorrowValuedExpressions() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc consume(take Box box) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Box box = new Box();
                          consume(borrow(box));
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("requires ownership")
                || error.getMessage().contains("cannot take ownership"));
    }

    @Test
    void consumingReceiverRejectsBorrowedSelf() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;

                          pub identity(take self)() => self {
                            return self;
                          }
                        end

                        fnc bad(Box box) => void {
                          val Box moved = box.identity();
                          stdio.println(moved.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("takes self ownership")
                || error.getMessage().contains("receiver is borrowed"));
    }

    @Test
    void qualifiedModuleTakeCallsMoveTheCallerValue() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        define module sink as
                          pub fnc consume(take Box box) => void {
                            return;
                          }
                        end

                        define module app as
                          pub fnc bad() => void {
                            let Box box = new Box();
                            sink.consume(box);
                            stdio.println(box.value);
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("moved"));
    }

    @Test
    void staticTakeCallsMoveTheCallerValue() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        define class Sink as
                          pub static fnc consume(take Box box) => void {
                            return;
                          }
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          Sink.consume(box);
                          stdio.println(box.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved"));
    }

    @Test
    void ordinaryFirstClassFunctionsBorrowNonCopyArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub val int value = 7;
                end

                fnc read(Box box) => int {
                  return box.value;
                }

                fnc ok() => void {
                  let Box box = new Box();
                  val Fnc<Box, int> callback = read;
                  stdio.println(callback(box));
                  stdio.println(box.value);
                  return;
                }
                """)));
    }

    @Test
    void nonCopyFieldProjectionCannotEscapeBorrowedOwner() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Inner as
                          pub val int value = 7;
                        end

                        define class Outer as
                          pub val Inner inner = new Inner();
                        end

                        fnc leak(Outer outer) => Inner {
                          return outer.inner;
                        }
                        """)));

        assertTrue(error.getMessage().contains("borrowed value")
                || error.getMessage().contains("return provenance"));
    }

    @Test
    void storedFieldProjectionKeepsRootOwnerBorrowed() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Inner as
                          pub val int value = 7;
                        end

                        define class Outer as
                          pub val Inner inner = new Inner();
                        end

                        fnc mutate(mut Outer outer) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Outer outer = new Outer();
                          val inner = outer.inner;
                          mutate(outer);
                          stdio.println(inner.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void readReceiverConflictsWithMutableAliasingArgument() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 0;

                          pub observe(self)(mut Box other) => void {
                            other.value = 1;
                            return;
                          }
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          box.observe(box);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void mutableReceiverConflictsWithReadAliasingArgument() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 0;

                          pub merge(mut self)(Box other) => void {
                            self.value = other.value;
                            return;
                          }
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          box.merge(box);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void nonCopyFieldMayMoveOutOfUnreachableTemporary() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Inner as
                  pub val int value = 7;
                end

                define class Outer as
                  pub val Inner inner = new Inner();
                end

                fnc make() => Outer {
                  return new Outer();
                }

                fnc extract() => Inner {
                  return make().inner;
                }
                """)));
    }

    @Test
    void readInterfaceCannotBeImplementedWithStrongerReceiverOwnership() {
        IllegalArgumentException mutable = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface Reader as
                          fnc read() => int;
                        end

                        define class Bad is Reader as
                          pub read(mut self)() => int {
                            return 1;
                          }
                        end
                        """)));
        assertTrue(mutable.getMessage().contains("read-receiver contract"));

        IllegalArgumentException consuming = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface Reader as
                          fnc read() => int;
                        end

                        define class Bad is Reader as
                          pub read(take self)() => int {
                            return 1;
                          }
                        end
                        """)));
        assertTrue(consuming.getMessage().contains("read-receiver contract"));
    }

    @Test
    void classOverridesMustPreserveReceiverAndParameterOwnership() {
        IllegalArgumentException receiver = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Parent as
                          pub use(mut self)() => void {
                            return;
                          }
                        end

                        define class Child extends Parent as
                          pub use() => void {
                            return;
                          }
                        end
                        """)));
        assertTrue(receiver.getMessage().contains("ownership contract mismatch"));

        IllegalArgumentException parameter = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                        end

                        define class Parent as
                          pub use(take Box value) => void {
                            return;
                          }
                        end

                        define class Child extends Parent as
                          pub use(Box value) => void {
                            return;
                          }
                        end
                        """)));
        assertTrue(parameter.getMessage().contains("ownership contract mismatch"));
    }

    @Test
    void multipleInheritanceRejectsConflictingOwnershipContracts() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class A as
                          pub use(mut self)() => void {
                            return;
                          }
                        end

                        define class B as
                          pub use() => void {
                            return;
                          }
                        end

                        define class C extends A, B as
                        end
                        """)));

        assertTrue(error.getMessage().contains("multiple inheritance ownership conflict"));
    }

    @Test
    void interfaceDispatchReservesReadReceiverAcrossArguments() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface Touch as
                          fnc touch(mut Touch other) => void;
                        end

                        define class Impl is Touch as
                          pub touch(mut Touch other) => void {
                            return;
                          }
                        end

                        fnc bad() => void {
                          let Touch value = new Impl();
                          value.touch(value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void interfaceNonCopyFieldProjectionBorrowsItsRootOwner() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Inner as
                          pub val int value = 7;
                        end

                        define interface HasInner as
                          val Inner inner;
                        end

                        define class Outer is HasInner as
                          pub val Inner inner = new Inner();
                        end

                        fnc leak(HasInner value) => Inner {
                          return value.inner;
                        }
                        """)));

        assertTrue(error.getMessage().contains("borrowed value")
                || error.getMessage().contains("return provenance"));
    }

    @Test
    void persistentModuleStateCannotBeMovedOut() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        define module state as
                          val Box box = new Box();

                          pub fnc take_out() => Box {
                            return box;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("persistent module-owned state"));
    }

    @Test
    void ordinaryCallsMayReadBorrowModuleState() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub val int value = 7;
                end

                define module state as
                  val Box box = new Box();

                  fnc read(Box value) => int {
                    return value.value;
                  }

                  pub fnc current() => int {
                    return read(box);
                  }
                end
                """)));
    }

    @Test
    void mutableCallsMayExclusivelyBorrowLetModuleState() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub let int value = 0;
                end

                define module state as
                  let Box box = new Box();

                  fnc bump(mut Box value) => void {
                    value.value = value.value + 1;
                    return;
                  }

                  pub fnc next() => int {
                    bump(box);
                    return box.value;
                  }
                end
                """)));
    }

    @Test
    void qualifiedCopyModuleFieldsMayBeReadDirectly() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module state as
                  pub val int count = 7;
                end

                define module app as
                  pub fnc read() => int {
                    return state.count;
                  }
                end
                """)));
    }

    @Test
    void qualifiedNonCopyModuleFieldsRequireAnAccessor() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        define module state as
                          pub val Box box = new Box();
                        end

                        define module app as
                          pub fnc bad() => int {
                            return state.box.value;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("cannot directly extract non-Copy module state"));
    }

    @Test
    void anonymousObjectCopyFieldsRemainCopyValues() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc read() => int {
                  val record = obj{count: 7};
                  return record.count;
                }
                """)));
    }

    @Test
    void anonymousObjectNonCopyFieldsBorrowTheRoot() {
        IllegalArgumentException escaped = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => Box {
                          val record = obj{child: new Box()};
                          return record.child;
                        }
                        """)));
        assertTrue(escaped.getMessage().contains("borrowed value")
                || escaped.getMessage().contains("return provenance"));

        IllegalArgumentException moved = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let record = obj{child: new Box()};
                          val child = record.child;
                          val moved = take(record);
                          stdio.println(child.value);
                          return;
                        }
                        """)));
        assertTrue(moved.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void anonymousObjectFieldMayMoveOutOfUnreachableTemporary() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub val int value = 7;
                end

                fnc make() => Box {
                  return obj{child: new Box()}.child;
                }
                """)));
    }

    @Test
    void destructuringBorrowedCollectionsDoesNotManufactureOwnership() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc leak(Array<Box> boxes) => Box {
                          [val first] = boxes;
                          return first;
                        }
                        """)));

        assertTrue(error.getMessage().contains("borrowed value")
                || error.getMessage().contains("return provenance"));
    }

    @Test
    void destructuringBorrowedCopyElementsStillCopies() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc first(Array<int> values) => int {
                  [val first] = values;
                  return first;
                }
                """)));
    }

    @Test
    void destructuringConsumedCollectionTransfersElementOwnership() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub val int value = 7;
                end

                fnc extract() => Box {
                  let boxes = arr[new Box()];
                  [val first] = take(boxes);
                  return first;
                }
                """)));
    }

    @Test
    void destructuringMutableBorrowDoesNotUpgradeElementToMutableOwner() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 0;
                        end

                        fnc bad(mut Array<Box> boxes) => void {
                          [let first] = boxes;
                          first.value = 1;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("immutable binding/read borrow")
                || error.getMessage().contains("read borrow"));
    }

    @Test
    void unlinkedImportedFunctionCallsFailClosed() {
        IllegalArgumentException call = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import fnc {remote} from "./remote.ores";

                        fnc bad() => void {
                          remote();
                          return;
                        }
                        """)));
        assertTrue(call.getMessage().contains("cross-unit linker"));

        IllegalArgumentException extracted = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import fnc {remote} from "./remote.ores";

                        fnc bad() => void {
                          val callback = remote;
                          return;
                        }
                        """)));
        assertTrue(extracted.getMessage().contains("cannot be extracted"));
    }

    @Test
    void unlinkedImportedClassMethodsFailClosedButConstructionRemainsOwned() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                import class {RemoteBox} from "./remote.ores";

                fnc make() => RemoteBox {
                  return new RemoteBox();
                }
                """)));

        IllegalArgumentException method = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import class {RemoteBox} from "./remote.ores";

                        fnc bad(RemoteBox box) => void {
                          box.touch();
                          return;
                        }
                        """)));
        assertTrue(method.getMessage().contains("linked receiver/parameter ownership metadata"));

        IllegalArgumentException staticCall = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import class {RemoteBox} from "./remote.ores";

                        fnc bad() => void {
                          RemoteBox.make();
                          return;
                        }
                        """)));
        assertTrue(staticCall.getMessage().contains("linked ownership metadata"));
    }

    @Test
    void unlinkedImportedNamespaceCallsFailClosed() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import * as remote from "./remote.ores";

                        fnc bad() => void {
                          remote.answer();
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("requires linked ownership metadata"));
    }

    @Test
    void pointerBorrowAndDereferenceSyntaxAreRejected() {
        IllegalArgumentException amp = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(&mut String value) => void {
                          return;
                        }
                        """));
        assertTrue(amp.getMessage().contains("pointer-style '&'"));

        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(int value) => int {
                          return *value;
                        }
                        """));
    }

    @Test
    void borrowedParametersCannotMasqueradeAsOwnedReturns() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc identity(Box box) => Box {
                          return box;
                        }
                        """)));

        assertTrue(error.getMessage().contains("return provenance"));
    }

    @Test
    void ownershipSensitiveFunctionsCannotLoseModesWhenExtracted() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc consume(take Box box) => void {
                          return;
                        }

                        fnc bad() => void {
                          val callback = consume;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot be extracted as a first-class Fnc"));
    }

    @Test
    void interfaceImplementationsMustMatchOwnershipModes() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        define interface Sink as
                          fnc save(take Box box) => void;
                        end

                        define class Bad is Sink as
                          pub save(Box box) => void {
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("parameter ownership mismatch"));
    }

    @Test
    void mutableReceiverCannotMasqueradeAsReadOnlyInterfaceMethod() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface CounterApi as
                          fnc bump() => int;
                        end

                        define class Counter is CounterApi as
                          pub let int count = 0;

                          pub bump(mut self)() => int {
                            self.count = self.count + 1;
                            return self.count;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("read-receiver contract"));
        assertTrue(error.getMessage().contains("mut self"));
    }

    @Test
    void structuralViewsRejectOwnershipSensitiveMethods() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 0;

                          pub bump(mut self)() => int {
                            self.value = self.value + 1;
                            return self.value;
                          }
                        end

                        fnc inspect(@Structural Box box) => int {
                          return box.value;
                        }
                        """)));

        assertTrue(error.getMessage().contains("structural views are read-only"));
    }

    @Test
    void inheritedInterfaceOwnershipConflictsAreRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        define interface Reader as
                          fnc use(Box box) => void;
                        end

                        define interface Consumer as
                          fnc use(take Box box) => void;
                        end

                        define interface Impossible extends Reader, Consumer as
                        end
                        """)));

        assertTrue(error.getMessage().contains("interface ownership conflict"));
    }

    @Test
    void ownershipIntrinsicsCannotBeShadowed() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          val int take = 1;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("reserved ownership intrinsic name 'take'"));
    }

    @Test
    void copyIsProvenNotShallowReferenceAliasing() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok() => void {
                  val int x = 7;
                  val int y = copy(x);
                  stdio.println(x);
                  stdio.println(y);
                  return;
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
                          val copyOfBox = copy(box);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("not implicitly copyable")
                || error.getMessage().contains("proven Copy"));
    }

    @Test
    void shareDoesNotCreateAmbientSharedMutableState() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          let int value = 1;
                          val shared = share(value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("reserved for explicit shared capabilities"));
    }
}
