package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;

import java.lang.ref.Reference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimeGarbageCollectorTest {
    // Enqueue deterministically: these tests exercise queue bookkeeping, not
    // the JVM's nondeterministic decision to collect an owner.
    private static void enqueue(RuntimeGarbageCollector.CleanupHandle handle) throws Exception {
        var entry = handle.getClass().getDeclaredField("entry");
        entry.setAccessible(true);
        Reference<?> reference = (Reference<?>) entry.get(handle);
        reference.clear();
        assertTrue(reference.enqueue());
    }

    @Test
    void emptyRetiredDomainsAndInvalidRegistrationsDoNotRetainHeaps() throws Exception {
        try (var gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            for (int i = 0; i < 1000; i++) gc.retireActorDomain(new Object());
            assertThrows(NullPointerException.class, () -> gc.track(new Object(), null));
            for (String field : new String[] {"retiredDomains", "trackedByDomain"}) {
                var declared = gc.getClass().getDeclaredField(field);
                declared.setAccessible(true);
                Object value = declared.get(gc);
                assertTrue(value instanceof java.util.Map<?, ?> map ? map.isEmpty()
                        : ((java.util.Set<?>) value).isEmpty(), field);
            }
        }
    }

    @Test
    void lateFailingHandleCannotRepopulateClosedCollector() throws Exception {
        var gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1));
        var handle = gc.track(new Object(), () -> { throw new IllegalStateException("failure"); });
        gc.close();
        assertThrows(IllegalStateException.class, handle::close);
        var field = gc.getClass().getDeclaredField("retryableFailures");
        field.setAccessible(true);
        assertTrue(((java.util.Set<?>) field.get(gc)).isEmpty());
    }

    @Test
    void completedHandleReleasesCleanupClosure() throws Exception {
        try (var gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            var handle = gc.track(new Object(), () -> {});
            handle.close();
            var field = handle.getClass().getDeclaredField("entry");
            field.setAccessible(true);
            var entry = field.get(handle);
            var cleanup = entry.getClass().getDeclaredField("cleanup");
            cleanup.setAccessible(true);
            assertNull(cleanup.get(entry));
        }
    }

    @Test
    void explicitReleaseFailureIsRetriedWhileOwnerRemainsReachable() {
        AtomicInteger attempts = new AtomicInteger();
        Object owner = new Object();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            var handle = gc.track(owner, () -> {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("transient");
            });
            assertThrows(IllegalStateException.class, handle::close);
            assertEquals(1, attempts.get());

            var retry = gc.collectPeriodic();
            assertEquals(1, retry.cleaned());
            assertEquals(0, retry.trackedAfter());
            assertEquals(2, attempts.get());

            java.lang.ref.Reference.reachabilityFence(owner);
        }
    }

    @Test
    void queuedFailureIsRetriedAfterItsNotificationWasConsumed() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            var handle = gc.track(new Object(), () -> {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("transient");
            });
            enqueue(handle);
            var first = gc.collectPeriodic();
            assertEquals(1, first.cleanupFailures());
            assertEquals(1, first.trackedAfter());
            var retry = gc.collectPeriodic();
            assertEquals(1, retry.cleaned());
            assertEquals(0, retry.trackedAfter());
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void queueDrainDuringFailingExplicitReleaseRemainsRetryable() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Throwable> releaseFailure = new AtomicReference<>();
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch failRelease = new CountDownLatch(1);
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            var handle = gc.track(new Object(), () -> {
                if (attempts.incrementAndGet() == 1) {
                    cleaning.countDown();
                    try {
                        if (!failRelease.await(2, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                    throw new IllegalStateException("transient");
                }
            });
            Thread releasing = new Thread(() -> {
                try { handle.close(); }
                catch (Throwable failure) { releaseFailure.set(failure); }
            });
            releasing.start();
            try {
                assertTrue(cleaning.await(2, TimeUnit.SECONDS));
                enqueue(handle);
                assertEquals(0, gc.collectPeriodic().cleaned());
            } finally {
                failRelease.countDown();
                releasing.join(2_000);
            }
            assertFalse(releasing.isAlive());
            assertInstanceOf(IllegalStateException.class, releaseFailure.get());
            var retry = gc.collectPeriodic();
            assertEquals(1, retry.cleaned());
            assertEquals(0, retry.trackedAfter());
            handle.close();
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void actorExitFailureIsRetriedWithAStillReachableOwner() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Object owner = new Object();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            runtime.setActorExitHook(gc::retireActorDomain);
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                gc.track(owner, () -> {
                    if (attempts.incrementAndGet() == 1) throw new IllegalStateException("transient");
                });
                context.self().stop();
            });
            ref.send("stop");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(1, attempts.get());
            assertEquals(1, gc.collectPeriodic().cleaned());
            assertEquals(2, attempts.get());
            java.lang.ref.Reference.reachabilityFence(owner);
        }
    }

    @Test
    void contextShutdownCleansLiveProcessAndActorResourcesAndClosesRegistry() throws Exception {
        AtomicInteger processCleanups = new AtomicInteger();
        AtomicInteger actorCleanups = new AtomicInteger();
        Object processOwner = new Object();
        Object actorOwner = new Object();
        Context context = Context.newBuilder(OresLanguage.ID).build();
        OresContext runtimeContext;
        try {
            context.initialize(OresLanguage.ID);
            context.enter();
            try { runtimeContext = OresContext.get(null); }
            finally { context.leave(); }
            var gc = runtimeContext.garbageCollector();
            gc.track(processOwner, processCleanups::incrementAndGet);
            CountDownLatch registered = new CountDownLatch(1);
            var actor = runtimeContext.actors().<String>spawn(() -> (message, actorContext) -> {
                gc.track(actorOwner, actorCleanups::incrementAndGet);
                registered.countDown();
            });
            actor.send("register");
            assertTrue(registered.await(2, TimeUnit.SECONDS));
            context.close(true);
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(1, processCleanups.get());
            assertEquals(1, actorCleanups.get());
            assertThrows(IllegalStateException.class, gc::collectPeriodic);
            assertThrows(IllegalStateException.class, () -> gc.track(new Object(), () -> {}));
            java.lang.ref.Reference.reachabilityFence(processOwner);
            java.lang.ref.Reference.reachabilityFence(actorOwner);
        } finally {
            context.close(true);
        }
    }

    @Test
    void processCollectionRequestsJvmGcAtMostOncePerThrottleWindow() {
        AtomicInteger gcRequests = new AtomicInteger();
        Object owner = new Object();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(
                gcRequests::incrementAndGet, Duration.ofHours(1), Duration.ofHours(1), 16)) {
            gc.track(owner, () -> fail("live owner must not be cleaned"));
            var first = gc.collectProcess();
            var second = gc.collectProcess();
            assertTrue(first.jvmGcRequested());
            assertFalse(second.jvmGcRequested());
            assertEquals(1, gcRequests.get());
            assertEquals(1, first.trackedAfter());
            assertNotNull(owner);
        }
    }

    @Test
    void cleanupHandleIsDeterministicAndIdempotent() {
        AtomicInteger cleanups = new AtomicInteger();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            var handle = gc.track(new Object(), cleanups::incrementAndGet);
            handle.close();
            handle.close();
            assertEquals(1, cleanups.get());
        }
    }

    @Test
    void failedCleanupRemainsRetryable() {
        AtomicInteger attempts = new AtomicInteger();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            var handle = gc.track(new Object(), () -> {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("transient");
            });
            assertThrows(IllegalStateException.class, handle::close);
            assertDoesNotThrow(handle::close);
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void registryGrowthIsBounded() {
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(
                () -> {}, Duration.ofHours(1), Duration.ofSeconds(1), 1)) {
            Object owner = new Object();
            gc.track(owner, () -> {});
            assertThrows(IllegalStateException.class, () -> gc.track(new Object(), () -> {}));
            assertNotNull(owner);
        }
    }

    @Test
    void actorCollectionIsDomainLocalAndNeverRequestsJvmGc() throws Exception {
        AtomicInteger gcRequests = new AtomicInteger();
        AtomicReference<RuntimeGarbageCollector.CollectionReport> report = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(gcRequests::incrementAndGet, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                report.set(gc.collectCurrentActor());
                done.countDown();
            });
            ref.send("gc");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals("actor", report.get().scope());
            assertFalse(report.get().jvmGcRequested());
            assertEquals(0, gcRequests.get());
        }
    }


    @Test
    void actorCollectionReportsOnlyItsOwnIndexedDomain() throws Exception {
        AtomicReference<RuntimeGarbageCollector.CollectionReport> firstReport = new AtomicReference<>();
        AtomicReference<RuntimeGarbageCollector.CollectionReport> secondReport = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);

        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            var first = runtime.<String>spawn(() -> (message, context) -> {
                Object owner = new Object();
                gc.track(owner, () -> {});
                registered.countDown();
                assertTrue(registered.await(2, TimeUnit.SECONDS));
                firstReport.set(gc.collectCurrentActor());
                assertNotNull(owner);
                done.countDown();
            });
            var second = runtime.<String>spawn(() -> (message, context) -> {
                Object owner = new Object();
                gc.track(owner, () -> {});
                registered.countDown();
                assertTrue(registered.await(2, TimeUnit.SECONDS));
                secondReport.set(gc.collectCurrentActor());
                assertNotNull(owner);
                done.countDown();
            });

            first.send("gc");
            second.send("gc");

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(1, firstReport.get().trackedBefore());
            assertEquals(1, secondReport.get().trackedBefore());
            assertEquals(1, firstReport.get().inspected());
            assertEquals(1, secondReport.get().inspected());
        }
    }

    @Test
    void actorCollectionHasABoundedInspectionQuantum() throws Exception {
        AtomicReference<RuntimeGarbageCollector.CollectionReport> report = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                ArrayList<Object> owners = new ArrayList<>();
                for (int i = 0; i < 300; i++) {
                    Object owner = new Object();
                    owners.add(owner);
                    gc.track(owner, () -> {});
                }
                report.set(gc.collectCurrentActor());
                assertEquals(300, owners.size());
                done.countDown();
            });

            ref.send("gc");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(300, report.get().trackedBefore());
            assertEquals(256, report.get().inspected());
            assertEquals(300, report.get().trackedAfter());
            assertFalse(report.get().jvmGcRequested());
        }
    }


    @Test
    void actorExitDeterministicallyRetiresItsCleanupDomain() throws Exception {
        AtomicInteger cleanups = new AtomicInteger();
        AtomicReference<Object> leakedOwner = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1);

        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            runtime.setActorExitHook(gc::retireActorDomain);
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                Object owner = new Object();
                leakedOwner.set(owner); // deliberately keep a stale host reference alive
                gc.track(owner, cleanups::incrementAndGet);
                registered.countDown();
                context.self().stop();
            });

            ref.send("stop");
            assertTrue(registered.await(2, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertNotNull(leakedOwner.get());
            assertEquals(1, cleanups.get(),
                    "actor termination must retire actor-local runtime resources even with a stale owner reference");
        }
    }

    @Test
    void actorCollectionOutsideActorIsRejected() {
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            var error = assertThrows(IllegalStateException.class, gc::collectCurrentActor);
            assertTrue(error.getMessage().contains("actor.gc() requires execution inside an actor"));
        }
    }

    @Test
    void actorExitPerformsOnlyABoundedSynchronousCleanupQuantum() throws Exception {
        AtomicInteger cleanups = new AtomicInteger();
        AtomicReference<ArrayList<Object>> reachableOwners = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1);

        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(
                     () -> {}, Duration.ofHours(1), Duration.ofHours(1), 64, 64, 2);
             ActorRuntime runtime = new ActorRuntime()) {
            runtime.setActorExitHook(gc::retireActorDomain);
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                var owners = new ArrayList<Object>();
                for (int i = 0; i < 5; i++) {
                    Object owner = new Object();
                    owners.add(owner);
                    gc.track(owner, cleanups::incrementAndGet);
                }
                reachableOwners.set(owners);
                registered.countDown();
                context.self().stop();
            });
            ref.send("stop");
            assertTrue(registered.await(2, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(5, reachableOwners.get().size());
            assertEquals(2, cleanups.get(), "actor exit must not synchronously drain the entire heap");

            var maintenance = gc.collectPeriodic();
            assertEquals(3, maintenance.cleaned());
            assertEquals(5, cleanups.get(), "periodic maintenance must drain retired actor hooks");
        }
    }

    @Test
    void periodicAndExplicitProcessSweepsHaveHardInspectionBudgets() {
        AtomicInteger attempts = new AtomicInteger();
        var owners = new ArrayList<Object>();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(
                     () -> {}, Duration.ofHours(1), Duration.ofHours(1), 6_000)) {
            for (int i = 0; i < 5_000; i++) {
                Object owner = new Object();
                owners.add(owner);
                var handle = gc.track(owner, () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("poisoned external cleanup");
                });
                assertThrows(IllegalStateException.class, handle::close);
            }
            int before = attempts.get();
            var periodic = gc.collectPeriodic();
            assertTrue(periodic.inspected() <= 1_024, "periodic cleanup must stay bounded");
            assertTrue(attempts.get() - before <= 1_024);
            before = attempts.get();
            var explicit = gc.collectProcess();
            assertTrue(explicit.inspected() <= 4_096, "process sweep must stay bounded");
            assertTrue(attempts.get() - before <= 4_096);
            assertEquals(5_000, owners.size());
        }
    }

    @Test
    void repeatedEmptyActorRetirementDoesNotRetainDomains() throws Exception {
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            for (int i = 0; i < 2_000; i++) {
                var report = gc.retireActorDomain(new Object());
                assertEquals(0, report.inspected());
                assertEquals(0, report.trackedAfter());
            }
            assertEquals(0, gc.collectPeriodic().inspected());
        }
    }

    @Test
    void idleAutomaticSweepDoesNoWorkWithoutFallbackResources() throws Exception {
        try (RuntimeGarbageCollector gc =
                     new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            var tick = RuntimeGarbageCollector.class.getDeclaredMethod("safePeriodicSweep");
            tick.setAccessible(true);
            var counter = RuntimeGarbageCollector.class.getDeclaredField("collections");
            counter.setAccessible(true);
            AtomicLong collections = (AtomicLong) counter.get(gc);

            tick.invoke(gc);
            assertEquals(0, collections.get(),
                    "ownership-only workloads must not run empty automatic sweeps");
            Object owner = new Object();
            var handle = gc.track(owner, () -> {});
            tick.invoke(gc);
            assertEquals(1, collections.get(), "tracked fallback handles enable maintenance");
            handle.close();
            tick.invoke(gc);
            assertEquals(1, collections.get(), "deterministic drop disables empty sweeps");
            assertNotNull(owner);
        }
    }
}
