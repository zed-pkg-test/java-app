package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            CountDownLatch done = new CountDownLatch(24);
            Set<Integer> slots = ConcurrentHashMap.newKeySet();
            Set<Integer> affinityTargets = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < 24; i++) {
                executor.executeAffinity(0, () -> {
                    slots.add(NativeCarrierExecutor.currentCarrierSlot());
                    affinityTargets.add(NativeCarrierExecutor.currentCarrierAffinityTarget());
                    done.countDown();
                });
            }

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(Set.of(0), slots,
                    "a healthy preferred affinity lane must keep one actor key on one carrier");
            assertEquals(1, affinityTargets.size(),
                    "one carrier lane must expose one stable CPU/cache-affinity target");
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
            CountDownLatch spillRan = new CountDownLatch(1);
            Set<Integer> spillSlots = ConcurrentHashMap.newKeySet();

            executor.executeAffinity(0, () -> {
                preferredStarted.countDown();
                try {
                    assertTrue(releasePreferred.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail(interrupted);
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
            releasePreferred.countDown();
        }
    }

}
