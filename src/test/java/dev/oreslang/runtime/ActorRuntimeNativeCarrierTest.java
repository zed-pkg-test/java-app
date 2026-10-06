package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class ActorRuntimeNativeCarrierTest {

    @Test
    void actorRuntimeUsesNativePthreadCarriersWhenRequired() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("darwin"));

        String previous = System.getProperty("ores.runtime.carriers");
        System.setProperty("ores.runtime.carriers", "native");
        try {
            var config = new ActorRuntime.DispatcherConfig(1, 2, 1, 128);
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
                assertEquals(
                        ActorRuntime.CarrierBackend.NATIVE_PTHREAD,
                        runtime.carrierBackend());

                CountDownLatch delivered = new CountDownLatch(32);
                Set<Long> nativeThreads = ConcurrentHashMap.newKeySet();
                Set<Integer> carrierSlots = ConcurrentHashMap.newKeySet();
                Set<Integer> affinityTargets = ConcurrentHashMap.newKeySet();

                var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                    assertTrue(NativeCarrierExecutor.isNativeCarrierThread());
                    assertFalse(Thread.currentThread().isVirtual());
                    long pthread = NativeCarrierExecutor.currentNativeThreadId();
                    assertNotEquals(0L, pthread);
                    nativeThreads.add(pthread);
                    carrierSlots.add(NativeCarrierExecutor.currentCarrierSlot());
                    affinityTargets.add(NativeCarrierExecutor.currentCarrierAffinityTarget());
                    delivered.countDown();
                    if (message == 31) context.self().stop();
                });

                for (int i = 0; i < 32; i++) ref.send(i);

                assertTrue(delivered.await(5, TimeUnit.SECONDS));
                assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
                assertEquals(
                        1,
                        nativeThreads.size(),
                        "one shared actor should reuse one native carrier when its preferred lane is healthy");
                assertEquals(
                        1,
                        carrierSlots.size(),
                        "successive mailbox turns should preserve the actor's preferred carrier slot");
                assertEquals(
                        1,
                        affinityTargets.size(),
                        "successive turns should preserve the carrier CPU/cache-affinity target");

                ActorRuntime.CarrierAffinityDiagnostics diagnostics =
                        runtime.carrierAffinityDiagnostics();
                assertTrue(diagnostics.preferredHits() >= 32,
                        "ActorRuntime must aggregate preferred-lane activity for operators");
                if (os.contains("linux")) {
                    assertEquals(0, diagnostics.bindingFailures(),
                            "successful native Linux startup must report no affinity-binding failures");
                }
            }
        } finally {
            if (previous == null) System.clearProperty("ores.runtime.carriers");
            else System.setProperty("ores.runtime.carriers", previous);
        }
    }

    @Test
    void invalidCarrierBackendFailsClosed() {
        String previous = System.getProperty("ores.runtime.carriers");
        System.setProperty("ores.runtime.carriers", "mystery");
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ActorRuntime(IsolatePolicy.developer(),
                            new ActorRuntime.DispatcherConfig(1, 1, 8, 32)));
        } finally {
            if (previous == null) System.clearProperty("ores.runtime.carriers");
            else System.setProperty("ores.runtime.carriers", previous);
        }
    }
}
