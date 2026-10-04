package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Process/runtime boundary for Oreslang execution.
 *
 * <p>The VM owns the physical scheduler infrastructure. OresContext remains the
 * Truffle integration object, while ActorRuntime owns one logical actor registry
 * attached to this VM. Production contexts in one OS process attach to the
 * process VM so hot-reload generations do not multiply physical carrier pools.
 *
 * <p>Guest code never receives executor/thread-pool handles. The only public
 * surface is immutable identity/topology metadata; scheduler submission remains
 * package-private runtime machinery.
 */
public final class OresVM {
    public enum SchedulerDomain {
        CONTROL,
        SHARED_ACTOR,
        ISOACTOR,
        UNTRUSTED_ACTOR
    }

    /**
     * Immutable scheduler declaration. Thread counts are carrier bounds, never
     * actor counts: many actors are multiplexed over each actor domain.
     */
    public record SchedulerTopology(
            List<SchedulerDomain> domains,
            int controlMinThreads,
            int controlMaxThreads,
            int sharedActorMinThreads,
            int sharedActorMaxThreads,
            int isoactorMinThreads,
            int isoactorMaxThreads,
            int untrustedActorMinThreads,
            int untrustedActorMaxThreads) {
        public SchedulerTopology {
            domains = List.copyOf(domains);
            if (domains.size() != 4
                    || !domains.containsAll(List.of(SchedulerDomain.values()))) {
                throw new IllegalArgumentException(
                        "Oreslang VM topology must declare exactly four scheduler domains");
            }
        }
    }

    private static final OresVM PROCESS = new OresVM(
            ActorRuntime.DispatcherConfig.defaults(),
            "ores-process-",
            true);

    private final UUID vmId = UUID.randomUUID();
    private final ActorRuntime.DispatcherGroup dispatchers;
    private final boolean processVm;

    private OresVM(
            ActorRuntime.DispatcherConfig config,
            String threadPrefix,
            boolean processVm) {
        this.dispatchers = new ActorRuntime.DispatcherGroup(
                Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(threadPrefix, "threadPrefix"));
        this.processVm = processVm;
    }

    /** The one physical Oreslang VM scheduler set for the current OS process. */
    public static OresVM process() {
        return PROCESS;
    }

    /** Dedicated VM used by host tests/tools that explicitly construct ActorRuntime. */
    static OresVM dedicated(ActorRuntime.DispatcherConfig config) {
        return new OresVM(config, "ores-vm-", false);
    }

    public UUID id() {
        return vmId;
    }

    public boolean processVm() {
        return processVm;
    }

    public SchedulerTopology schedulerTopology() {
        ActorRuntime.DispatcherConfig config = dispatchers.config();
        return new SchedulerTopology(
                List.of(
                        SchedulerDomain.CONTROL,
                        SchedulerDomain.SHARED_ACTOR,
                        SchedulerDomain.ISOACTOR,
                        SchedulerDomain.UNTRUSTED_ACTOR),
                config.controlParallelism(),
                config.maxControlParallelism(),
                config.sharedParallelism(),
                config.maxParallelismFor(ActorRuntime.ActorKind.SHARED),
                config.privateParallelism(),
                config.maxParallelismFor(ActorRuntime.ActorKind.PRIVATE),
                config.untrustedParallelism(),
                config.maxParallelismFor(ActorRuntime.ActorKind.UNTRUSTED));
    }

    ActorRuntime newActorRuntime(
            IsolatePolicy policyCeiling,
            ActorRuntime.TurnExecutor turnExecutor) {
        return ActorRuntime.attachToVm(this, policyCeiling, turnExecutor);
    }

    ActorRuntime.DispatcherGroup dispatcherGroup() {
        return dispatchers;
    }

    void shutdownNow() {
        if (processVm) {
            throw new IllegalStateException(
                    "the process Oreslang VM is host-owned and cannot be shut down by a context");
        }
        dispatchers.shutdownNow();
    }
}
