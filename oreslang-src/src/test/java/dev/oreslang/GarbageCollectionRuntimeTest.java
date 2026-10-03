package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.GcRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class GarbageCollectionRuntimeTest {
    private static final class Cache implements GcRuntime.Scavengeable, AutoCloseable {
        private final AtomicInteger scavenges = new AtomicInteger();
        private final CountDownLatch scavenged = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        @Override public void scavenge() {
            scavenges.incrementAndGet();
            scavenged.countDown();
        }
        @Override public void close() { closed.countDown(); }
    }

    @Test
    void periodicScavengingNeverForcesHostGcAndManualGcIsRateLimited() {
        AtomicInteger hostGc = new AtomicInteger();
        AtomicLong clock = new AtomicLong(100);
        GcRuntime gc = new GcRuntime(2, Duration.ofNanos(10), hostGc::incrementAndGet, clock::get);

        assertNull(gc.safepoint(GcRuntime.Scope.PROCESS, () -> 0));
        GcRuntime.Report periodic = gc.safepoint(GcRuntime.Scope.PROCESS, () -> 3);
        assertNotNull(periodic);
        assertEquals(3, periodic.scavenged());
        assertFalse(periodic.hostGcRequested());
        assertEquals(0, hostGc.get());

        assertTrue(gc.manual(GcRuntime.Scope.PROCESS, 1).hostGcRequested());
        assertFalse(gc.manual(GcRuntime.Scope.PROCESS, 0).hostGcRequested());
        assertEquals(1, hostGc.get());

        clock.addAndGet(11);
        assertTrue(gc.manual(GcRuntime.Scope.ACTOR, 0).hostGcRequested());
        assertEquals(2, hostGc.get());
    }

    @Test
    void actorGcIsCurrentActorScopedAndActorLocalsAreDroppedOnExit() throws Exception {
        AtomicInteger hostGc = new AtomicInteger();
        GcRuntime gc = new GcRuntime(1000, Duration.ZERO, hostGc::incrementAndGet, System::nanoTime);
        Cache cache = new Cache();
        CountDownLatch collected = new CountDownLatch(1);
        AtomicReference<GcRuntime.Report> report = new AtomicReference<>();

        ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), gc);
        assertThrows(IllegalStateException.class, runtime::gcCurrentActor);

        var ref = runtime.<String>spawn(() -> (message, context) -> {
            context.runtime().currentActorLocal("cache", () -> cache);
            report.set(context.gc());
            collected.countDown();
        });
        ref.send("collect");
        assertTrue(collected.await(2, TimeUnit.SECONDS));
        assertEquals(GcRuntime.Scope.ACTOR, report.get().scope());
        assertEquals(1, cache.scavenges.get());

        runtime.close();
        assertTrue(cache.closed.await(2, TimeUnit.SECONDS));
        assertTrue(hostGc.get() >= 1);
    }

    @Test
    void privateActorsRejectSharedReferencesButSharedActorsAcceptFrozenReadonlySharing() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var shared = runtime.shareReadonly(List.of(1, 2, 3));
            var privateRef = runtime.<Object>spawn(() -> (message, context) -> { });
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> privateRef.send(List.of(Map.of("nested", shared))));
            assertTrue(denied.getMessage().contains("private actor"));

            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorKind> kind = new AtomicReference<>();
            var sharedRef = runtime.<Object>spawnShared(() -> (message, context) -> {
                kind.set(context.kind());
                delivered.countDown();
            });
            sharedRef.send(shared);
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(ActorRuntime.ActorKind.SHARED, kind.get());
        }
    }

    @Test
    void processGcWakesIdleActorsButEachActorScavengesItsOwnRoots() throws Exception {
        GcRuntime gc = new GcRuntime(1000, Duration.ofHours(1), () -> { }, System::nanoTime);
        Cache cache = new Cache();
        CountDownLatch seeded = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), gc)) {
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                context.runtime().currentActorLocal("cache", () -> cache);
                seeded.countDown();
            });

            ref.send("seed");
            assertTrue(seeded.await(2, TimeUnit.SECONDS));
            assertEquals(0, cache.scavenges.get());

            runtime.requestProcessGc();
            assertTrue(cache.scavenged.await(2, TimeUnit.SECONDS));
            assertEquals(1, cache.scavenges.get());
        }
    }

    @Test
    void gcSurfaceTypeChecksAndCapabilityPolicyFailsClosed() {
        String source = """
                pub routine main() => void {
                  val report = process.gc();
                  print(report.scavenged);
                  return;
                }

                pub fnc actor_cleanup() => void {
                  actor.gc();
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.validateForIsolate(source, IsolatePolicy.developer()));
        SecurityException denied = assertThrows(SecurityException.class,
                () -> OresCompiler.validateForIsolate(source, IsolatePolicy.strictFaas()));
        assertTrue(denied.getMessage().contains("GC_CONTROL"));

        IllegalArgumentException arity = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main() => void {
                          process.gc(1);
                          return;
                        }
                        """));
        assertTrue(arity.getMessage().contains("takes no arguments"));
    }
}
