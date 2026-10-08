package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.ActorContractValidator;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorHiddenEntryContractTest {
    private static void rejects(String source, String diagnostic) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(diagnostic), failure.getMessage());
    }

    @Test
    void privateRunIsAWellFormedRequestHandlerDeclaration() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  fnc run(int job): int {
                    return job + 1;
                  }
                end
                """)));
    }

    @Test
    void onlyActorRunIsPrivateAndHasOneTypedRequestAndReply() {
        rejects("""
                define actor Worker as
                  pub fnc run(int job): int { return job; }
                end
                """, "run must be private");
        rejects("""
                define actor Worker as
                  pub run(int job): int { return job; }
                end
                """, "run must be private");
        rejects("""
                define actor Worker as
                  static fnc run(int job): int { return job; }
                end
                """, "run must be private");
        rejects("""
                define actor Worker as
                  fnc run(): int { return 1; }
                end
                """, "take exactly one message");
        rejects("""
                define actor Worker as
                  fnc run(int a, int b): int { return a; }
                end
                """, "take exactly one message");
        rejects("""
                define actor Worker as
                  fnc run(int job): void { return; }
                end
                """, "non-void result");
        rejects("""
                define actor Worker as
                  fnc run<T>(T job): T { return job; }
                end
                """, "non-generic");
    }

    @Test
    void requestTransportTypesAreCheckedAtDeclarationWithoutCallers() {
        rejects("""
                define actor Worker as
                  @Implementation
                  fnc run(Channel<int> payload): int {
                    return 1;
                  }
                end
                """, "request payload of actor");
        rejects("""
                define actor Worker as
                  @Implementation
                  fnc run(int delta): Future<int> {
                    return delta;
                  }
                end
                """, "request reply of actor");
    }

    @Test
    void requestAndEventHandlersCannotShareOneActorMailboxAbi() {
        rejects("""
                define actor Worker as
                  fnc run(int job): int { return job; }
                  receive(ActorMail<int> mail): void { return; }
                end
                """, "cannot mix");
        rejects("""
                define actor Worker as
                  fnc run(int job): int { return job; }
                  fnc run(String job): String { return job; }
                end
                """, "cannot overload");
    }

    @Test
    void inheritedEventAndRequestModesCannotMix() {
        rejects("""
                define actor Parent as
                  receive(ActorMail<int> mail): void { return; }
                end

                define actor Child extends Parent as
                  fnc run(int job): int { return job; }
                end
                """, "cannot mix inherited");
        rejects("""
                define actor Parent as
                  fnc run(int job): int { return job; }
                end

                define actor Child extends Parent as
                  receive(ActorMail<int> mail): void { return; }
                end
                """, "cannot mix inherited");
    }

    @Test
    void handlersCannotBeCalledOrCapturedViaSelfEvenInNestedCode() {
        rejects("""
                define actor Worker as
                  fnc run(int job): int {
                    return self.run(job);
                  }
                end
                """, "cannot be called directly");
        rejects("""
                define actor Worker as
                  fnc run(int job): int {
                    val callback = self.run;
                    return job;
                  }
                end
                """, "cannot be called directly");
        rejects("""
                define actor Worker as
                  fnc run(int job): int {
                    val callback = || -> {
                      self.run(job);
                      return;
                    };
                    return job;
                  }
                end
                """, "cannot be called directly");
        rejects("""
                define actor Worker as
                  on_start(): void {
                    self.on_start();
                    return;
                  }
                end
                """, "cannot be called directly");
        rejects("""
                define actor Worker as
                  receive(ActorMail<int> mail): void {
                    self.receive(mail);
                    return;
                  }
                end
                """, "cannot be called directly");
    }

    @Test
    void actorsStillCannotDeclareConstructorsOrUseSuper() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Worker as
                  constructor() {}
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Worker as
                  fnc run(int job): int {
                    super.run(job);
                    return job;
                  }
                end
                """));
        rejects("""
                define actor Worker as
                  fnc run(int job): int { return job; }
                end
                pub routine main(): void {
                  val worker = spawn Worker(123);
                  return;
                }
                """, "does not accept constructor/startup arguments");
    }

    @Test
    void ordinaryClassesAndLegacyEventHandlersRemainSupported() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Worker as
                  pub run(int job): int { return job; }
                end

                define actor Sink as
                  receive(ActorMail<int> mail): void {
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val sink = spawn Sink();
                  await sink.ready;
                  sink.send(5);
                  sink.stop();
                  await sink.done;
                  return;
                }
                """)));
    }

    @Test
    void validatorAlsoProtectsProgrammaticAstCallers() {
        Ast.Program program = Parser.parse("""
                define actor Worker as
                  pub fnc run(int job): int { return job; }
                end
                """);
        assertThrows(IllegalArgumentException.class,
                () -> ActorContractValidator.validate(program));
    }
}
