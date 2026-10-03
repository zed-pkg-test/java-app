package dev.oreslang.runtime;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Ownership-first runtime cleanup coordinator.
 *
 * Memory safety does not depend on this class. Ownership/borrowing is checked
 * at compile time, runtime scope retirement performs deterministic cleanup, and
 * JVM/Graal tracing GC remains the final reclamation mechanism for unreachable
 * host/interop/reflection objects.
 */
public final class GcRuntime implements AutoCloseable {
    public enum Scope { ACTOR, PROCESS }

    /** Runtime caches can opt into safe-point scavenging without exposing it to guest code. */
    public interface Scavengeable {
        void scavenge();
    }

    public record Report(
            Scope scope,
            long sequence,
            long safepoints,
            int scavenged,
            boolean hostGcRequested,
            String trigger) {
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scope", scope.name().toLowerCase());
            result.put("sequence", sequence);
            result.put("safepoints", safepoints);
            result.put("scavenged", scavenged);
            result.put("host_gc_requested", hostGcRequested);
            result.put("trigger", trigger);
            return Map.copyOf(result);
        }
    }

    private static final long DEFAULT_SAFEPOINT_INTERVAL = 4096L;
    private static final Duration DEFAULT_HOST_GC_COOLDOWN = Duration.ofSeconds(30);

    private final long safepointInterval;
    private final long minHostGcIntervalNanos;
    private final Runnable hostGc;
    private final LongSupplier nanoTime;
    private final AtomicLong safepoints = new AtomicLong();
    private final AtomicLong collections = new AtomicLong();
    private final AtomicLong lastHostGcNanos = new AtomicLong(Long.MIN_VALUE);

    public static GcRuntime defaults() {
        return new GcRuntime(DEFAULT_SAFEPOINT_INTERVAL, DEFAULT_HOST_GC_COOLDOWN, System::gc, System::nanoTime);
    }

    public static GcRuntime fromApplicationArguments(String[] args) {
        long interval = DEFAULT_SAFEPOINT_INTERVAL;
        long cooldownMs = DEFAULT_HOST_GC_COOLDOWN.toMillis();
        for (String arg : args) {
            if (arg.startsWith("--ores-gc-safepoints=")) {
                interval = Long.parseLong(arg.substring("--ores-gc-safepoints=".length()));
            } else if (arg.startsWith("--ores-gc-min-host-ms=")) {
                cooldownMs = Long.parseLong(arg.substring("--ores-gc-min-host-ms=".length()));
            }
        }
        return new GcRuntime(interval, Duration.ofMillis(cooldownMs), System::gc, System::nanoTime);
    }

    public GcRuntime(long safepointInterval, Duration minHostGcInterval, Runnable hostGc, LongSupplier nanoTime) {
        if (safepointInterval <= 0) throw new IllegalArgumentException("GC safepoint interval must be positive");
        if (minHostGcInterval.isNegative()) throw new IllegalArgumentException("GC host cooldown cannot be negative");
        this.safepointInterval = safepointInterval;
        this.minHostGcIntervalNanos = minHostGcInterval.toNanos();
        this.hostGc = java.util.Objects.requireNonNull(hostGc);
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
    }

    /**
     * Periodic runtime scavenging deliberately does not force host GC: one
     * actor/tenant must not be able to impose global collection pressure.
     */
    public Report safepoint(Scope scope, IntSupplier scavenger) {
        long n = safepoints.incrementAndGet();
        if (n % safepointInterval != 0) return null;
        int scavenged = Math.max(0, scavenger.getAsInt());
        return report(scope, scavenged, false, "periodic");
    }

    /** Manual scope-local cleanup plus a rate-limited host-GC hint. */
    public Report manual(Scope scope, int scavenged) {
        return report(scope, Math.max(0, scavenged), maybeRequestHostGc(), "manual");
    }

    private Report report(Scope scope, int scavenged, boolean hostRequested, String trigger) {
        return new Report(java.util.Objects.requireNonNull(scope), collections.incrementAndGet(),
                safepoints.get(), scavenged, hostRequested, trigger);
    }

    private boolean maybeRequestHostGc() {
        long now = nanoTime.getAsLong();
        while (true) {
            long previous = lastHostGcNanos.get();
            if (previous != Long.MIN_VALUE && now >= previous && now - previous < minHostGcIntervalNanos) {
                return false;
            }
            if (lastHostGcNanos.compareAndSet(previous, now)) {
                hostGc.run();
                return true;
            }
        }
    }

    public Map<String, Object> descriptor() {
        return Map.of(
                "safepoint_interval", safepointInterval,
                "host_gc_cooldown_ms", Duration.ofNanos(minHostGcIntervalNanos).toMillis(),
                "safepoints", safepoints.get(),
                "collections", collections.get());
    }

    public static int scavengeValues(Iterable<?> values) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        int count = 0;
        for (Object value : values) {
            if (value == null || seen.put(value, Boolean.TRUE) != null) continue;
            if (value instanceof Scavengeable scavengeable) {
                scavengeable.scavenge();
                count++;
            }
        }
        return count;
    }

    /** Deterministic drop/RAII-like cleanup for runtime-owned resources. */
    public static int closeOwnedValues(Iterable<?> values) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        RuntimeException failure = null;
        int count = 0;
        for (Object value : values) {
            if (value == null || seen.put(value, Boolean.TRUE) != null) continue;
            if (!(value instanceof AutoCloseable closeable)) continue;
            try {
                closeable.close();
                count++;
            } catch (Exception error) {
                if (failure == null) failure = new IllegalStateException("failed to close owned runtime value", error);
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
        return count;
    }

    @Override public void close() {
        // No background collector thread is owned by this coordinator.
    }
}
