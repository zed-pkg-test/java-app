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
