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
    private static final String GENERATION_BINDING_ARG = "--ores-generation-binding=";
    private static final java.util.concurrent.ConcurrentMap<String, OresVM> VM_BINDINGS =
            new ConcurrentHashMap<>();

    /**
     * Lazy process singleton. Loading OresVM must not allocate scheduler
     * threads: Native Image analysis and host tooling may load runtime classes
     * without actually starting an Oreslang VM.
     */
    private static final class ProcessHolder {
        private static final OresVM INSTANCE = new OresVM(
                ActorRuntime.DispatcherConfig.defaults(),
                "ores-process-",
                true);
    }

    private final UUID vmId = UUID.randomUUID();
    private final String contextBindingToken = UUID.randomUUID().toString();
    private final ActorRuntime.DispatcherGroup dispatchers;
    private final boolean processVm;
    private final Set<HotReloadManager> hotReloadManagers = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentMap<
            String, ActorRuntime.ActorGenerationLeaseFactory> generationBindings =
            new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

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
        return ProcessHolder.INSTANCE;
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
        String token = singleInternalArgument(applicationArguments, VM_BINDING_ARG);
        if (token == null) return process();

        OresVM vm = VM_BINDINGS.get(token);
        if (vm == null || vm.shutdown()) {
            throw new SecurityException("invalid or retired Oreslang VM context binding");
        }
        return vm;
    }

    String[] bindApplicationArguments(
            String[] baseArguments,
            String generationBindingToken) {
        Objects.requireNonNull(baseArguments, "baseArguments");
        synchronized (lifecycleLock) {
            ensureRunning();
            for (String argument : baseArguments) {
                if (argument.startsWith(VM_BINDING_ARG)
                        || argument.startsWith(GENERATION_BINDING_ARG)) {
                    throw new SecurityException(
                            "application arguments may not use reserved Oreslang VM binding prefixes");
                }
            }

            int extra = generationBindingToken == null ? 1 : 2;
            String[] bound = java.util.Arrays.copyOf(baseArguments, baseArguments.length + extra);
            bound[baseArguments.length] = VM_BINDING_ARG + contextBindingToken;
            if (generationBindingToken != null) {
                if (!generationBindings.containsKey(generationBindingToken)) {
                    throw new SecurityException("unknown Oreslang generation binding");
                }
                bound[baseArguments.length + 1] =
                        GENERATION_BINDING_ARG + generationBindingToken;
            }
            return bound;
        }
    }

    String registerGenerationBinding(
            ActorRuntime.ActorGenerationLeaseFactory leaseFactory) {
        Objects.requireNonNull(leaseFactory, "leaseFactory");
        synchronized (lifecycleLock) {
            ensureRunning();
            String token;
            do {
                token = UUID.randomUUID().toString();
            } while (generationBindings.putIfAbsent(token, leaseFactory) != null);
            return token;
        }
    }

    void unregisterGenerationBinding(String token) {
        if (token == null) return;
        synchronized (lifecycleLock) {
            generationBindings.remove(token);
        }
    }

    ActorRuntime.ActorGenerationLeaseFactory generationLeaseFactory(
            String[] applicationArguments) {
        String token = singleInternalArgument(
                applicationArguments,
                GENERATION_BINDING_ARG);
        if (token == null) return null;

        ActorRuntime.ActorGenerationLeaseFactory factory =
                generationBindings.get(token);
        if (factory == null || shutdown()) {
            throw new SecurityException(
                    "invalid or retired Oreslang generation binding");
        }
        return factory;
    }

    private static String singleInternalArgument(
            String[] applicationArguments,
            String prefix) {
        Objects.requireNonNull(applicationArguments, "applicationArguments");
        String value = null;
        for (String argument : applicationArguments) {
            Objects.requireNonNull(argument, "application argument");
            if (!argument.startsWith(prefix)) continue;
            String candidate = argument.substring(prefix.length());
            if (candidate.isBlank()) {
                throw new SecurityException("empty reserved Oreslang VM binding argument");
            }
            if (value != null) {
                throw new SecurityException("duplicate reserved Oreslang VM binding argument");
            }
            value = candidate;
        }
        return value;
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

    ActorRuntime newActorRuntime(
            IsolatePolicy policyCeiling,
            ActorRuntime.TurnExecutor turnExecutor,
            ActorRuntime.ActorGenerationLeaseFactory generationLeaseFactory) {
        return newActorRuntime(
                policyCeiling,
                turnExecutor,
                generationLeaseFactory,
                ActorRuntime.RuntimePlacement.MAIN_GRAAL_ISOLATE);
    }

    ActorRuntime newActorRuntime(
            IsolatePolicy policyCeiling,
            ActorRuntime.TurnExecutor turnExecutor,
            ActorRuntime.ActorGenerationLeaseFactory generationLeaseFactory,
            ActorRuntime.RuntimePlacement runtimePlacement) {
        ensureRunning();
        ActorRuntime.ActorGenerationLeaseFactory effectiveFactory =
                generationLeaseFactory == null
                        ? () -> () -> { }
                        : generationLeaseFactory;
        return ActorRuntime.attachToVm(
                this,
                policyCeiling,
                turnExecutor,
                effectiveFactory,
                Objects.requireNonNull(runtimePlacement, "runtimePlacement"));
    }

    /**
     * VM-owned hot loader. Trusted shared actors and trusted isoactors remain
     * in the primary Graal isolate; only untrusted generations use a spawned
     * Graal isolate. Loader authority remains control-plane only.
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
        synchronized (lifecycleLock) {
            ensureRunning();
            hotReloadManagers.add(manager);
        }
    }

    void unregisterHotReloadManager(HotReloadManager manager) {
        if (manager == null) return;
        synchronized (lifecycleLock) {
            hotReloadManagers.remove(manager);
        }
    }

    int hotReloadManagerCount() {
        synchronized (lifecycleLock) {
            return hotReloadManagers.size();
        }
    }

    int generationBindingCount() {
        synchronized (lifecycleLock) {
            return generationBindings.size();
        }
    }

    ActorRuntime.DispatcherGroup dispatcherGroup() {
        ensureRunning();
        return dispatchers;
    }

    void executeControlMaintenance(Runnable task) {
        // Teardown may race VM shutdown after shutdown=true but before the
        // physical CONTROL pool is stopped. Let already-owned cleanup drain.
        dispatchers.executeControlMaintenance(
                Objects.requireNonNull(task, "task"));
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

        List<HotReloadManager> managers;
        synchronized (lifecycleLock) {
            if (!shutdown.compareAndSet(false, true)) return;
            // Registration and shutdown share this lock, so once shutdown is
            // visible no manager or generation binding can slip in after this
            // ownership snapshot.
            managers = List.copyOf(hotReloadManagers);
        }

        RuntimeException firstFailure = null;
        for (HotReloadManager manager : managers) {
            try {
                manager.close();
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }

        synchronized (lifecycleLock) {
            generationBindings.clear();
            hotReloadManagers.clear();
            VM_BINDINGS.remove(contextBindingToken, this);
        }
        dispatchers.shutdownNow();

        if (firstFailure != null) throw firstFailure;
    }
}
