package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process/runtime boundary for Oreslang execution.
 *
 * <p>The VM owns physical scheduler infrastructure and hot-code generation
 * lifecycles. {@link OresContext} remains the Truffle/Graal integration object,
 * while logical {@link ActorRuntime} instances attach to this VM.
 *
 * <p>This class is deliberately package-private. Guest Oreslang code must never
 * receive an OresVM reference; runtime access crosses language primitives and
 * opaque capabilities instead.
 */
final class OresVM {
    enum SchedulerDomain {
        CONTROL,
        SHARED_ACTOR,
        ISOACTOR,
        UNTRUSTED_ACTOR
    }

    /**
     * Immutable scheduler declaration. Thread counts are carrier bounds, never
     * actor counts: many actors are multiplexed over each actor domain.
     */
    record SchedulerTopology(
            List<SchedulerDomain> domains,
            int controlMinThreads,
            int controlMaxThreads,
            int sharedActorMinThreads,
            int sharedActorMaxThreads,
            int isoactorMinThreads,
            int isoactorMaxThreads,
            int untrustedActorMinThreads,
            int untrustedActorMaxThreads) {
        SchedulerTopology {
            domains = List.copyOf(domains);
            if (domains.size() != 4
                    || !domains.containsAll(List.of(SchedulerDomain.values()))) {
                throw new IllegalArgumentException(
                        "Oreslang VM topology must declare exactly four scheduler domains");
            }
        }
    }

    private static final String VM_BINDING_ARG = "--ores-vm-binding=";
    private static final java.util.concurrent.ConcurrentMap<String, OresVM> VM_BINDINGS =
            new ConcurrentHashMap<>();

    private static final OresVM PROCESS = new OresVM(
            ActorRuntime.DispatcherConfig.defaults(),
            "ores-process-",
            true);

    private final UUID vmId = UUID.randomUUID();
    private final String contextBindingToken = UUID.randomUUID().toString();
    private final ActorRuntime.DispatcherGroup dispatchers;
    private final boolean processVm;
    private final Set<HotReloadManager> hotReloadManagers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdown = new AtomicBoolean();

    private OresVM(
            ActorRuntime.DispatcherConfig config,
            String threadPrefix,
            boolean processVm) {
        this.dispatchers = new ActorRuntime.DispatcherGroup(
                Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(threadPrefix, "threadPrefix"));
        this.processVm = processVm;
        VM_BINDINGS.put(contextBindingToken, this);
    }

    /** The one physical Oreslang VM for the current OS process. */
    static OresVM process() {
        return PROCESS;
    }

    /** Dedicated VM used by host tests/tools that explicitly construct ActorRuntime. */
    static OresVM dedicated(ActorRuntime.DispatcherConfig config) {
        return new OresVM(config, "ores-", false);
    }

    /**
     * Resolve the VM selected by the privileged host for this language context.
     * The opaque binding token is injected by OresVM when it builds a hot-load
     * generation and is never exposed through the Oreslang guest API.
     */
    static OresVM contextOwner(String[] applicationArguments) {
        String token = null;
        for (String argument : applicationArguments) {
            if (argument.startsWith(VM_BINDING_ARG)) {
                token = argument.substring(VM_BINDING_ARG.length());
                break;
            }
        }
        if (token == null) return PROCESS;

        OresVM vm = VM_BINDINGS.get(token);
        if (vm == null || vm.shutdown()) {
            throw new SecurityException("invalid or retired Oreslang VM context binding");
        }
        return vm;
    }

    String[] bindApplicationArguments(String[] baseArguments) {
        Objects.requireNonNull(baseArguments, "baseArguments");
        ensureRunning();
        String[] bound = java.util.Arrays.copyOf(baseArguments, baseArguments.length + 1);
        bound[baseArguments.length] = VM_BINDING_ARG + contextBindingToken;
        return bound;
    }

    UUID id() {
        return vmId;
    }

    boolean processVm() {
        return processVm;
    }

    boolean shutdown() {
        return shutdown.get();
    }

    SchedulerTopology schedulerTopology() {
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
        ensureRunning();
        return ActorRuntime.attachToVm(this, policyCeiling, turnExecutor);
    }

    /**
     * VM-owned hot loader. Trusted generations may share the main Graal engine;
     * isolated/untrusted generations are created through their sandboxed
     * Context policy. Loader authority remains control-plane only.
     */
    HotReloadManager newHotReloadManager(
            IsolatePolicy supervisorPolicy,
            IsolatePolicy guestPolicy,
            ExecutionProfile executionProfile,
            HotReloadManager.ExecutionDomain executionDomain) {
        ensureRunning();
        return new HotReloadManager(
                this,
                supervisorPolicy,
                guestPolicy,
                executionProfile,
                executionDomain);
    }

    void registerHotReloadManager(HotReloadManager manager) {
        Objects.requireNonNull(manager, "manager");
        ensureRunning();
        hotReloadManagers.add(manager);
    }

    void unregisterHotReloadManager(HotReloadManager manager) {
        if (manager != null) hotReloadManagers.remove(manager);
    }

    int hotReloadManagerCount() {
        return hotReloadManagers.size();
    }

    ActorRuntime.DispatcherGroup dispatcherGroup() {
        ensureRunning();
        return dispatchers;
    }

    private void ensureRunning() {
        if (shutdown.get()) {
            throw new IllegalStateException("Oreslang VM is shut down");
        }
    }

    /**
     * Dedicated VM teardown. A process VM is host-owned and intentionally
     * outlives individual language contexts/hot-reload generations.
     */
    void shutdownNow() {
        if (processVm) {
            throw new IllegalStateException(
                    "the process Oreslang VM is host-owned and cannot be shut down by a context");
        }
        if (!shutdown.compareAndSet(false, true)) return;

        RuntimeException firstFailure = null;
        for (HotReloadManager manager : List.copyOf(hotReloadManagers)) {
            try {
                manager.close();
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        dispatchers.shutdownNow();
        VM_BINDINGS.remove(contextBindingToken, this);

        if (firstFailure != null) throw firstFailure;
    }
}
