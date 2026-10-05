package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class GarbageCollectionAuthorityTest {

    @Test
    void actorGcIsDirectMailboxTurnAuthorityOnly() {
        var directActor = TypeChecker.check(Parser.parse("""
                pub actor fnc worker() => void {
                  actor.gc();
                  return;
                }
                """));
        assertDoesNotThrow(() ->
                CapabilityChecker.check(directActor, IsolatePolicy.developer()));

        var outsideActor = TypeChecker.check(Parser.parse("""
                pub routine main() => void {
                  actor.gc();
                  return;
                }
                """));
        SecurityException outside = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(outsideActor, IsolatePolicy.developer()));
        assertTrue(outside.getMessage().contains("mailbox context"));

        var extracted = TypeChecker.check(Parser.parse("""
                pub actor fnc worker() => void {
                  val collect = actor.gc;
                  return;
                }
                """));
        SecurityException extraction = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(extracted, IsolatePolicy.developer()));
        assertTrue(extraction.getMessage().contains("cannot be extracted"));

        var escapedClosure = TypeChecker.check(Parser.parse("""
                pub actor fnc worker() => void {
                  val collect = () -> {
                    actor.gc();
                    return;
                  };
                  return;
                }
                """));
        SecurityException closure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(escapedClosure, IsolatePolicy.developer()));
        assertTrue(closure.getMessage().contains("mailbox context"));
    }

    @Test
    void privateActorCannotAdmitProcessGcEvenUnderDeveloperParent() {
        var program = TypeChecker.check(Parser.parse("""
                pub actor fnc worker() => void {
                  process.gc();
                  return;
                }
                """));

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(denied.getMessage().contains("GC_CONTROL"));
    }

    @Test
    void runtimePrivateActorPolicyAlsoStripsProcessGc() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            CountDownLatch observed = new CountDownLatch(1);
            AtomicReference<IsolatePolicy> policy = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    IsolatePolicy.developer(),
                    factoryContext -> {
                        policy.set(factoryContext.policy());
                        observed.countDown();
                        return (message, turn) -> turn.self().stop();
                    });

            actor.send("stop");
            assertTrue(observed.await(2, TimeUnit.SECONDS));
            assertNotNull(policy.get());
            assertFalse(policy.get().allows(IsolatePolicy.Capability.GC_CONTROL));
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
