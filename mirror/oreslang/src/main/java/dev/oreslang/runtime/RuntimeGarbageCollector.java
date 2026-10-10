package dev.oreslang.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Secondary runtime reclamation for host/interop resources that are not governed
 * solely by Oreslang ownership. Guest memory safety remains ownership/borrow
 * based; this collector cannot make an invalid ownership program valid.
 */
public final class RuntimeGarbageCollector implements AutoCloseable {
    private static final Duration DEFAULT_PERIOD = Duration.ofSeconds(30);
    private static final Duration DEFAULT_MIN_PROCESS_GC_INTERVAL = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_TRACKED = 100_000;
    private static final int DEFAULT_MAX_TRACKED_PER_ACTOR = 4_096;
    private static final int DEFAULT_MAX_ACTOR_SWEEP_ENTRIES = 256;
    private static final int DEFAULT_MAX_ACTOR_EXIT_SWEEP_ENTRIES = 32;
    private static final int DEFAULT_MAX_PERIODIC_SWEEP_ENTRIES = 1_024;
    private static final int DEFAULT_MAX_PROCESS_SWEEP_ENTRIES = 4_096;
    private static final Object PROCESS_DOMAIN = new Object();
    private static final ScheduledThreadPoolExecutor SWEEP_TIMER = createSweepTimer();

