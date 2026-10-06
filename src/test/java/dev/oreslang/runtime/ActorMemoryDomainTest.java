package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorMemoryDomainTest {
    private static final long MIB = 1024L * 1024L;

    private static IsolatePolicy smallPolicy() {
        IsolatePolicy developer = IsolatePolicy.developer();
        return new IsolatePolicy(developer.capabilities(), 16 * MIB, 64, Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @EnumSource(ActorRuntime.ActorKind.class)
    void localOperationsPreserveActorIdentityAndDoNotGrantSharedMemory(ActorRuntime.ActorKind kind) {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertTrue(runtime.currentActorMemoryDomain().isEmpty());
            long result = runtime.invoke(kind, 1L, (message, context) -> {
                ActorMemoryDomain domain = context.memoryDomain();
                assertSame(domain, runtime.currentActorMemoryDomain().orElseThrow());
                assertEquals(context.self().id(), domain.owner());
                assertEquals(kind, domain.kind());
                assertEquals(context.policy(), domain.policy());
                long before = runtime.actorMemoryBytes();
                for (ActorMemoryDomain.Operation operation : ActorMemoryDomain.Operation.values()) {
                    assertDoesNotThrow(() -> helper(domain, operation));
                }
                assertEquals(before, runtime.actorMemoryBytes());
                if (kind != ActorRuntime.ActorKind.SHARED) {
                    assertFalse(domain.policy().allows(IsolatePolicy.Capability.SHARED_MEMORY));
                    assertFalse(domain.policy().allows(IsolatePolicy.Capability.ACTOR_SHARE_READONLY));
                }
                return message;
            });
            assertEquals(1L, result);
        }
    }

    private static void helper(ActorMemoryDomain domain, ActorMemoryDomain.Operation operation) {
        domain.requireLocalOperation(operation, domain);
    }

    @ParameterizedTest
    @EnumSource(ActorRuntime.ActorKind.class)
    void copyReservesBeforeConstructionAndDeterministicCleanupIsIdempotent(ActorRuntime.ActorKind kind) {
        try (ActorRuntime runtime = new ActorRuntime(); RuntimeGarbageCollector gc = new RuntimeGarbageCollector()) {
            runtime.invoke(kind, 1L, (message, context) -> {
                ActorMemoryDomain domain = context.memoryDomain();
                byte[] original = {1, 2, 3};
                long before = runtime.actorMemoryBytes();
                var copy = domain.allocateCopy(domain, 256, () -> {
                    assertEquals(before + 256, runtime.actorMemoryBytes());
                    return original.clone();
                }, gc);
                assertSame(domain, copy.domain());
                assertNotSame(original, copy.value());
                copy.value()[0] = 9;
                assertEquals(1, original[0]);
                assertEquals(before + 256, runtime.actorMemoryBytes());
                copy.close();
                copy.close();
                assertEquals(before, runtime.actorMemoryBytes());
                assertThrows(IllegalStateException.class, copy::value);
                return message;
            });
        }
    }

    @ParameterizedTest
    @EnumSource(ActorRuntime.ActorKind.class)
    void quotaFailureDoesNotExecuteCopyPlan(ActorRuntime.ActorKind kind) {
        try (ActorRuntime runtime = new ActorRuntime(smallPolicy()); RuntimeGarbageCollector gc = new RuntimeGarbageCollector()) {
            runtime.invoke(kind, 1L, (message, context) -> {
                ActorMemoryDomain domain = context.memoryDomain();
                long before = runtime.actorMemoryBytes();
                AtomicInteger plans = new AtomicInteger();
                assertThrows(IllegalStateException.class, () -> domain.allocateCopy(
                        domain, domain.policy().maxHeapBytes(), () -> {
                            plans.incrementAndGet();
                            return new byte[1];
                        }, gc));
                assertEquals(0, plans.get());
                assertEquals(before, runtime.actorMemoryBytes());
                return message;
            });
        }
    }

    @ParameterizedTest
    @EnumSource(ActorRuntime.ActorKind.class)
    void throwingOrNullCopyPlanRollsBackReservation(ActorRuntime.ActorKind kind) {
        try (ActorRuntime runtime = new ActorRuntime(); RuntimeGarbageCollector gc = new RuntimeGarbageCollector()) {
            runtime.invoke(kind, 1L, (message, context) -> {
                ActorMemoryDomain domain = context.memoryDomain();
                long before = runtime.actorMemoryBytes();
                IllegalArgumentException failure = new IllegalArgumentException("copy failed");
                assertSame(failure, assertThrows(IllegalArgumentException.class,
                        () -> domain.allocateCopy(domain, 256, () -> { throw failure; }, gc)));
                assertThrows(NullPointerException.class,
                        () -> domain.allocateCopy(domain, 256, () -> null, gc));
                assertThrows(IllegalArgumentException.class, () -> domain.reserveAllocation(-1));
                assertThrows(IllegalStateException.class, () -> domain.reserveAllocation(Long.MAX_VALUE));
                assertEquals(before, runtime.actorMemoryBytes());
                return message;
            });
        }
    }

    @ParameterizedTest
    @EnumSource(ActorRuntime.ActorKind.class)
    void cleanupRegistrationFailureAlsoRollsBack(ActorRuntime.ActorKind kind) {
        try (ActorRuntime runtime = new ActorRuntime(); RuntimeGarbageCollector gc = new RuntimeGarbageCollector(
                () -> {}, Duration.ofHours(1), Duration.ofHours(1), 1)) {
            Object retained = new Object();
            var existing = gc.track(retained, () -> {});
            runtime.invoke(kind, 1L, (message, context) -> {
                ActorMemoryDomain domain = context.memoryDomain();
                long before = runtime.actorMemoryBytes();
                assertThrows(IllegalStateException.class,
                        () -> domain.allocateCopy(domain, 256, () -> new byte[1], gc));
                assertEquals(before, runtime.actorMemoryBytes());
                return message;
            });
            existing.close();
            java.lang.ref.Reference.reachabilityFence(retained);
        }
    }

    @Test
    void allLocalOperationsRejectForeignActorAndRuntimeDomains() throws Exception {
        try (ActorRuntime first = new ActorRuntime(); ActorRuntime second = new ActorRuntime();
                RuntimeGarbageCollector gc = new RuntimeGarbageCollector()) {
            AtomicReference<ActorMemoryDomain> source = new AtomicReference<>();
            CountDownLatch initialized = new CountDownLatch(1);
            var sourceRef = first.<Long>spawnPrivateTrusted(context -> {
                source.set(context.memoryDomain());
                initialized.countDown();
                return (message, turn) -> {};
            });
            sourceRef.send(1L);
            assertTrue(initialized.await(2, TimeUnit.SECONDS));
            for (ActorRuntime runtime : List.of(first, second)) {
                for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                    runtime.invoke(kind, 1L, (message, context) -> {
                        ActorMemoryDomain destination = context.memoryDomain();
                        for (ActorMemoryDomain.Operation operation : ActorMemoryDomain.Operation.values()) {
                            assertThrows(SecurityException.class,
                                    () -> destination.requireLocalOperation(operation, source.get()));
                        }
                        AtomicInteger copies = new AtomicInteger();
                        long before = runtime.actorMemoryBytes();
                        assertThrows(SecurityException.class, () -> destination.allocateCopy(source.get(), 256,
                                () -> { copies.incrementAndGet(); return new byte[256]; }, gc));
                        assertEquals(0, copies.get());
                        // Other one-shot actors can finish concurrently; a
                        // rejected plan must not add any charge of its own.
                        assertTrue(runtime.actorMemoryBytes() <= before);
                        assertThrows(SecurityException.class, source.get()::requireCurrentOwner);
                        ActorRuntime foreign = runtime == first ? second : first;
                        assertThrows(SecurityException.class, foreign::currentActorMemoryDomain);
                        return message;
                    });
                }
            }
            assertThrows(SecurityException.class, source.get()::requireCurrentOwner);
        }
    }

    @Test
    void sharedHeapAndMailboxUseOneActorLimit() {
        try (ActorRuntime runtime = new ActorRuntime(smallPolicy())) {
            runtime.invoke(ActorRuntime.ActorKind.SHARED, 1L, (message, context) -> {
                ActorMemoryDomain domain = context.memoryDomain();
                long mailboxBytes = runtime.actorMemoryBytes();
                assertTrue(mailboxBytes > 0);
                try (var full = domain.reserveAllocation(domain.policy().maxHeapBytes() - mailboxBytes)) {
                    assertEquals(domain.policy().maxHeapBytes(), runtime.actorMemoryBytes());
                    assertThrows(IllegalStateException.class, () -> domain.reserveAllocation(1));
                    assertThrows(IllegalStateException.class, () -> context.self().send(2L));
                }
                assertEquals(mailboxBytes, runtime.actorMemoryBytes());
                return message;
            });
        }
    }

    @Test
    void allocationsAcrossActorsAndKindsShareTheRuntimeCeiling() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(smallPolicy())) {
            CountDownLatch allocated = new CountDownLatch(1);
            var holder = runtime.<Long>spawnPrivateTrusted(context -> {
                context.memoryDomain().reserveAllocation(9 * MIB);
                allocated.countDown();
                return (message, turn) -> {};
            });
            holder.send(1L);
            assertTrue(allocated.await(2, TimeUnit.SECONDS));
            for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                runtime.invoke(kind, 1L, (message, context) -> {
                    assertThrows(IllegalStateException.class,
                            () -> context.memoryDomain().reserveAllocation(9 * MIB));
                    return message;
                });
            }
        }
    }

    @Test
    void payloadOutlivesMailboxChargeButActorRetirementRevokesIt() throws Exception {
        CountDownLatch turnLeft = new CountDownLatch(1);
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(); ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 1),
                turn -> { turn.run(); turnLeft.countDown(); })) {
            runtime.setActorExitHook(gc::retireActorDomain);
            AtomicReference<ActorMemoryDomain.Allocation<byte[]>> retained = new AtomicReference<>();
            var ref = runtime.<Long>spawnPrivateTrusted(context -> {
                ActorMemoryDomain domain = context.memoryDomain();
                retained.set(domain.allocateCopy(domain, 256, () -> new byte[256], gc));
                return (message, turn) -> {};
            });
            ref.send(1L);
            assertTrue(turnLeft.await(2, TimeUnit.SECONDS));
            assertEquals(256, runtime.privateMemoryBytes());
            assertThrows(SecurityException.class, retained.get()::value);
            ref.stop();
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(retained.get().domain().retired());
            assertThrows(IllegalStateException.class, retained.get()::value);
            retained.get().close();
            retained.get().close();
            assertEquals(0, runtime.actorMemoryBytes());
            assertEquals(0, gc.collectProcess().trackedAfter());
        }
    }

    @Test
    void cancellationDuringCopyDoesNotPublishOrLeak() {
        try (ActorRuntime runtime = new ActorRuntime(); RuntimeGarbageCollector gc = new RuntimeGarbageCollector()) {
            runtime.invoke(ActorRuntime.ActorKind.PRIVATE, 1L, (message, context) -> {
                ActorMemoryDomain domain = context.memoryDomain();
                long before = runtime.actorMemoryBytes();
                assertThrows(ActorRuntime.ActorCancellationSignal.class, () -> domain.allocateCopy(domain, 256, () -> {
                    context.self().stop();
                    return new byte[256];
                }, gc));
                assertEquals(before, runtime.actorMemoryBytes());
                return message;
            });
        }
    }

    @ParameterizedTest
    @EnumSource(ActorRuntime.ActorKind.class)
    void retirementReleasesLiveAllocationAndRejectsItsOldDomain(ActorRuntime.ActorKind kind) throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(); RuntimeGarbageCollector gc = new RuntimeGarbageCollector()) {
            AtomicReference<ActorMemoryDomain.Allocation<byte[]>> retained = new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorRef<Long>> ref = new AtomicReference<>();
            runtime.invoke(kind, 1L, (message, context) -> {
                ActorMemoryDomain domain = context.memoryDomain();
                ref.set(context.self());
                retained.set(domain.allocateCopy(domain, 256, () -> new byte[256], gc));
                return message;
            });
            assertTrue(ref.get().awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorMemoryBytes());
            assertTrue(retained.get().domain().retired());
            assertThrows(IllegalStateException.class, retained.get()::value);
            retained.get().close();
            retained.get().close();
            runtime.invoke(kind, 1L, (message, context) -> {
                assertNotSame(retained.get().domain(), context.memoryDomain());
                assertThrows(SecurityException.class, () -> context.memoryDomain().requireLocalOperation(
                        ActorMemoryDomain.Operation.BORROW, retained.get().domain()));
                return message;
            });
        }
    }

    private static final class ReservedBehavior implements ActorRuntime.Behavior<Long> {
        private final ActorMemoryDomain domain;
        private final ActorMemoryDomain.Reservation reservation;

        private ReservedBehavior(ActorMemoryDomain domain) {
            this.domain = domain;
            this.reservation = domain.reserveAllocation(256);
        }

        @Override public void onMessage(Long message, ActorRuntime.ActorContext<Long> context) {
            assertSame(domain, context.memoryDomain());
            assertSame(domain, reservation.domain());
            domain.requireLocalOperation(ActorMemoryDomain.Operation.SHARE, domain);
            context.self().stop();
        }
    }

    @Test
    void statelessUntrustedFactoryCanRetainOnlyItsOwnDomainAndReservation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Long>spawnUntrusted(context -> new ReservedBehavior(context.memoryDomain()));
            ref.send(1L);
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty(), () -> ref.failure().toString());
            assertEquals(0, runtime.actorMemoryBytes());
        }
    }

    @ParameterizedTest
    @EnumSource(ActorRuntime.ActorKind.class)
    void explicitCleanupRacingActorRetirementDoesNotDeadlockOrDoubleRelease(ActorRuntime.ActorKind kind) throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch allocated = new CountDownLatch(1);
            AtomicReference<ActorMemoryDomain.Reservation> reservation = new AtomicReference<>();
            ActorRuntime.BehaviorFactory<Long> factory = context -> {
                reservation.set(context.memoryDomain().reserveAllocation(256));
                allocated.countDown();
                return (message, turn) -> {};
            };
            // Trusted host fixture captures the observation latch only. The
            // untrusted kind is also covered by the one-shot invocation fixture
            // because it deliberately disallows captured factories.
            if (kind == ActorRuntime.ActorKind.UNTRUSTED) {
                runtime.invoke(kind, 1L, (message, context) -> {
                    var held = context.memoryDomain().reserveAllocation(256);
                    Thread cleanup = new Thread(held::close);
                    cleanup.start();
                    context.self().stop();
                    cleanup.join(2_000);
                    assertFalse(cleanup.isAlive());
                    held.close();
                    return message;
                });
                return;
            }
            var ref = kind == ActorRuntime.ActorKind.SHARED
                    ? runtime.spawnSharedTrusted(factory) : runtime.spawnPrivateTrusted(factory);
            ref.send(1L);
            assertTrue(allocated.await(2, TimeUnit.SECONDS));
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable cleanup = () -> {
                try { assertTrue(start.await(2, TimeUnit.SECONDS)); reservation.get().close(); }
                catch (Throwable problem) { failure.set(problem); }
            };
            Thread cleaner = new Thread(cleanup);
            cleaner.start();
            start.countDown();
            ref.stop();
            cleaner.join(2_000);
            assertFalse(cleaner.isAlive());
            assertNull(failure.get());
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(reservation.get().released());
            reservation.get().close();
            assertEquals(0, runtime.actorMemoryBytes());
        }
    }

    @Test
    void producerCompletionRestoresDomainOnADifferentWorker() throws Exception {
        CountDownLatch initialTurnLeft = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);
        AtomicInteger turns = new AtomicInteger();
        ActorRuntime.TurnExecutor freshWorker = turn -> {
            Thread worker = new Thread(turn, "ownership-domain-test-worker-" + turns.incrementAndGet());
            worker.start();
            try { worker.join(2_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
            if (worker.isAlive()) throw new IllegalStateException("actor test worker did not finish");
            initialTurnLeft.countDown();
        };
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1), freshWorker)) {
            OresFuture<Long> producer = new OresFuture<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            var ref = runtime.<Long>spawnPrivateTrusted(context -> (message, turn) -> {
                ActorMemoryDomain domain = turn.memoryDomain();
                Thread first = Thread.currentThread();
                runtime.enqueueOnCompletion(producer, runtime.captureCurrentContinuationTarget(), (value, error) -> {
                    try {
                        assertNull(error);
                        assertNotSame(first, Thread.currentThread());
                        assertSame(domain, runtime.currentActorMemoryDomain().orElseThrow());
                        helper(domain, ActorMemoryDomain.Operation.SHARE);
                    } catch (Throwable problem) { failure.set(problem); }
                    finally { resumed.countDown(); }
                });
            });
            ref.send(1L);
            assertTrue(initialTurnLeft.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.currentActorMemoryDomain().isEmpty());
            producer.completeFromRuntime(1L);
            assertTrue(resumed.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }
}
