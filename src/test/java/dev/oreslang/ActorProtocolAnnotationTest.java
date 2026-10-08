package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorProtocolAnnotationTest {
    private static void accepts(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void rejects(String source, String expected) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    private static void rejectsActorAbiMismatch(String source) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("changes inherited mailbox")
                        || error.getMessage().contains("conflicting member"),
                error.getMessage());
    }

    @Test
    void implementationMarksUnaryAndStreamRuntimeHooks() {
        accepts("""
                define actor Worker as
                  let int count;
                  @Implementation
                  on_start(): void {
                    self.count = 0;
                    return;
                  }
                  @Implementation
                  fnc run(int delta): int {
                    self.count = self.count + delta;
                    return self.count;
                  }
                end
                """);
        accepts("""
                define actor Sink as
                  @Implementation
                  receive(ActorMail<int> mail): void {
                    self.send(mail.value);
                    return;
                  }
                end
                """);
    }

    @Test
    void legacyHandlersRemainValidWithoutAnnotations() {
        accepts("""
                define actor Worker as
                  fnc run(int delta): int { return delta; }
                end
                define actor Sink as
                  receive(ActorMail<int> mail): void { return; }
                end
                """);
    }

    @Test
    void overrideRequiresMatchingInheritedInstanceSlot() {
        accepts("""
                define actor Parent as
                  @Implementation
                  fnc run(int delta): int { return delta; }
                end
                define actor Child extends Parent as
                  @Implementation
                  @Override
                  fnc run(int delta): int { return delta + 1; }
                end
                """);
        accepts("""
                define class Parent as
                  pub compute(int delta): int { return delta; }
                end
                define class Child extends Parent as
                  @Override
                  pub compute(int delta): int { return delta + 1; }
                end
                """);
        rejects("""
                define actor Worker as
                  @Override
                  fnc run(int delta): int { return delta; }
                end
                """, "no inherited instance method");
        rejects("""
                define class Parent as
                  pub compute(int delta): int { return delta; }
                end
                define class Child extends Parent as
                  @Override
                  pub compute(): int { return 1; }
                end
                """, "no inherited instance method");
    }

    @Test
    void inheritedActorMailboxHandlerMustPreservePayloadAndReplyTypes() {
        rejectsActorAbiMismatch("""
                define actor Parent as
                  @Implementation
                  fnc run(int delta): int { return delta; }
                end
                define actor Child extends Parent as
                  @Implementation
                  @Override
                  fnc run(String delta): int { return 7; }
                end
                """);
        rejectsActorAbiMismatch("""
                define actor Parent as
                  @Implementation
                  fnc run(int delta): int { return delta; }
                end
                define actor Child extends Parent as
                  fnc run(int delta): String { return "wrong"; }
                end
                """);
        rejectsActorAbiMismatch("""
                define actor Parent as
                  @Implementation
                  receive(ActorMail<int> mail): void { return; }
                end
                define actor Child extends Parent as
                  @Implementation
                  @Override
                  receive(ActorMail<String> mail): void { return; }
                end
                """);
    }

    @Test
    void implementationCannotPretendAnOrdinaryMethodIsAnActorHook() {
        rejects("""
                define actor Worker as
                  @Implementation
                  fnc helper(int delta): int { return delta; }
                end
                """, "reserved actor");
        rejects("""
                define class Worker as
                  @Implementation
                  pub run(int delta): int { return delta; }
                end
                """, "reserved actor");
    }

    @Test
    void annotationsMustBeUniqueAndArgumentFree() {
        rejects("""
                define actor Worker as
                  @Implementation
                  @Implementation
                  fnc run(int delta): int { return delta; }
                end
                """, "duplicate @Implementation");
        rejects("""
                define actor Worker as
                  @Implementation(true)
                  fnc run(int delta): int { return delta; }
                end
                """, "does not accept arguments");
        rejects("""
                define class Parent as
                  pub compute(int delta): int { return delta; }
                end
                define class Child extends Parent as
                  @Override
                  @Override
                  pub compute(int delta): int { return delta; }
                end
                """, "duplicate @Override");
    }

    @Test
    void annotationsCannotAppearOnFieldsOrTopLevelFunctions() {
        rejects("""
                define actor Worker as
                  @Implementation
                  let int count = 0;
                  fnc run(int delta): int { return delta; }
                end
                """, "only valid on class/actor methods");
        rejects("""
                @Override
                pub fnc helper(): void { return; }
                """, "only valid on class/actor methods");
    }
}
