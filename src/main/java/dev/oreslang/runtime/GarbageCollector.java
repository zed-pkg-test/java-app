package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates explicit Oreslang GC requests.
 *
 * Ownership/borrow analysis determines when guest values become unreachable.
 * The host JVM/Graal collector remains responsible for reclaiming heap storage.
 * Explicit GC is therefore a bounded hint plus a runtime-maintenance barrier,
 * never a guest-visible "free" primitive.
 */
public final class GarbageCollector {
    public enum Scope { ACTOR, PROCESS }

    private static final long MIN_REQUEST_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
    private static final AtomicLong GLOBAL_LAST_REQUEST_NANOS = new AtomicLong(Long.MIN_VALUE);

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong honoredRequests = new AtomicLong();
    private final AtomicLong suppressedRequests = new AtomicLong();

    public GcResult request(Scope scope) {
        if (scope == Scope.ACTOR && !ActorRuntime.inActorExecution()) {
            throw new IllegalStateException("actor.gc() is only valid while executing an actor");
        }

        long requestNumber = requests.incrementAndGet();
        long now = System.nanoTime();
        boolean honored = reserveGcWindow(now);
        if (honored) {
            /*
             * This is intentionally only a hint. HotSpot/Graal may ignore
             * explicit collection. Never expose finalization or address reuse
             * as part of Oreslang semantics.
             */
            System.gc();
            honoredRequests.incrementAndGet();
        } else {
            suppressedRequests.incrementAndGet();
        }

        return new GcResult(scope, requestNumber, honored);
    }

    private boolean reserveGcWindow(long now) {
        while (true) {
            long previous = GLOBAL_LAST_REQUEST_NANOS.get();
            if (previous != Long.MIN_VALUE && now - previous < MIN_REQUEST_INTERVAL_NANOS) return false;
            if (GLOBAL_LAST_REQUEST_NANOS.compareAndSet(previous, now)) return true;
        }
    }

    public Map<String, Object> descriptor() {
        return Map.of(
                "requests", requests.get(),
                "honored_requests", honoredRequests.get(),
                "suppressed_requests", suppressedRequests.get(),
                "min_request_interval_ms", Duration.ofNanos(MIN_REQUEST_INTERVAL_NANOS).toMillis(),
                "host_reclamation", "jvm-graal-gc",
                "ownership_reclamation", "lexical-drop");
    }

    public record GcResult(Scope scope, long requestNumber, boolean hostGcHintIssued) { }
}
