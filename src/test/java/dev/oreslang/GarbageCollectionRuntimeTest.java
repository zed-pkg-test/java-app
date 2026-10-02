package dev.oreslang;

import com.oracle.truffle.api.source.Source;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.GarbageCollector;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class GarbageCollectionRuntimeTest {
    @Test
    void processGcIsAValidBoundedRuntimeHint() throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, """
                pub routine main() => void {
                  process.gc();
                  process.gc();
                  return;
                }
                """, "gc.ores").mimeType(OresLanguage.MIME_TYPE).build();

        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertDoesNotThrow(() -> context.eval(source));
        }
    }

    @Test
    void repeatedExplicitGcRequestsAreRateLimited() {
        GarbageCollector gc = new GarbageCollector();
        var first = gc.request(GarbageCollector.Scope.PROCESS);
        var second = gc.request(GarbageCollector.Scope.PROCESS);

        assertTrue(first.hostGcHintIssued());
        assertFalse(second.hostGcHintIssued());
        assertEquals(2L, gc.descriptor().get("requests"));
        assertEquals(1L, gc.descriptor().get("suppressed_requests"));
    }

    @Test
    void actorGcCannotPretendToBeActorLocalOutsideActorExecution() {
        GarbageCollector gc = new GarbageCollector();
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> gc.request(GarbageCollector.Scope.ACTOR));
        assertTrue(error.getMessage().contains("actor.gc()"));
    }

    @Test
    void actorGcIsAvailableInsideActorExecutionDomain() throws Exception {
        GarbageCollector gc = new GarbageCollector();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawn(() -> (message, context) -> {
                try {
                    var result = gc.request(GarbageCollector.Scope.ACTOR);
                    assertEquals(GarbageCollector.Scope.ACTOR, result.scope());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    done.countDown();
                }
            });
            ref.send("collect");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }
}
