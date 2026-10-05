package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresVMTest {

    @Test
    void processVmDeclaresExactlyFiveSchedulerDomainsWithoutExposingExecutors() {
        OresVM vm = OresVM.process();
        OresVM.SchedulerTopology topology = vm.schedulerTopology();

        assertEquals(
                List.of(
                        OresVM.SchedulerDomain.CONTROL,
                        OresVM.SchedulerDomain.ROOT_TASK,
                        OresVM.SchedulerDomain.SHARED_ACTOR,
                        OresVM.SchedulerDomain.ISOACTOR,
                        OresVM.SchedulerDomain.UNTRUSTED_ACTOR),
                topology.domains());

        assertTrue(topology.controlMinThreads() > 0);
        assertTrue(topology.rootTaskMinThreads() > 0);
        assertTrue(topology.sharedActorMinThreads() > 0);
        assertTrue(topology.isoactorMinThreads() > 0);
        assertTrue(topology.untrustedActorMinThreads() > 0);
        assertTrue(topology.controlMaxThreads() >= topology.controlMinThreads());
        assertTrue(topology.rootTaskMaxThreads() >= topology.rootTaskMinThreads());

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

        for (Class<?> apiType : List.of(OresContext.class, ActorRuntime.class)) {
            for (Method method : apiType.getMethods()) {
                assertNotEquals(
                        OresVM.class,
                        method.getReturnType(),
                        apiType.getSimpleName()
                                + " must never hand guest/interop code the VM object: "
                                + method);
            }
        }
    }

    @Test
    void generationPublicApiDoesNotExposeRawGraalHandles() {
        for (Method method : HotReloadManager.Generation.class.getMethods()) {
            Class<?> returned = method.getReturnType();
            assertNotEquals(org.graalvm.polyglot.Context.class, returned, method.toString());
            assertNotEquals(org.graalvm.polyglot.Source.class, returned, method.toString());
            assertNotEquals(org.graalvm.polyglot.Engine.class, returned, method.toString());
            assertNotEquals(OresVM.class, returned, method.toString());
            assertNotEquals(ActorRuntime.class, returned, method.toString());
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
        assertEquals(1, vm.generationBindingCount());

        vm.shutdownNow();

        assertTrue(vm.shutdown());
        assertEquals(0, vm.hotReloadManagerCount());
        assertEquals(0, vm.generationBindingCount(),
                "VM shutdown must revoke every opaque generation binding");
        assertTrue(generation.closed(),
                "dedicated VM shutdown must retire contexts/generations it owns");
        assertThrows(
                IllegalStateException.class,
                () -> hot.load("after-shutdown.ores", "pub routine main() => void { return; }"));
    }

    @Test
    void replacingUnpinnedGenerationRevokesItsOpaqueBinding() {
        OresVM vm = OresVM.dedicated(ActorRuntime.DispatcherConfig.defaults());
        try (HotReloadManager hot = vm.newHotReloadManager(
                IsolatePolicy.developer(),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.TRUSTED_JIT)) {

            HotReloadManager.Generation first = hot.loadAndStart(
                    "service.ores",
                    "pub routine main() => void { return; }");
            assertEquals(1, vm.generationBindingCount());

            HotReloadManager.Generation second = hot.loadAndStart(
                    "service.ores",
                    """
                    pub routine main() => void {
                      val version = 2;
                      return;
                    }
                    """);

            assertTrue(first.closed());
            assertTrue(second.active());
            assertEquals(1, vm.generationBindingCount(),
                    "only the active generation binding should remain");
        } finally {
            assertEquals(0, vm.generationBindingCount());
            vm.shutdownNow();
        }
    }

    @Test
    void onlyUntrustedHotLoadDomainUsesSpawnedGraalIsolate() {
        assertFalse(HotReloadManager.ExecutionDomain.TRUSTED_JIT.spawnedIsolate());
        assertFalse(HotReloadManager.ExecutionDomain.TRUSTED_ISOACTOR_JIT.spawnedIsolate());
        assertFalse(HotReloadManager.ExecutionDomain.AOT_INTERPRETED.spawnedIsolate());
        assertTrue(HotReloadManager.ExecutionDomain.UNTRUSTED_JIT.spawnedIsolate());
        assertTrue(HotReloadManager.ExecutionDomain.UNTRUSTED_JIT.untrusted());
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
    void actorGenerationLeaseIsAcquiredAtBirthAndReleasedExactlyOnce() {
        OresVM vm = OresVM.dedicated(ActorRuntime.DispatcherConfig.defaults());
        AtomicInteger activeLeases = new AtomicInteger();
        AtomicInteger releasedLeases = new AtomicInteger();

        ActorRuntime runtime = ActorRuntime.attachToVm(
                vm,
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct(),
                () -> {
                    activeLeases.incrementAndGet();
                    AtomicBoolean released = new AtomicBoolean();
                    return () -> {
                        if (released.compareAndSet(false, true)) {
                            activeLeases.decrementAndGet();
                            releasedLeases.incrementAndGet();
                        }
                    };
                },
                ActorRuntime.RuntimePlacement.MAIN_GRAAL_ISOLATE);
        try {
            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivate(
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) -> { });

            assertEquals(1, activeLeases.get());
            actor.stop();
            assertEquals(0, activeLeases.get());
            assertEquals(1, releasedLeases.get());

            runtime.close();
            assertEquals(1, releasedLeases.get(),
                    "runtime teardown must never release a finalized actor generation twice");
        } finally {
            runtime.close();
            vm.shutdownNow();
        }
    }

    @Test
    void mainGraalIsolateRefusesDirectUntrustedActorExecution() {
        OresVM vm = OresVM.dedicated(ActorRuntime.DispatcherConfig.defaults());
        ActorRuntime runtime = ActorRuntime.attachToVm(
                vm,
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct(),
                () -> () -> { },
                ActorRuntime.RuntimePlacement.MAIN_GRAAL_ISOLATE);
        try {
            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnUntrusted(
                            ignored -> (message, turn) -> { }));
            assertTrue(denied.getMessage().contains("main Graal isolate"));
        } finally {
            runtime.close();
            vm.shutdownNow();
        }
    }

    @Test
    void mailmanControlCarrierDoesNotConferSupervisorAuthority() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(1),
                1,
                64);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch attempted = new CountDownLatch(1);
            AtomicReference<Throwable> denied = new AtomicReference<>();

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
                            try {
                                runtime.executeRootTask(() -> null);
                            } catch (Throwable failure) {
                                denied.set(failure);
                            } finally {
                                attempted.countDown();
                            }
                        }
                    });

            ActorRuntime.ActorRef<String> actor = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) -> turn.emit(message));

            actor.send("mail");

            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            SecurityException failure = assertInstanceOf(
                    SecurityException.class,
                    denied.get());
            assertTrue(failure.getMessage().contains("host/supervisor"));
        }
    }

    @Test
    void rootTasksControlMailmenAndActorsUseSeparateCarrierDomains() throws Exception {
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
            assertTrue(rootThread.get().contains("root-task-dispatcher-"));

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
                            actorThread.set(mail.message());
                            mailmanThread.set(Thread.currentThread().getName());
                            mailDelivered.countDown();
                        }
                    });

            ActorRuntime.ActorRef<String> actor = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) ->
                            turn.emit(Thread.currentThread().getName()));

            actor.send("hello");

            assertTrue(mailDelivered.await(5, TimeUnit.SECONDS));
            assertTrue(actorThread.get().contains("shared-actor-dispatcher-"));
            assertTrue(mailmanThread.get().contains("control-plane-dispatcher-"));
            assertNotEquals(rootThread.get(), mailmanThread.get());
            assertNotEquals(rootThread.get(), actorThread.get());
            assertNotEquals(actorThread.get(), mailmanThread.get());
        }
    }
    @Test
    void rootAwaitUnwindsCarrierAndResumesOnlyThroughRootTaskScheduler()
            throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(2),
                1,
                64);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            OresFuture<Integer> pending = new OresFuture<>();
            CountDownLatch awaitRegistered = new CountDownLatch(1);
            AtomicReference<String> firstCarrier = new AtomicReference<>();
            AtomicReference<String> producerThread = new AtomicReference<>();
            AtomicReference<String> resumedCarrier = new AtomicReference<>();

            Thread producer = Thread.ofPlatform()
                    .name("test-future-producer")
                    .start(() -> {
                        try {
                            assertTrue(awaitRegistered.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        producerThread.set(Thread.currentThread().getName());
                        pending.completeFromRuntime(41);
                    });

            Integer result = runtime.executeRootTask(() -> {
                firstCarrier.set(Thread.currentThread().getName());
                awaitRegistered.countDown();
                ActorRuntime.suspendCurrentRootOn(
                        pending,
                        (value, failure) -> {
                            assertNull(failure);
                            resumedCarrier.set(Thread.currentThread().getName());
                            assertTrue(
                                    ActorRuntime.isOresCarrierThread(),
                                    "continuation must run on an Ores carrier");
                            assertNotEquals(
                                    producerThread.get(),
                                    resumedCarrier.get(),
                                    "Future producer must never execute guest continuation code");
                            return ((Integer) value) + 1;
                        });
                fail("root await lowering must unwind instead of returning inline");
                return -1;
            });

            producer.join();
            assertEquals(42, result);
            assertTrue(firstCarrier.get().contains("root-task-dispatcher-"));
            assertTrue(resumedCarrier.get().contains("root-task-dispatcher-"));
        }
    }

    @Test
    void alreadyCompletedRootAwaitStillResumesAfterCarrierStackUnwinds() {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(2),
                1,
                64);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicBoolean unwound = new AtomicBoolean();
            Integer result = runtime.executeRootTask(() -> {
                try {
                    ActorRuntime.suspendCurrentRootOn(
                            OresFuture.completed(7),
                            (value, failure) -> {
                                assertNull(failure);
                                assertTrue(
                                        unwound.get(),
                                        "completed Future must not inline-resume before await unwinds");
                                return ((Integer) value) * 2;
                            });
                    fail("await must not return inline");
                    return -1;
                } finally {
                    unwound.set(true);
                }
            });

            assertEquals(14, result);
        }
    }

    @Test
    void blockingFutureObservationIsRejectedOnRootCarrier() {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(2),
                1,
                64);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.executeRootTask(() -> OresFuture.completed(1).join()));
            assertTrue(failure.getMessage().contains("cannot block an OresVM carrier"));
        }
    }


    @Test
    void suspendedAsyncRootTasksOutnumberCarriersWithoutPinning() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(2),
                0,
                32);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            OresFuture<Integer> gate = new OresFuture<>();
            int taskCount = 8;
            CountDownLatch suspended = new CountDownLatch(taskCount);
            java.util.ArrayList<OresFuture<Integer>> results =
                    new java.util.ArrayList<>();

            for (int i = 0; i < taskCount; i++) {
                int index = i;
                results.add(runtime.submitAsyncRootTask(() -> {
                    suspended.countDown();
                    ActorRuntime.suspendCurrentRootOn(
                            gate,
                            (value, failure) -> {
                                assertNull(failure);
                                return ((Integer) value) + index;
                            });
                    fail("suspension must unwind the ROOT_TASK carrier");
                    return -1;
                }));
            }

            assertTrue(
                    suspended.await(2, TimeUnit.SECONDS),
                    "one carrier must be able to suspend many logical root tasks");
            assertEquals(
                    1,
                    runtime.rootTaskDispatcherStats().largestPoolSize(),
                    "suspended logical tasks must not require one carrier each");

            gate.completeFromRuntime(100);

            for (int i = 0; i < taskCount; i++) {
                assertEquals(100 + i, results.get(i).get(2, TimeUnit.SECONDS));
            }
        }
    }


    @Test
    void runtimeFutureWaiterRegistrationCanDetachBeforeSettlement() {
        OresFuture<Integer> future = new OresFuture<>();
        AtomicInteger callbacks = new AtomicInteger();

        OresFuture.RuntimeWaiterRegistration registration =
                future.whenCompleteRuntime((value, failure) -> callbacks.incrementAndGet());

        assertTrue(registration.cancel());
        assertFalse(registration.cancel());
        assertTrue(future.completeFromRuntime(1));
        assertEquals(0, callbacks.get());
    }

    @Test
    void cancellationRacingRootSuspensionCannotResurrectContinuation() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(2),
                0,
                32);

        ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config);
        try {
            OresFuture<Integer> awaited = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch unwound = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);
            AtomicBoolean allowSuspend = new AtomicBoolean();

            OresFuture<Integer> task = runtime.submitAsyncRootTask(() -> {
                entered.countDown();
                while (!allowSuspend.get()) Thread.onSpinWait();
                try {
                    ActorRuntime.suspendCurrentRootOn(
                            awaited,
                            (value, failure) -> {
                                resumed.countDown();
                                return 99;
                            });
                    fail("suspend must unwind the root carrier");
                    return -1;
                } finally {
                    unwound.countDown();
                }
            });

            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(task.cancel(true));
            allowSuspend.set(true);
            assertTrue(unwound.await(2, TimeUnit.SECONDS));

            awaited.completeFromRuntime(1);
            assertFalse(
                    resumed.await(100, TimeUnit.MILLISECONDS),
                    "a canceled logical task must never be resurrected by its old await waiter");
            assertTrue(task.isCancelled());

            // Close also proves activeRootTasks/rootSlots were not stranded by
            // the cancellation-vs-suspension publication race.
            runtime.close();
        } finally {
            runtime.close();
        }
    }

    @Test
    void stoppedSuspendedActorDetachesItsFutureWaiter() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresFuture<Integer> awaited = new OresFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> ref = runtime.spawnPrivateTrusted(
                    ignored -> (message, context) -> {
                        try {
                            context.suspendOn(
                                    awaited,
                                    (value, failure, resumedContext) -> resumed.countDown());
                        } finally {
                            suspended.countDown();
                        }
                    });

            ref.send("wait");
            assertTrue(suspended.await(2, TimeUnit.SECONDS));
            ref.stop();
            assertTrue(ref.done().get(2, TimeUnit.SECONDS));

            awaited.completeFromRuntime(1);
            assertFalse(
                    resumed.await(100, TimeUnit.MILLISECONDS),
                    "a finalized actor must not remain reachable through an old Future waiter");
        }
    }


    @Test
    void timedFutureGetDoesNotRetainHostWaiterAfterTimeout() throws Exception {
        OresFuture<Integer> future = new OresFuture<>();

        assertThrows(
                java.util.concurrent.TimeoutException.class,
                () -> future.get(1, TimeUnit.MILLISECONDS));

        Field waitersField = OresFuture.class.getDeclaredField("waiters");
        waitersField.setAccessible(true);
        java.util.Queue<?> waiters =
                (java.util.Queue<?>) waitersField.get(future);
        assertTrue(
                waiters.isEmpty(),
                "timed-out host observation must detach its Future waiter");
    }


    @Test
    void terminalFutureReleasesCancellationAuthority() throws Exception {
        AtomicInteger cancellations = new AtomicInteger();
        OresFuture<Integer> cancelled =
                new OresFuture<>(cancellations::incrementAndGet);

        assertTrue(cancelled.cancel(true));
        assertFalse(cancelled.cancel(true));
        assertEquals(1, cancellations.get());

        OresFuture<Integer> completed =
                new OresFuture<>(() -> fail("completed Future must not invoke cancel hook"));
        assertTrue(completed.completeFromRuntime(7));

        Field cancelHookField = OresFuture.class.getDeclaredField("cancelHook");
        cancelHookField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Runnable> cancelledHook =
                (AtomicReference<Runnable>) cancelHookField.get(cancelled);
        @SuppressWarnings("unchecked")
        AtomicReference<Runnable> completedHook =
                (AtomicReference<Runnable>) cancelHookField.get(completed);

        assertNull(cancelledHook.get());
        assertNull(completedHook.get());
    }


    @Test
    void suspendedRootTaskStillExpiresAtAbsoluteWallDeadline() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy shortPolicy = new IsolatePolicy(
                base.capabilities(),
                base.maxHeapBytes(),
                base.maxMailboxMessages(),
                Duration.ofMillis(80),
                false);
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(1),
                0,
                32);

        try (ActorRuntime runtime = new ActorRuntime(shortPolicy, config)) {
            OresFuture<Integer> never = new OresFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);

            OresFuture<Integer> task = runtime.submitAsyncRootTask(() -> {
                suspended.countDown();
                ActorRuntime.suspendCurrentRootOn(
                        never,
                        (value, failure) -> {
                            resumed.countDown();
                            return 1;
                        });
                fail("suspension must unwind");
                return -1;
            });

            assertTrue(suspended.await(2, TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> task.get(2, TimeUnit.SECONDS));

            never.completeFromRuntime(1);
            assertFalse(
                    resumed.await(100, TimeUnit.MILLISECONDS),
                    "wall-time expiry while suspended must detach the old continuation");
            assertTrue(runtime.rootTaskDispatcherStats().overrunTurns() >= 1);
        }
    }


    @Test
    void interruptingSynchronousRootWaitCancelsLogicalTask() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(1),
                0,
                32);

        ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config);
        try {
            CountDownLatch entered = new CountDownLatch(1);
            AtomicReference<Throwable> callerFailure = new AtomicReference<>();

            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    runtime.executeRootTask(() -> {
                        entered.countDown();
                        for (;;) runtime.schedulerSafepoint();
                    });
                } catch (Throwable failure) {
                    callerFailure.set(failure);
                }
            });

            assertTrue(entered.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2_000);

            assertFalse(caller.isAlive());
            assertInstanceOf(
                    java.util.concurrent.CancellationException.class,
                    callerFailure.get());

            // Structured cancellation must let teardown observe zero live root
            // tasks instead of abandoning work after the host waiter exits.
            runtime.close();
        } finally {
            runtime.close();
        }
    }


    @Test
    void suspendedRootTaskPinsGenerationUntilLogicalTaskTerminates() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(1),
                0,
                16);

        OresVM vm = OresVM.dedicated(config);
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        ActorRuntime runtime = vm.newActorRuntime(
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct(),
                () -> {
                    acquired.incrementAndGet();
                    return released::incrementAndGet;
                });

        try {
            OresFuture<Integer> never = new OresFuture<>();
            CountDownLatch suspensionRequested = new CountDownLatch(1);

            OresFuture<Integer> task = runtime.submitAsyncRootTask(() -> {
                suspensionRequested.countDown();
                ActorRuntime.suspendCurrentRootOn(
                        never,
                        (value, failure) -> 1);
                fail("suspension must unwind");
                return -1;
            });

            assertTrue(suspensionRequested.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (runtime.rootTaskDispatcherStats().activeThreads() != 0
                    && System.nanoTime() - deadline < 0) {
                Thread.onSpinWait();
            }

            assertEquals(1, acquired.get());
            assertEquals(0, released.get(),
                    "suspended logical task must keep its generation pinned");

            assertTrue(task.cancel(true));
            assertThrows(java.util.concurrent.CancellationException.class, task::join);

            runtime.close();
            assertEquals(1, released.get(),
                    "generation lease must release exactly once after logical-task termination");
        } finally {
            try {
                runtime.close();
            } finally {
                vm.shutdownNow();
            }
        }
    }


    @Test
    void fatalWaiterDoesNotSkipCancellationCleanupOrOtherWaiters() {
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        OresFuture<Integer> future =
                new OresFuture<>(cancellations::incrementAndGet);

        future.whenCompleteRuntime((value, failure) -> {
            throw new LinkageError("fatal waiter");
        });
        future.whenCompleteRuntime((value, failure) -> delivered.incrementAndGet());

        LinkageError fatal = assertThrows(
                LinkageError.class,
                () -> future.cancel(true));

        assertEquals("fatal waiter", fatal.getMessage());
        assertTrue(future.isCancelled());
        assertEquals(1, cancellations.get(),
                "producer cancellation cleanup must run despite fatal waiter delivery");
        assertEquals(1, delivered.get(),
                "settlement must drain remaining waiters before fatal rethrow");
    }


    @Test
    void rootTaskQuiescencePublishesOnlyAfterGenerationLeaseRelease() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(1),
                0,
                16);

        OresVM vm = OresVM.dedicated(config);
        CountDownLatch leaseCloseEntered = new CountDownLatch(1);
        CountDownLatch allowLeaseClose = new CountDownLatch(1);
        AtomicInteger leaseReleases = new AtomicInteger();
        ActorRuntime runtime = vm.newActorRuntime(
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct(),
                () -> () -> {
                    leaseCloseEntered.countDown();
                    try {
                        allowLeaseClose.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(
                                "generation lease release was interrupted",
                                interrupted);
                    }
                    leaseReleases.incrementAndGet();
                });

        try {
            OresFuture<Integer> task =
                    runtime.submitAsyncRootTask(() -> 7);
            assertEquals(7, task.get(2, TimeUnit.SECONDS));
            assertTrue(
                    leaseCloseEntered.await(2, TimeUnit.SECONDS),
                    "root finalization must reach generation lease release");

            Field activeRootTasksField =
                    ActorRuntime.class.getDeclaredField("activeRootTasks");
            activeRootTasksField.setAccessible(true);
            AtomicInteger activeRootTasks =
                    (AtomicInteger) activeRootTasksField.get(runtime);

            assertEquals(
                    1,
                    activeRootTasks.get(),
                    "runtime must remain non-quiescent while the generation lease is still closing");

            allowLeaseClose.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (activeRootTasks.get() != 0
                    && System.nanoTime() - deadline < 0) {
                Thread.onSpinWait();
            }

            assertEquals(0, activeRootTasks.get());
            assertEquals(1, leaseReleases.get());
        } finally {
            allowLeaseClose.countDown();
            try {
                runtime.close();
            } finally {
                vm.shutdownNow();
            }
        }
    }


    @Test
    void successfulCloseRetryStillShutsDownOwnedVm() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(1),
                0,
                16);

        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                config);
        CountDownLatch entered = new CountDownLatch(1);

        runtime.submitAsyncRootTask(() -> {
            entered.countDown();
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (System.nanoTime() < until) {
                Thread.onSpinWait(); // deliberately ignore interrupt/cancellation
            }
            return 1;
        });

        assertTrue(entered.await(2, TimeUnit.SECONDS));

        assertThrows(
                IllegalStateException.class,
                runtime::close,
                "first close should fail while the uncooperative task is still running");

        Thread.sleep(350);
        runtime.close();

        assertThrows(
                IllegalStateException.class,
                runtime::rootTaskDispatcherStats,
                "successful close retry must still shut down the dedicated VM");
    }


}