    private static ScheduledThreadPoolExecutor createSweepTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "ores-gc-timer");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    public record CollectionReport(
            String scope,
            long collection,
            int trackedBefore,
            int inspected,
            int cleaned,
            int trackedAfter,
            int cleanupFailures,
            boolean jvmGcRequested) {
        public Map<String,Object> asMap() {
            return Map.of(
                    "scope", scope,
                    "collection", collection,
                    "tracked_before", trackedBefore,
                    "inspected", inspected,
                    "cleaned", cleaned,
                    "tracked_after", trackedAfter,
                    "cleanup_failures", cleanupFailures,
                    "jvm_gc_requested", jvmGcRequested);
        }
    }

    private static final class TrackedCleanup extends WeakReference<Object> {
        private final Object domain;
        private Runnable cleanup;
        private final AtomicBoolean cleaning = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final AtomicBoolean retired = new AtomicBoolean();
        private volatile int domainSlot = -1;

        private TrackedCleanup(
                Object owner,
                Object domain,
                Runnable cleanup,
                ReferenceQueue<Object> queue) {
            super(Objects.requireNonNull(owner), queue);
            this.domain = Objects.requireNonNull(domain);
            this.cleanup = Objects.requireNonNull(cleanup);
        }

        private void retire() {
            retired.set(true);
        }

        private boolean eligible(Set<Object> retiredDomains) {
            return retired.get() || get() == null || retiredDomains.contains(domain);
        }

        private boolean tryClean() {
            if (cleaned.get() || !cleaning.compareAndSet(false, true)) return false;
            try {
                if (cleaned.get()) return false;
                cleanup.run();
                cleanup = null; // Do not pin captured native/host resources through a closed handle.
                cleaned.set(true);
                return true;
            } finally {
                cleaning.set(false);
            }
        }
    }

    /**
     * Stable O(1) per-domain registration/removal plus a rotating bounded scan.
     *
     * A raw ConcurrentHashMap iterator restarts from an arbitrary bucket on each
     * actor.gc() call and can repeatedly inspect the same live prefix. Slot
     * rotation guarantees that repeated small quanta eventually visit every
     * actor-local cleanup entry without copying the whole domain.
     */
    private static final class DomainBucket {
        private final ArrayList<TrackedCleanup> slots = new ArrayList<>();
        private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
        private int liveEntries;
        private int cursor;

        synchronized int size() {
            return liveEntries;
        }

        synchronized void add(TrackedCleanup entry) {
            int slot;
            if (freeSlots.isEmpty()) {
                slot = slots.size();
                slots.add(entry);
            } else {
                slot = freeSlots.removeFirst();
                slots.set(slot, entry);
            }
            entry.domainSlot = slot;
            liveEntries++;
        }

        synchronized void remove(TrackedCleanup entry) {
            int slot = entry.domainSlot;
            if (slot < 0 || slot >= slots.size() || slots.get(slot) != entry) return;
            slots.set(slot, null);
            entry.domainSlot = -1;
            liveEntries--;
            if (liveEntries == 0) {
                slots.clear();
                freeSlots.clear();
                cursor = 0;
                return;
            }
            freeSlots.addLast(slot);
            if (cursor >= slots.size()) cursor = 0;
        }

        synchronized List<TrackedCleanup> nextBatch(int maxEntries) {
            if (maxEntries <= 0 || liveEntries == 0) return List.of();
            int target = Math.min(maxEntries, liveEntries);
            ArrayList<TrackedCleanup> batch = new ArrayList<>(target);
            int slotCount = slots.size();
            int checked = 0;
            // Bound the number of visited *slots*, not merely live entries:
            // heavy churn can leave tombstones between live registrations.
            while (batch.size() < target && checked < slotCount && checked < maxEntries) {
                if (cursor >= slotCount) cursor = 0;
                TrackedCleanup entry = slots.get(cursor++);
                checked++;
                if (entry != null) batch.add(entry);
            }
            return batch;
        }
    }

    private record SweepCounts(int inspected, int cleaned, int failures) { }

    public final class CleanupHandle implements AutoCloseable {
        private final TrackedCleanup entry;
        private CleanupHandle(TrackedCleanup entry) { this.entry = entry; }

        @Override
        public void close() {
            if (entry.cleaned.get()) {
                removeTracked(entry);
                return;
            }
            // Explicit close/drop is a logical retirement boundary. If host
            // cleanup fails transiently, later bounded sweeps retry it even
            // while the former owner remains strongly reachable.
            entry.retire();
            try {
                boolean cleanedNow = entry.tryClean();
                if (cleanedNow) removeTracked(entry);
                else if (!entry.cleaned.get()) {
                    // A concurrent sweeper owns the entry. If it subsequently
                    // fails, do not lose the explicit-drop retry notification.
                    queueRetry(entry);
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (RuntimeException | Error cleanupFailure) {
                queueRetry(entry);
                throw cleanupFailure;
            }
        }
    }

    private final Set<TrackedCleanup> tracked = ConcurrentHashMap.newKeySet();
    private final Set<TrackedCleanup> retryableFailures = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<TrackedCleanup> retryQueue = new ConcurrentLinkedQueue<>();
    private final Map<Object, DomainBucket> trackedByDomain = new ConcurrentHashMap<>();
    private final Set<Object> retiredDomains = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Object> retiredDomainQueue = new ConcurrentLinkedQueue<>();
    private final ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
    private final AtomicLong collections = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastJvmGcNanos = new AtomicLong(Long.MIN_VALUE);
    private final Object lifecycleLock = new Object();
    private final Runnable jvmGcRequest;
    private final Duration minProcessGcInterval;
    private final int maxTracked;
    private final int maxTrackedPerActor;
    private final int maxActorSweepEntries;
    private final ScheduledFuture<?> periodicSweep;

    public RuntimeGarbageCollector() {
        this(
                System::gc,
                DEFAULT_PERIOD,
                DEFAULT_MIN_PROCESS_GC_INTERVAL,
                DEFAULT_MAX_TRACKED,
                DEFAULT_MAX_TRACKED_PER_ACTOR,
                DEFAULT_MAX_ACTOR_SWEEP_ENTRIES);
    }

    RuntimeGarbageCollector(Runnable jvmGcRequest, Duration period) {
        this(
                jvmGcRequest,
                period,
                DEFAULT_MIN_PROCESS_GC_INTERVAL,
                DEFAULT_MAX_TRACKED,
                DEFAULT_MAX_TRACKED_PER_ACTOR,
                DEFAULT_MAX_ACTOR_SWEEP_ENTRIES);
    }

    RuntimeGarbageCollector(
            Runnable jvmGcRequest,
            Duration period,
            Duration minProcessGcInterval,
            int maxTracked) {
        this(
                jvmGcRequest,
                period,
                minProcessGcInterval,
                maxTracked,
                Math.min(maxTracked, DEFAULT_MAX_TRACKED_PER_ACTOR),
                DEFAULT_MAX_ACTOR_SWEEP_ENTRIES);
    }

    RuntimeGarbageCollector(
            Runnable jvmGcRequest,
            Duration period,
            Duration minProcessGcInterval,
            int maxTracked,
            int maxTrackedPerActor,
            int maxActorSweepEntries) {
        this.jvmGcRequest = Objects.requireNonNull(jvmGcRequest);
        this.minProcessGcInterval = requirePositive(minProcessGcInterval, "minimum process GC interval");
        requirePositive(period, "GC sweep period");
        if (maxTracked <= 0) throw new IllegalArgumentException("maxTracked must be positive");
        if (maxTrackedPerActor <= 0) throw new IllegalArgumentException("maxTrackedPerActor must be positive");
        if (maxTrackedPerActor > maxTracked) throw new IllegalArgumentException("maxTrackedPerActor cannot exceed maxTracked");
        if (maxActorSweepEntries <= 0) throw new IllegalArgumentException("maxActorSweepEntries must be positive");
        this.maxTracked = maxTracked;
        this.maxTrackedPerActor = maxTrackedPerActor;
        this.maxActorSweepEntries = maxActorSweepEntries;
        long periodNanos = period.toNanos();
        this.periodicSweep = SWEEP_TIMER.scheduleWithFixedDelay(
                this::safePeriodicSweep,
                periodNanos,
                periodNanos,
                TimeUnit.NANOSECONDS);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value);
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    /**
     * Registers an idempotent cleanup hook. The owner is weakly referenced;
     * callers must not capture the owner strongly from the cleanup closure.
     */
    public CleanupHandle track(Object owner, Runnable cleanup) {
        synchronized (lifecycleLock) {
            ensureOpen();
            // Validate before allocating a domain bucket: rejected registrations
            // must not retain an otherwise empty actor/VM execution domain.
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(cleanup, "cleanup");
            if (tracked.size() >= maxTracked) {
                throw new IllegalStateException("runtime cleanup registry limit exceeded: " + maxTracked);
            }
            Object actorDomain = ActorRuntime.currentActorExecutionDomain();
            Object domain = actorDomain == null ? PROCESS_DOMAIN : actorDomain;
            DomainBucket domainEntries =
                    trackedByDomain.computeIfAbsent(domain, ignored -> new DomainBucket());
            if (actorDomain != null && domainEntries.size() >= maxTrackedPerActor) {
                throw new IllegalStateException(
                        "actor cleanup registry limit exceeded: " + maxTrackedPerActor);
            }
            TrackedCleanup entry = new TrackedCleanup(owner, domain, cleanup, referenceQueue);
            tracked.add(entry);
            domainEntries.add(entry);
            return new CleanupHandle(entry);
        }
    }

    public CollectionReport collectProcess() {
        ensureOpen();
        boolean requested = requestJvmGcIfAllowed();
        return sweepProcess(requested, DEFAULT_MAX_PROCESS_SWEEP_ENTRIES);
    }

    public CollectionReport collectCurrentActor() {
        ensureOpen();
        Object actorDomain = ActorRuntime.currentActorExecutionDomain();
        if (actorDomain == null) throw new IllegalStateException("actor.gc() requires execution inside an actor");
        return sweepDomain(actorDomain, false, maxActorSweepEntries);
    }

    public CollectionReport collectPeriodic() {
        ensureOpen();
        return sweepProcess(false, DEFAULT_MAX_PERIODIC_SWEEP_ENTRIES);
    }

    /**
     * Retires one semantic actor domain. Actor-local runtime resources are
     * deterministic actor-lifetime resources: actor termination makes them
     * cleanup-eligible even if a stale host reference still exists.
     *
     * Failed cleanup hooks remain registered and are retried by later process
     * or periodic sweeps. The per-actor registry cap bounds exit work.
     */
    public CollectionReport retireActorDomain(Object actorDomain) {
        Objects.requireNonNull(actorDomain, "actorDomain");
        ensureOpen();
        // Actors without registered hooks must not leave a long-lived
        // tombstone or hold their semantic heap alive via the queue.
        DomainBucket bucket = trackedByDomain.get(actorDomain);
        if (bucket != null && bucket.size() > 0 && retiredDomains.add(actorDomain)) {
            retiredDomainQueue.offer(actorDomain);
        }
        // Actor finalization is a latency-sensitive scheduler path. Perform one
        // small local quantum now; any remainder stays queued for fair periodic
        // cleanup instead of turning actor exit into an unbounded pause.
        return sweepDomain(
                actorDomain,
                false,
                Math.min(maxActorSweepEntries, DEFAULT_MAX_ACTOR_EXIT_SWEEP_ENTRIES));
    }

    private boolean requestJvmGcIfAllowed() {
        long now = System.nanoTime();
        long previous = lastJvmGcNanos.get();
        long minGap = minProcessGcInterval.toNanos();
        if (previous != Long.MIN_VALUE && now - previous < minGap) return false;
        if (!lastJvmGcNanos.compareAndSet(previous, now)) return false;
        try {
            jvmGcRequest.run();
            return true;
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private CollectionReport sweepProcess(boolean jvmGcRequested, int maxEntriesToInspect) {
        int before = tracked.size();
        int inspected = 0;
        int cleanedCount = 0;
        int cleanupFailures = 0;

        // Retry work gets a fixed share so a permanently failing hook cannot
        // starve newly-dead weak owners or retired actor heaps.
        int retryLimit = Math.max(1, maxEntriesToInspect / 4);
        for (int polls = 0; polls < retryLimit && inspected < maxEntriesToInspect; polls++) {
            TrackedCleanup entry = retryQueue.poll();
            if (entry == null) break;
            if (!tracked.contains(entry)) {
                retryableFailures.remove(entry);
                continue;
            }
            inspected++;
            if (!entry.eligible(retiredDomains)) {
                retryableFailures.remove(entry);
                continue;
            }
            try {
                if (entry.tryClean()) {
                    cleanedCount++;
                    removeTracked(entry);
                } else if (tracked.contains(entry)) {
                    retryQueue.offer(entry);
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable cleanupFailure) {
                cleanupFailures++;
                retryQueue.offer(entry);
            }
        }

        // ReferenceQueue work is O(number of newly-dead owners), not O(registry).
        // Cap its share so a burst of host garbage cannot starve retired domains.
        int referenceLimit = Math.max(1, maxEntriesToInspect / 2);
        for (int polls = 0; polls < referenceLimit && inspected < maxEntriesToInspect; polls++) {
            TrackedCleanup entry = (TrackedCleanup) referenceQueue.poll();
            if (entry == null) break;
            if (!tracked.contains(entry)) continue;
            inspected++;
            try {
                if (entry.tryClean()) {
                    cleanedCount++;
                    removeTracked(entry);
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable cleanupFailure) {
                cleanupFailures++;
                queueRetry(entry);
            }
        }

        // Retired actor heaps are round-robin queued. A large/poisoned actor
        // cannot monopolize the shared maintenance thread or delay other actors.
        // Stale domain queue notifications count against the maintenance
        // quantum, even when no live hook remains.
        int retiredPolls = 0;
        while (inspected < maxEntriesToInspect && retiredPolls++ < maxEntriesToInspect) {
            Object retired = retiredDomainQueue.poll();
            if (retired == null) break;
            if (!retiredDomains.contains(retired)) continue;
            DomainBucket bucket = trackedByDomain.get(retired);
            if (bucket == null || bucket.size() == 0) {
                retiredDomains.remove(retired);
                continue;
            }

            SweepCounts counts = sweepBucket(retired, bucket, maxEntriesToInspect - inspected);
            inspected += counts.inspected();
            cleanedCount += counts.cleaned();
            cleanupFailures += counts.failures();

            if (retiredDomains.contains(retired)
                    && trackedByDomain.get(retired) == bucket
                    && bucket.size() > 0) {
                retiredDomainQueue.offer(retired);
            }
            if (counts.inspected() == 0) break;
        }

        return new CollectionReport(
                "process",
                collections.incrementAndGet(),
                before,
                inspected,
                cleanedCount,
                tracked.size(),
                cleanupFailures,
                jvmGcRequested);
    }

    private CollectionReport sweepDomain(
            Object requestedDomain,
            boolean jvmGcRequested,
            int maxEntriesToInspect) {
        DomainBucket bucket = trackedByDomain.get(requestedDomain);
        int before = bucket == null ? 0 : bucket.size();
        if (bucket == null || before == 0) {
            retiredDomains.remove(requestedDomain);
            return new CollectionReport(
                    "actor",
                    collections.incrementAndGet(),
                    before,
                    0,
                    0,
                    0,
                    0,
                    jvmGcRequested);
        }

        SweepCounts counts = sweepBucket(requestedDomain, bucket, maxEntriesToInspect);
        int after = trackedByDomain.get(requestedDomain) == bucket ? bucket.size() : 0;
        return new CollectionReport(
                "actor",
                collections.incrementAndGet(),
                before,
                counts.inspected(),
                counts.cleaned(),
                after,
                counts.failures(),
                jvmGcRequested);
    }

    private SweepCounts sweepBucket(Object domain, DomainBucket bucket, int maxEntriesToInspect) {
        if (maxEntriesToInspect <= 0) return new SweepCounts(0, 0, 0);
        int inspected = 0;
        int cleanedCount = 0;
        int cleanupFailures = 0;
        for (TrackedCleanup entry : bucket.nextBatch(maxEntriesToInspect)) {
            if (!tracked.contains(entry)) continue;
            inspected++;
            if (!entry.eligible(retiredDomains)) continue;
            // Retry failures are owned by retryQueue. Do not run the same
            // poisoned cleanup twice in one process/retirement quantum.
            if (retryableFailures.contains(entry)) continue;
            try {
                if (entry.tryClean()) {
                    cleanedCount++;
                    removeTracked(entry);
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable cleanupFailure) {
                cleanupFailures++;
                queueRetry(entry);
            }
        }
        return new SweepCounts(inspected, cleanedCount, cleanupFailures);
    }

    private void queueRetry(TrackedCleanup entry) {
        synchronized (lifecycleLock) {
            if (closed.get() || !tracked.contains(entry) || entry.cleaned.get()) return;
            if (retryableFailures.add(entry)) retryQueue.offer(entry);
        }
    }

    private void removeTracked(TrackedCleanup entry) {
        synchronized (lifecycleLock) {
            tracked.remove(entry);
            retryableFailures.remove(entry);
            DomainBucket domainEntries = trackedByDomain.get(entry.domain);
            if (domainEntries == null) return;
            domainEntries.remove(entry);
            if (domainEntries.size() == 0) {
                trackedByDomain.remove(entry.domain, domainEntries);
                retiredDomains.remove(entry.domain);
            }
        }
    }

    private void safePeriodicSweep() {
        // Zero host/interop fallback resources is the overwhelmingly common
        // ownership-first case. Do no registry scan or collection accounting.
        // New registrations will be observed by a subsequent timer tick.
        if (closed.get() || tracked.isEmpty()) return;
        try {
            collectPeriodic();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Periodic housekeeping is best-effort. Cleanup hook failures are
            // retained for retry and must not kill the shared timer thread.
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("garbage collector is closed");
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
        }
        periodicSweep.cancel(false);
        for (TrackedCleanup entry : Set.copyOf(tracked)) {
            entry.retire();
            try {
                if (entry.tryClean()) removeTracked(entry);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Context shutdown is best-effort for host cleanup hooks.
            }
        }
        synchronized (lifecycleLock) {
            tracked.clear();
            retryableFailures.clear();
            retryQueue.clear();
            trackedByDomain.clear();
            retiredDomains.clear();
            retiredDomainQueue.clear();
            while (referenceQueue.poll() != null) { /* drain */ }
        }
    }
}
