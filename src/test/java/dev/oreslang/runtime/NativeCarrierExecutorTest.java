package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class NativeCarrierExecutorTest {

    @Test
    void manyLogicalTurnsMultiplexOntoOnePthreadCarrier() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(1, 1, 64, "ores-native-test-")) {
            CountDownLatch done = new CountDownLatch(32);
            Set<Long> pthreadIds = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < 32; i++) {
                executor.execute(() -> {
                    assertTrue(NativeCarrierExecutor.isNativeCarrierThread());
                    assertFalse(Thread.currentThread().isVirtual());
                    assertEquals(0, NativeCarrierExecutor.currentCarrierSlot());
                    long nativeId = NativeCarrierExecutor.currentNativeThreadId();
                    assertNotEquals(0L, nativeId);
                    pthreadIds.add(nativeId);
                    done.countDown();
                });
            }

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, pthreadIds.size(),
                    "logical Ores turns must multiplex over the configured pthread carrier");

            long accountingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.getCompletedTaskCount() != 32L
                    && System.nanoTime() < accountingDeadline) {
                Thread.onSpinWait();
            }
            assertEquals(32L, executor.getCompletedTaskCount(),
                    "completion accounting must publish after each carrier turn returns");
        }
    }
    @Test
    void affinityKeyKeepsTurnsOnOneCarrierWhileLaneIsHealthy() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(2, 2, 64, "ores-native-affinity-")) {
            CountDownLatch peerStarted = new CountDownLatch(1);
            CountDownLatch releasePeer = new CountDownLatch(1);
            CountDownLatch peerFinished = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(24);
            Set<Integer> slots = ConcurrentHashMap.newKeySet();
            Set<Integer> affinityTargets = ConcurrentHashMap.newKeySet();
            Set<Integer> actualCpus = ConcurrentHashMap.newKeySet();

            executor.executeAffinity(1, () -> {
                peerStarted.countDown();
                try {
                    assertTrue(releasePeer.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail(interrupted);
                } finally {
                    peerFinished.countDown();
                }
            });
            assertTrue(peerStarted.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < 24; i++) {
                executor.executeAffinity(0, () -> {
                    slots.add(NativeCarrierExecutor.currentCarrierSlot());
                    affinityTargets.add(NativeCarrierExecutor.currentCarrierAffinityTarget());
                    actualCpus.add(NativeCarrierExecutor.currentCarrierCpu());
                    done.countDown();
                });
            }

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(Set.of(0), slots,
                    "a healthy preferred affinity lane must keep one actor key on one carrier");
            assertEquals(1, affinityTargets.size(),
                    "one carrier lane must expose one stable CPU/cache-affinity target");
            if (os.contains("linux")) {
                assertTrue(affinityTargets.stream().allMatch(target -> target >= 0),
                        "Linux native carriers must prove a real allowed-CPU binding");
                assertEquals(affinityTargets, actualCpus,
                        "a pinned Linux carrier must actually execute on its selected CPU");
                assertEquals(0, executor.getAffinityBindingFailureCount());
            }
            assertTrue(executor.getAffinityPreferredHitCount() >= 24);

            releasePeer.countDown();
            assertTrue(peerFinished.await(5, TimeUnit.SECONDS));
        }
    }


    @Test
    void blockedPreferredCarrierAllowsIdlePeerToStealAfterGrace() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(2, 2, 64, "ores-native-affinity-steal-")) {
            CountDownLatch preferredStarted = new CountDownLatch(1);
            CountDownLatch releasePreferred = new CountDownLatch(1);
            CountDownLatch preferredFinished = new CountDownLatch(1);
            CountDownLatch stolenRan = new CountDownLatch(1);
            Set<Integer> stolenSlots = ConcurrentHashMap.newKeySet();

            executor.executeAffinity(0, () -> {
                preferredStarted.countDown();
                try {
                    assertTrue(releasePreferred.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail(interrupted);
                } finally {
                    preferredFinished.countDown();
                }
            });
            assertTrue(preferredStarted.await(5, TimeUnit.SECONDS));

            // This remains below the backlog-spill threshold, so the only way
            // to make progress is delayed stealing from carrier 0's local lane.
            executor.executeAffinity(0, () -> {
                stolenSlots.add(NativeCarrierExecutor.currentCarrierSlot());
                stolenRan.countDown();
            });

            assertTrue(stolenRan.await(2, TimeUnit.SECONDS),
                    "an idle peer must rescue an actor lane whose preferred carrier is blocked");
            assertEquals(Set.of(1), stolenSlots);
            assertTrue(executor.getAffinityStealCount() >= 1,
                    "rescuing a blocked preferred lane must be observable as a steal");

            releasePreferred.countDown();
            assertTrue(preferredFinished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void affinityBacklogSpillsToGlobalQueueInsteadOfStarvingOnOneCore() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(2, 2, 8, "ores-native-affinity-spill-")) {
            CountDownLatch preferredStarted = new CountDownLatch(1);
            CountDownLatch releasePreferred = new CountDownLatch(1);
            CountDownLatch preferredFinished = new CountDownLatch(1);
            CountDownLatch spillRan = new CountDownLatch(1);
            Set<Integer> spillSlots = ConcurrentHashMap.newKeySet();

            executor.executeAffinity(0, () -> {
                preferredStarted.countDown();
                try {
                    assertTrue(releasePreferred.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail(interrupted);
                } finally {
                    preferredFinished.countDown();
                }
            });
            assertTrue(preferredStarted.await(5, TimeUnit.SECONDS));

            // With capacity=8 and two carriers, four queued turns remain local.
            // The next turn must spill to the global queue and remain runnable.
            for (int i = 0; i < 4; i++) {
                executor.executeAffinity(0, () -> { });
            }
            executor.executeAffinity(0, () -> {
                spillSlots.add(NativeCarrierExecutor.currentCarrierSlot());
                spillRan.countDown();
            });

            assertTrue(spillRan.await(5, TimeUnit.SECONDS),
                    "soft affinity must yield to throughput when the preferred lane backs up");
            assertEquals(Set.of(1), spillSlots,
                    "the idle carrier should execute globally spilled affinity work");
            assertTrue(executor.getAffinityGlobalSpillCount() >= 1,
                    "backlog escape must be observable as a global spill");
            releasePreferred.countDown();
            assertTrue(preferredFinished.await(5, TimeUnit.SECONDS));
        }
    }


    @Test
    void globalSpillQueueGetsBoundedServiceUnderContinuouslyHotAffinityLanes() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(2, 2, 128, "ores-native-affinity-fair-")) {
            CountDownLatch blockersStarted = new CountDownLatch(2);
            CountDownLatch releaseBlockers = new CountDownLatch(1);
            CountDownLatch blockersFinished = new CountDownLatch(2);

            for (int key = 0; key < 2; key++) {
                executor.executeAffinity(key, () -> {
                    blockersStarted.countDown();
                    try {
                        assertTrue(releaseBlockers.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        fail(interrupted);
                    } finally {
                        blockersFinished.countDown();
                    }
                });
            }
            assertTrue(blockersStarted.await(5, TimeUnit.SECONDS));

            AtomicInteger localCompleted = new AtomicInteger();
            CountDownLatch localDone = new CountDownLatch(48);
            for (int i = 0; i < 24; i++) {
                executor.executeAffinity(0, () -> {
                    localCompleted.incrementAndGet();
                    localDone.countDown();
                });
                executor.executeAffinity(1, () -> {
                    localCompleted.incrementAndGet();
                    localDone.countDown();
                });
            }

            AtomicInteger localCompletedWhenGlobalRan = new AtomicInteger(Integer.MAX_VALUE);
            CountDownLatch globalRan = new CountDownLatch(1);
            executor.execute(() -> {
                localCompletedWhenGlobalRan.set(localCompleted.get());
                globalRan.countDown();
            });

            releaseBlockers.countDown();
            assertTrue(globalRan.await(5, TimeUnit.SECONDS));
            assertTrue(localCompletedWhenGlobalRan.get() <= 16,
                    "global spill work must be probed after a bounded local-affinity burst; observed "
                            + localCompletedWhenGlobalRan.get() + " local completions first");
            assertTrue(localDone.await(5, TimeUnit.SECONDS));
            assertTrue(blockersFinished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shrinkingCarrierPoolRehomesDisabledAffinityLaneWork() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(2, 2, 64, "ores-native-affinity-shrink-")) {
            CountDownLatch slotOneStarted = new CountDownLatch(1);
            CountDownLatch releaseSlotOne = new CountDownLatch(1);
            CountDownLatch slotOneFinished = new CountDownLatch(1);
            CountDownLatch migratedDone = new CountDownLatch(3);
            Set<Integer> executionSlots = ConcurrentHashMap.newKeySet();

            executor.executeAffinity(1, () -> {
                assertEquals(1, NativeCarrierExecutor.currentCarrierSlot());
                slotOneStarted.countDown();
                try {
                    assertTrue(releaseSlotOne.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail(interrupted);
                } finally {
                    slotOneFinished.countDown();
                }
            });
            assertTrue(slotOneStarted.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < 3; i++) {
                executor.executeAffinity(1, () -> {
                    executionSlots.add(NativeCarrierExecutor.currentCarrierSlot());
                    migratedDone.countDown();
                });
            }

            executor.setCorePoolSize(1);
            releaseSlotOne.countDown();

            assertTrue(slotOneFinished.await(5, TimeUnit.SECONDS));
            assertTrue(migratedDone.await(5, TimeUnit.SECONDS),
                    "tasks from a disabled affinity lane must be rehomed to an enabled carrier");
            assertEquals(Set.of(0), executionSlots);
            assertEquals(0, executor.getQueueSize());
        }
    }

    @Test
    void shutdownRaceCannotLeaveTasksStrandedInAffinityLanes() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        NativeCarrierExecutor executor =
                new NativeCarrierExecutor(2, 2, 4096, "ores-native-affinity-shutdown-");
        CountDownLatch blockersStarted = new CountDownLatch(2);
        CountDownLatch releaseBlockers = new CountDownLatch(1);
        AtomicBoolean stopSubmitting = new AtomicBoolean();

        try {
            for (int key = 0; key < 2; key++) {
                executor.executeAffinity(key, () -> {
                    blockersStarted.countDown();
                    while (releaseBlockers.getCount() != 0) {
                        try {
                            releaseBlockers.await(10, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            Thread.interrupted();
                        }
                    }
                });
            }
            assertTrue(blockersStarted.await(5, TimeUnit.SECONDS));

            Thread[] submitters = new Thread[4];
            for (int i = 0; i < submitters.length; i++) {
                final int key = i & 1;
                submitters[i] = Thread.ofPlatform().start(() -> {
                    while (!stopSubmitting.get()) {
                        try {
                            executor.executeAffinity(key, () -> { });
                        } catch (java.util.concurrent.RejectedExecutionException closed) {
                            break;
                        }
                    }
                });
            }

            Thread.sleep(20L);
            executor.shutdownNow();
            stopSubmitting.set(true);
            releaseBlockers.countDown();

            for (Thread submitter : submitters) submitter.join(5_000L);
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, executor.getQueueSize(),
                    "shutdown must not permit a post-drain enqueue to strand work forever");
            assertTrue(executor.isQueueEmpty());
        } finally {
            stopSubmitting.set(true);
            releaseBlockers.countDown();
            if (!executor.isShutdown()) executor.shutdownNow();
        }
    }


    @Test
    void separateDedicatedExecutorsCanUseDistinctAffinityOrdinals() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);

        try (NativeCarrierExecutor first =
                     new NativeCarrierExecutor(1, 1, 8, "ores-native-affinity-base-a-", 0L);
             NativeCarrierExecutor second =
                     new NativeCarrierExecutor(1, 1, 8, "ores-native-affinity-base-b-", 1L)) {
            CountDownLatch done = new CountDownLatch(2);
            AtomicInteger firstTarget = new AtomicInteger(-1);
            AtomicInteger secondTarget = new AtomicInteger(-1);
            AtomicInteger firstCpu = new AtomicInteger(-1);
            AtomicInteger secondCpu = new AtomicInteger(-1);

            first.execute(() -> {
                firstTarget.set(NativeCarrierExecutor.currentCarrierAffinityTarget());
                firstCpu.set(NativeCarrierExecutor.currentCarrierCpu());
                done.countDown();
            });
            second.execute(() -> {
                secondTarget.set(NativeCarrierExecutor.currentCarrierAffinityTarget());
                secondCpu.set(NativeCarrierExecutor.currentCarrierCpu());
                done.countDown();
            });

            assertTrue(done.await(5, TimeUnit.SECONDS));
            if (os.contains("linux")) {
                assertNotEquals(firstTarget.get(), secondTarget.get(),
                        "independent one-carrier executors must not collapse onto one CPU");
                assertEquals(firstTarget.get(), firstCpu.get());
                assertEquals(secondTarget.get(), secondCpu.get());
            } else {
                assertNotEquals(firstTarget.get(), secondTarget.get(),
                        "independent one-carrier executors should receive distinct Mach affinity tags");
            }
        }
    }


    @Test
    void removingLastAffinityTaskClearsLaneStealAge() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        try (NativeCarrierExecutor executor =
                     new NativeCarrierExecutor(2, 2, 64, "ores-native-affinity-remove-")) {
            CountDownLatch blockersStarted = new CountDownLatch(2);
            CountDownLatch releaseBlockers = new CountDownLatch(1);

            for (int key = 0; key < 2; key++) {
                executor.executeAffinity(key, () -> {
                    blockersStarted.countDown();
                    try {
                        assertTrue(releaseBlockers.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        fail(interrupted);
                    }
                });
            }
            assertTrue(blockersStarted.await(5, TimeUnit.SECONDS));

            Runnable queued = () -> { };
            executor.executeAffinity(0, queued);

            var ageField = NativeCarrierExecutor.class
                    .getDeclaredField("affinityLaneOldestEnqueueNanos");
            ageField.setAccessible(true);
            AtomicLongArray ages = (AtomicLongArray) ageField.get(executor);

            try {
                assertTrue(ages.get(0) > 0L,
                        "queued affinity work must publish a steal-age timestamp");
                assertTrue(executor.remove(queued));
                assertEquals(0L, ages.get(0),
                        "removing the final affinity task must clear stale steal age");
                assertEquals(0, executor.getQueueSize());
            } finally {
                releaseBlockers.countDown();
            }
        }
    }

}
