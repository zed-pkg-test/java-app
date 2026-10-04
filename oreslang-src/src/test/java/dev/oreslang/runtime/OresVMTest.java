package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresVMTest {

    @Test
    void processVmDeclaresExactlyFourSchedulerDomainsWithoutExposingExecutors() {
        OresVM vm = OresVM.process();
        OresVM.SchedulerTopology topology = vm.schedulerTopology();

        assertEquals(
                List.of(
                        OresVM.SchedulerDomain.CONTROL,
                        OresVM.SchedulerDomain.SHARED_ACTOR,
                        OresVM.SchedulerDomain.ISOACTOR,
                        OresVM.SchedulerDomain.UNTRUSTED_ACTOR),
                topology.domains());

        assertTrue(topology.controlMinThreads() > 0);
        assertTrue(topology.sharedActorMinThreads() > 0);
        assertTrue(topology.isoactorMinThreads() > 0);
        assertTrue(topology.untrustedActorMinThreads() > 0);
        assertTrue(topology.controlMaxThreads() >= topology.controlMinThreads());

        for (Method method : OresVM.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            assertFalse(
                    Executor.class.isAssignableFrom(method.getReturnType()),
                    "public OresVM API must not expose raw executor authority: " + method);
        }
    }

    @Test
    void vmKernelTypeIsNotPublicAndContextDoesNotExposeIt() {
        assertFalse(Modifier.isPublic(OresVM.class.getModifiers()),
                "OresVM is a runtime kernel type, not a user/interop API");

        for (Method method : OresContext.class.getMethods()) {
            assertNotEquals(
                    OresVM.class,
                    method.getReturnType(),
                    "OresContext must never hand guest/interop code the VM object: " + method);
        }
    }

    @Test
    void vmOwnsHotReloadManagersAndDedicatedShutdownClosesGenerations() {
        ActorRuntime.DispatcherConfig config = ActorRuntime.DispatcherConfig.defaults();
        OresVM vm = OresVM.dedicated(config);

        HotReloadManager hot = vm.newHotReloadManager(
                IsolatePolicy.developer(),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.TRUSTED_JIT);

        assertEquals(1, vm.hotReloadManagerCount());

        HotReloadManager.Generation generation = hot.loadAndStart(
                "vm-owned.ores",
                """
                pub routine main() => void {
                  return;
                }
                """);
        assertEquals(HotReloadManager.GenerationState.ACTIVE, generation.state());

        vm.shutdownNow();

        assertTrue(vm.shutdown());
        assertEquals(0, vm.hotReloadManagerCount());
        assertTrue(generation.closed(),
                "dedicated VM shutdown must retire contexts/generations it owns");
        assertThrows(
                IllegalStateException.class,
                () -> hot.load("after-shutdown.ores", "pub routine main() => void { return; }"));
    }

    @Test
    void untrustedHotLoadDomainIsFailClosedAndMarkedForSpawnedIsolate() {
        OresVM vm = OresVM.dedicated(ActorRuntime.DispatcherConfig.defaults());
        try {
            HotReloadManager hot = vm.newHotReloadManager(
                    IsolatePolicy.developer(),
                    IsolatePolicy.developer().withCapabilities(
                            IsolatePolicy.Capability.FFI,
                            IsolatePolicy.Capability.NATIVE,
                            IsolatePolicy.Capability.REFLECTION,
                            IsolatePolicy.Capability.THREAD_CREATE,
                            IsolatePolicy.Capability.POLYGLOT),
                    ExecutionProfile.serverJit(),
                    HotReloadManager.ExecutionDomain.UNTRUSTED_JIT);
            try {
                assertTrue(hot.executionDomain().spawnedIsolate());
                assertTrue(hot.executionDomain().untrusted());
                assertTrue(hot.guestPolicy().adversarial());
                assertTrue(hot.guestPolicy().capabilities().isEmpty(),
                        "untrusted generations receive no ambient host authority");
            } finally {
                hot.close();
            }
            assertEquals(0, vm.hotReloadManagerCount());
        } finally {
            vm.shutdownNow();
        }
    }

    @Test
    void controlPlaneRunsRootAndMailmanOffSharedActorCarriers() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(1),
                1,
                64);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<String> rootThread = new AtomicReference<>();
            runtime.executeRootTask(() -> {
                rootThread.set(Thread.currentThread().getName());
                return null;
            });
            assertNotNull(rootThread.get());
            assertTrue(rootThread.get().contains("control-plane-dispatcher-"));

            CountDownLatch actorRan = new CountDownLatch(1);
            CountDownLatch mailDelivered = new CountDownLatch(1);
            AtomicReference<String> actorThread = new AtomicReference<>();
            AtomicReference<String> mailmanThread = new AtomicReference<>();

            ActorGroupConfig.GroupPolicy policy = new ActorGroupConfig.GroupPolicy(
                    ActorRuntime.ActorKind.SHARED,
                    0,
                    4,
                    32,
                    32,
                    ActorGroupConfig.RestartStrategy.ONE_FOR_ONE,
                    3,
                    Duration.ofSeconds(5),
                    ActorGroupConfig.RestartPolicy.PERMANENT,
                    null);

            ActorGroupRef<String> group = runtime.defineActorGroup(
                    ActorRuntime.ActorKind.SHARED,
                    policy,
                    new ActorMailman<>() {
                        @Override
                        public void receiveMail(
                                ActorMail<String> mail,
                                ActorGroupContext<String> ignored) {
                            mailmanThread.set(Thread.currentThread().getName());
                            mailDelivered.countDown();
                        }
                    });

            ActorRuntime.ActorRef<String> actor = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) -> {
                        actorThread.set(Thread.currentThread().getName());
                        turn.emit(message);
                        actorRan.countDown();
                    });

            actor.send("hello");

            assertTrue(actorRan.await(5, TimeUnit.SECONDS));
            assertTrue(mailDelivered.await(5, TimeUnit.SECONDS));
            assertTrue(actorThread.get().contains("shared-actor-dispatcher-"));
            assertTrue(mailmanThread.get().contains("control-plane-dispatcher-"));
            assertNotEquals(actorThread.get(), mailmanThread.get());
        }
    }
}
