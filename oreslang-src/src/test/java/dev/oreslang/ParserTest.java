package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ParserTest {

    @Test
    void sharedIsContextualModifierAndRemainsAvailableAsOrdinaryIdentifier() {
        var tokens = new Lexer("shared actor shared").scan();
        assertEquals(Token.Type.IDENT, tokens.get(0).type());
        assertEquals("shared", tokens.get(0).lexeme());
        assertEquals(Token.Type.ACTOR, tokens.get(1).type());
        assertEquals(Token.Type.IDENT, tokens.get(2).type());

        assertDoesNotThrow(() -> Parser.parse("""
                shared actor Worker {
                  pub receive(int message) => void {
                    val shared = message;
                    stdio.println(shared);
                    return;
                  }
                }

                fnc ordinary() => int {
                  val shared = 41;
                  return shared + 1;
                }
                """));
    }

    @Test
    void supportsMultipleModulesAndComplexNumbers() {
        String source = """
                define module math
                  fnc z() => complex {
                    return 3 + 4i;
                  }
                end

                define module app
                  pub fnc main() => void {
                    const answer = 40 + 2;
                    [const first, let second] = [1, 2];
                    stdio.println("oreslang");
                    return;
                  }
                end
                """;

        Ast.Program program = TypeChecker.check(Parser.parse(source));
        assertEquals(2, program.modules().size());
        assertEquals("math", program.modules().getFirst().name());
    }

    @Test
    void parsesIfDoFiWithCommaAndPipeConditions() {
        String source = """
                define module app
                  fnc choose(bool a, bool b) => int {
                    if a, b | false; do
                      return 1;
                    else
                      return 0;
                    fi
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void methodReceiverIsImplicitOrExplicitSelf() {
        String source = """
                define module model
                  define class x as
                    @Ret<self>
                    find() {
                      return self;
                    }

                    @Ret<self>
                    find_with_arg(self x)(int foo) {
                      return self;
                    }
                  end
                end
                """;
        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertNull(klass.methods().getFirst().explicitReceiverType());
        assertEquals("x", klass.methods().get(1).explicitReceiverType().name());
    }

    @Test
    void lexerRecognizesLambdaAndFatReturnArrows() {
        var tokens = new Lexer("(int x) -> x + 1; fnc f() => int { return 1; }").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ARROW));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.FAT_ARROW));
    }
    @Test
    void sharedActorUsesOneReceiveAndAllowsOwnedStateMutation() {
        String source = """
                shared actor Account {
                  let int balance = 100;

                  private current(): int {
                    return self.balance;
                  }

                  pub receive(amount: int): void {
                    self.balance = self.balance - amount;
                    val observed = self.current();
                    stdio.println(observed);
                    return;
                  }
                }
                """;

        Ast.Program program = TypeChecker.check(Parser.parse(source));
        Ast.ClassDecl actor =
                (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();

        assertEquals(Ast.ActorKind.SHARED, actor.actorKind());
        assertEquals("Account", actor.name());
        assertEquals(List.of("current", "receive"),
                actor.methods().stream().map(Ast.MethodDecl::name).toList());
        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    @Test
    void actorReceiveAndSendFormTheMailboxSurface() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define actor Worker as
                  let int count = 0;

                  constructor(initial: int) {
                    self.count = initial;
                  }

                  private helper(): int {
                    return self.count;
                  }

                  pub receive(value: int): void {
                    self.count = self.count + value;
                    val observed = self.helper();
                    stdio.println(observed);
                    return;
                  }
                end

                fnc exercise() -> void {
                  val worker = spawn Worker(1);
                  worker.send(2);
                  return;
                }
                """));

        Ast.ClassDecl actor =
                (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(List.of("constructor", "helper", "receive"),
                actor.methods().stream().map(Ast.MethodDecl::name).toList());

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor GenericEndpoint as
                  pub receive<T>(value: T): void { return; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor MutableEndpoint as
                  pub receive(value: int mut): void { return; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor UnsafeBoundary as
                  pub receive(state: SharedMutex<int>): void { return; }
                end
                """)));

        IllegalArgumentException directReceive = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub receive(value: int): void { return; }
                        end

                        fnc bad() -> void {
                          val worker = spawn Worker();
                          worker.receive(1);
                          return;
                        }
                        """)));
        assertTrue(directReceive.getMessage().contains("runtime-owned"),
                directReceive.getMessage());
    }

    @Test
    void classExtendingIntrinsicActorCarriesMessageReplyErrorContract() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class Worker extends Actor<int, void, String> as
                  let int count = 0;

                  pub receive(value: int): void {
                    self.count = self.count + value;
                    return;
                  }
                end
                """));

        Ast.ClassDecl actor =
                (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.SHARED, actor.actorKind());
        assertTrue(actor.parents().isEmpty(),
                "compiler-intrinsic Actor base normalizes into actorKind");
        assertEquals(3, actor.actorProtocolTypes().size());
        assertEquals("int", actor.actorProtocolTypes().getFirst().name());

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Bad extends Actor<String, void, String> as
                  pub receive(value: int): void { return; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Bad extends Actor<int, void> as
                  pub receive(value: int): void { return; }
                end
                """));
    }

    @Test
    void actorClassSpawnTypesMailboxSend() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  let int count = 0;

                  constructor(initial: int) {
                    self.count = initial;
                  }

                  pub receive(value: int): void {
                    self.count = self.count + value;
                    return;
                  }
                end

                fnc exercise() -> void {
                  val worker = spawn Worker(1);
                  worker.send(2);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  constructor() {
                    val f = Futures.completed(1);
                    await f;
                  }

                  pub receive(value: int): void { return; }
                end
                """)));
    }

    @Test
    void actorInheritancePreservesIsolationAndReceiveContract() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Parent as
                  let int total = 0;
                  pub receive(value: int): void {
                    self.total = self.total + value;
                    return;
                  }
                end

                define actor Child extends Parent as
                  pub receive(value: int): void {
                    self.total = self.total + value;
                    return;
                  }
                end

                fnc exercise() -> void {
                  val child = spawn Child();
                  child.send(7);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                isoactor Parent {
                  pub receive(value: int): void { return; }
                }

                shared actor Child extends Parent {
                  pub receive(value: int): void { return; }
                }
                """)));
    }

    @Test
    void actorMayImplementReceiveInterfaceButActorRefBehaviorStaysConcrete() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define interface WorkerInbox
                  fnc receive(value: int): void;
                end

                define actor Worker implements WorkerInbox as
                  pub receive(value: int): void { return; }
                end

                fnc exercise() -> void {
                  val worker = spawn Worker();
                  worker.send(41);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define interface WorkerInbox
                  fnc receive(value: int): void;
                end

                define actor Worker implements WorkerInbox as
                  pub receive(value: int): void { return; }
                end

                fnc bad() -> void {
                  val concrete = spawn Worker();
                  val ActorRef<WorkerInbox> narrowed = concrete;
                  narrowed.send(41);
                  return;
                }
                """)));
    }

    @Test
    void actorWithoutReceiveIsRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define actor Worker as
                          private helper(): int { return 1; }
                        end
                        """));
        assertTrue(failure.getMessage().contains("receive"), failure.getMessage());
    }

    @Test
    void actorReceiveIsNotAFirstClassBoundCallback() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub receive(value: int): void { return; }
                        end

                        fnc bad() -> void {
                          val worker = spawn Worker();
                          val callback = worker.receive;
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("runtime-owned"),
                failure.getMessage());
    }

    @Test
    void actorRestrictionsPropagateThroughOrdinaryHelpers() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc unsafe_helper() -> void {
                          val state = SharedMutex.new(1);
                          return;
                        }

                        define actor Worker as
                          pub receive(message: int): void {
                            unsafe_helper();
                            return;
                          }
                        end
                        """)));

        assertTrue(
                failure.getMessage().contains("external shared mutable state")
                        || failure.getMessage().contains("SharedMutex"),
                failure.getMessage());
    }

    @Test
    void actorConstructorNoSuspendRulePropagatesThroughPrivateHelpers() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          constructor() {
                            self.prepare();
                          }

                          private prepare(): void {
                            await 1;
                            return;
                          }

                          pub receive(message: int): void { return; }
                        end
                        """)));

        assertTrue(failure.getMessage().contains("constructor")
                        && (failure.getMessage().contains("await")
                            || failure.getMessage().contains("suspend")),
                failure.getMessage());
    }

    @Test
    void actorBoundaryInputCannotBeUpgradedToMutableHelperAuthority() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> {
                    Ast.Program typed = TypeChecker.check(Parser.parse("""
                            define class Box as
                              let int value = 0;
                            end

                            fnc mutate(Box mut box) -> void {
                              box.value = 1;
                              return;
                            }

                            define actor Worker as
                              pub receive(box: Box): void {
                                mutate(box);
                                return;
                              }
                            end
                            """));
                    OwnershipChecker.check(typed);
                });

        assertTrue(failure.getMessage().contains("actor-boundary input")
                        || failure.getMessage().contains("mutable"),
                failure.getMessage());
    }

    @Test
    void actorConstructorParametersCannotBeMut() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Worker as
                  constructor(box: Box mut) { }
                  pub run(): void { return; }
                end
                """));
    }

    @Test
    void actorImportAliasesAndEntrySyntaxParseWithoutDefaultExports() {
        Ast.Program imports = Parser.parse("""
                import actor Foo from "../../actor-mod";
                import actor (Bar as B) from "../../actor-mod";
                import actor {Baz: Zed} from "../../actor-mod";
                import * as X from "../../actor-mod";
                import entry as Plugin from "../../actor-mod";

                fnc main() -> void { return; }
                """);

        assertEquals(Ast.ImportKind.ACTOR, imports.imports().get(0).kind());
        assertEquals("B", imports.imports().get(1).localName("Bar"));
        assertEquals("Zed", imports.imports().get(2).localName("Baz"));
        assertEquals(Ast.ImportKind.ALL, imports.imports().get(3).kind());
        assertEquals("X", imports.imports().get(3).namespace());
        assertEquals(Ast.ImportKind.ENTRY, imports.imports().get(4).kind());
        assertEquals("Plugin", imports.imports().get(4).localName("$entry$"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  pub receive(m: int): void { return; }
                end

                export entry Worker;
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Worker as
                  pub receive(m: int): void { return; }
                end

                export default Worker;
                """));
    }

    @Test
    void actorClassRejectsStaticEscapeHatchesAndReservedIntrinsicNames() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Bad as
                  private static fnc helper() => int { return 1; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Actor as
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor IsoActor as
                end
                """));
    }

    @Test
    void actorProtocolCannotReceiveOrAcquireWritableSharedMutexState() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Bad as
                  pub run(SharedMutex<int> state): void {
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Bad as
                  private mutate(): void {
                    val state = SharedMutex.new(1);
                    return;
                  }

                  pub run(int message): void { return; }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Bad as
                  private mutate(SharedMutex<int> state): void {
                    val guard = state.try_lock();
                    return;
                  }

                  pub run(int message): void { return; }
                end
                """)));
    }

    @Test
    void actorFncDefaultsSharedAndIsoactorIsPrivate() {
        Ast.Program sharedProgram = Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }
                """);
        Ast.FunctionDecl sharedActor = (Ast.FunctionDecl) sharedProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.SHARED, sharedActor.actorKind());

        Ast.Program explicitSharedProgram = Parser.parse("""
                pub shared actor fnc worker(int value) => int {
                  return value;
                }
                """);
        Ast.FunctionDecl explicitShared = (Ast.FunctionDecl) explicitSharedProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.SHARED, explicitShared.actorKind());

        Ast.Program privateProgram = Parser.parse("""
                pub isoactor routine worker(int value) => int {
                  return value;
                }
                """);
        Ast.FunctionDecl privateActor = (Ast.FunctionDecl) privateProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.PRIVATE, privateActor.actorKind());
        assertEquals(Ast.CallableKind.ROUTINE, privateActor.kind());
    }

    @Test
    void sharedWithoutActorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared fnc nope() => void {
                  return;
                }
                """));
    }

    @Test
    void actorCallablesRequireSpawnAndActorClassesCannotBeConstructedOrdinarily() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }

                pub fnc good() => void {
                  val pending = spawn worker(1);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }

                pub fnc bad_call() => int {
                  return worker(1);
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor Account {
                  let int balance = 100;

                  pub receive(int message): void {
                    return;
                  }
                }

                pub fnc bad() => Account {
                  return new Account();
                }
                """)));
    }

    @Test
    void rejectsDuplicateAndConflictingActorModifiers() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared shared actor Account {
                  let balance = 1;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub private actor fnc worker() => void {
                  return;
                }
                """));
    }

    @Test
    void actorFunctionCannotBeProgramMain() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc main() => void {
                  return;
                }
                """)));
    }


    @Test
    void actorProtocolCannotLeakActorOwnedState() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor Account {
                  let int balance = 100;

                  pub leak(): Account {
                    return self;
                  }
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let Array<int> items = [1, 2, 3];

                      pub snapshot(): Array<int> {
                        return self.items;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void actorMayReadAndMutateOwnedStateButCannotLeakSelfBorrow() {
        assertDoesNotThrow(() -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let int balance = 100;

                      private current(): int {
                        return self.balance;
                      }

                      pub receive(delta: int): void {
                        val current = self.current();
                        self.balance = current + delta;
                        return;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    fnc inspect(&Account account) -> int {
                      return 1;
                    }

                    shared actor Account {
                      let int balance = 100;

                      private inspect_self(): void {
                        val observed = inspect(&self);
                        return;
                      }

                      pub receive(delta: int): void {
                        self.inspect_self();
                        return;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void actorRefSendUsesEffectiveInheritedReceiveContract() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                shared actor Parent {
                  let int total = 0;

                  pub receive(value: int): void {
                    self.total = self.total + value;
                    return;
                  }
                }

                shared actor Child extends Parent {
                  private current(): int {
                    return self.total;
                  }
                }

                fnc exercise() -> void {
                  val child = spawn Child();
                  child.send(7);
                  return;
                }
                """)));

        IllegalArgumentException wrongMessage = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        shared actor Parent {
                          pub receive(value: int): void { return; }
                        }

                        shared actor Child extends Parent {
                        }

                        fnc bad() -> void {
                          val child = spawn Child();
                          child.send("wrong");
                          return;
                        }
                        """)));
        assertTrue(wrongMessage.getMessage().contains("ActorRef.send"),
                wrongMessage.getMessage());
    }

    @Test
    void actorInheritanceMustPreserveIsolationKind() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                shared actor Parent {
                  let int value = 1;
                  pub receive(message: int): void { return; }
                }

                shared actor Child extends Parent {
                  pub receive(message: int): void {
                    self.value = self.value + message;
                    return;
                  }
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                isoactor Parent {
                  let int value = 1;
                  pub receive(message: int): void { return; }
                }

                shared actor Child extends Parent {
                  pub receive(message: int): void { return; }
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor Child extends Object {
                  let int value = 1;
                  pub receive(message: int): void { return; }
                }
                """)));
    }

    @Test
    void classDeclarationsRequireAsDelimiter() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class CounterState as
                  pub let int value = 10;
                end
                """));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class CounterState
                          pub let int value = 10;
                        end
                        """));

        assertTrue(failure.getMessage().contains("expected 'as' after class header"));
    }

}
