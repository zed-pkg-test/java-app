package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract regressions for the distinction between reference cycles,
 * actor-transport validation, and deterministic host cleanup.
 *
 * This intentionally does not use System.gc(), sleep, or nondeterministic
 * WeakReference collection as evidence of a native cycle collector.
 */
final class CircularReferenceContractTest {

    @Test
    void actorTransportRejectsTrueCycleButAcceptsRepeatedAcyclicSubtree() {
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(cyclic));

        List<Object> subtree = new ArrayList<>();
        subtree.add("leaf");
        List<Object> diamond = List.of(subtree, subtree);
        assertDoesNotThrow(() -> ActorRuntime.freeze(diamond));
    }

    @Test
    void explicitCleanupDoesNotRequireTraversingAnOwnerCycle() {
        AtomicInteger cleaned = new AtomicInteger();
        List<Object> cyclicOwner = new ArrayList<>();
        cyclicOwner.add(cyclicOwner);

        try (RuntimeGarbageCollector gc =
                     new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            RuntimeGarbageCollector.CleanupHandle handle =
                    gc.track(cyclicOwner, cleaned::incrementAndGet);
            handle.close();
            handle.close();
            assertEquals(1, cleaned.get(), "explicit close must release once");
            assertEquals(0, gc.collectPeriodic().trackedAfter());
        }
        Reference.reachabilityFence(cyclicOwner);
    }

    @Test
    void hostPeriodicCleanupDoesNotReclaimLiveCyclicOwner() {
        AtomicInteger cleaned = new AtomicInteger();
        List<Object> cyclicOwner = new ArrayList<>();
        cyclicOwner.add(cyclicOwner);

        try (RuntimeGarbageCollector gc =
                     new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            gc.track(cyclicOwner, cleaned::incrementAndGet);
            RuntimeGarbageCollector.CollectionReport report = gc.collectPeriodic();
            assertEquals(0, report.cleaned());
            assertFalse(report.jvmGcRequested());
            assertEquals(0, cleaned.get());
            Reference.reachabilityFence(cyclicOwner);
        }
        assertEquals(1, cleaned.get(), "context teardown retires registered host resources");
    }
}
