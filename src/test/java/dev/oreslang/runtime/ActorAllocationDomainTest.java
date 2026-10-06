package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ActorAllocationDomainTest {
    @Test
    void rootAndExplicitSharedDomainsAreDistinctAndRuntimeStable() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.AllocationDomain root = runtime.currentAllocationDomain();
            ActorRuntime.AllocationDomain shared = runtime.runtimeSharedAllocationDomain();

            assertEquals(ActorRuntime.AllocationDomainKind.ROOT, root.kind());
            assertEquals(ActorRuntime.AllocationDomainKind.RUNTIME_SHARED, shared.kind());
            assertEquals(runtime.allocationRuntimeId(), root.runtimeId());
            assertEquals(runtime.allocationRuntimeId(), shared.runtimeId());
            assertFalse(root.actorOwned());
            assertFalse(shared.actorOwned());
            assertFalse(root.equals(shared));
            assertEquals(
                    0,
                    ActorRuntime.AllocationDomain.class.getConstructors().length,
                    "allocation domains must be runtime-minted, not publicly forgeable");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(root),
                    "allocation-domain metadata must not become an actor-message capability");

            ActorRuntime.Shared<List<Integer>> frozen = runtime.shareReadonly(List.of(1, 2, 3));
            assertEquals(shared, frozen.allocationDomain());

            ActorRuntime.SyncCell<Integer> cell = runtime.syncCell(1);
            assertEquals(shared, cell.allocationDomain());
        }
    }

    @Test
    void sharedActorUsesActorLocalDomainWithoutPrivateHeapAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(factoryContext -> {
                assertEquals(ActorRuntime.AllocationDomainKind.ACTOR_LOCAL,
                        factoryContext.allocationDomain().kind());
                assertEquals(factoryContext.self().id(),
                        factoryContext.allocationDomain().actorId());
                assertEquals(runtimeId(factoryContext),
                        factoryContext.allocationDomain().runtimeId());
                assertTrue(factoryContext.privateMemory().isEmpty());

                return (message, context) -> {
                    assertEquals(ActorRuntime.AllocationDomainKind.ACTOR_LOCAL,
                            context.allocationDomain().kind());
                    assertEquals(context.self().id(), context.allocationDomain().actorId());
                    context.self().stop();
                };
            });

            ref.send(1);
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void privateActorUsesActorPrivateDomain() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnPrivate(factoryContext -> {
                assertEquals(ActorRuntime.AllocationDomainKind.ACTOR_PRIVATE,
                        factoryContext.allocationDomain().kind());
                assertEquals(factoryContext.self().id(),
                        factoryContext.allocationDomain().actorId());
                assertTrue(factoryContext.privateMemory().isPresent());

                return (message, context) -> {
                    assertEquals(ActorRuntime.AllocationDomainKind.ACTOR_PRIVATE,
                            context.allocationDomain().kind());
                    context.self().stop();
                };
            });

            ref.send(1);
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void untrustedActorUsesSeparateUntrustedDomain() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnUntrusted(factoryContext -> {
                assertEquals(ActorRuntime.AllocationDomainKind.UNTRUSTED_ISOLATE,
                        factoryContext.allocationDomain().kind());
                assertEquals(factoryContext.self().id(),
                        factoryContext.allocationDomain().actorId());
                assertTrue(factoryContext.privateMemory().isPresent());

                return (message, context) -> {
                    assertEquals(ActorRuntime.AllocationDomainKind.UNTRUSTED_ISOLATE,
                            context.allocationDomain().kind());
                    context.self().stop();
                };
            });

            ref.send(1);
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void allocationLookupCannotLaunderAcrossActorRuntimes() throws Exception {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            var ref = left.<Integer>spawnSharedTrusted(factoryContext -> (message, context) -> {
                try {
                    right.currentAllocationDomain();
                    throw new AssertionError(
                            "foreign ActorRuntime allocation lookup did not fail closed");
                } catch (SecurityException expected) {
                    context.self().stop();
                }
            });

            ref.send(1);
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }


    @Test
    void actorAllocationDomainSurvivesStacklessAwaitResume() throws Exception {
        AtomicReference<OresFuture<Void>> gate = new AtomicReference<>();
        AtomicReference<ActorRuntime.AllocationDomain> before = new AtomicReference<>();
        AtomicReference<ActorRuntime.AllocationDomain> after = new AtomicReference<>();
        CountDownLatch suspended = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnSharedTrusted(factoryContext -> (message, context) -> {
                OresFuture<Void> wait = new OresFuture<>();
                gate.set(wait);

                context.runtime().startActorTask(new OresScheduler.Task<Void>() {
                    private boolean initial = true;

                    @Override
                    public OresScheduler.Step<Void> resume(OresScheduler.Resume resume) {
                        if (initial) {
                            initial = false;
                            before.set(context.runtime().currentAllocationDomain());
                            suspended.countDown();
                            return OresScheduler.await(wait);
                        }
                        after.set(context.runtime().currentAllocationDomain());
                        context.self().stop();
                        return OresScheduler.done(null);
                    }
                });
            });

            ref.send(1);
            assertTrue(suspended.await(5, TimeUnit.SECONDS));
            assertTrue(gate.get().completeFromRuntime(null));
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());

            assertEquals(before.get(), after.get(),
                    "await/resume must preserve the actor allocation domain");
            assertEquals(ref.id(), before.get().actorId());
            assertEquals(ActorRuntime.AllocationDomainKind.ACTOR_LOCAL,
                    after.get().kind());
        }
    }

    @Test
    void sharedMutexIsUnboundUntilRuntimeUseThenReportsRuntimeSharedDomain() throws Exception {
        OresMutex.Shared<Integer> mutex = OresMutex.shared(1);
        assertTrue(mutex.allocationDomain().isEmpty());

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnSharedTrusted(factoryContext -> (message, context) -> {
                var guard = mutex.tryLock().orElseThrow();
                try {
                    assertEquals(
                            context.runtime().runtimeSharedAllocationDomain(),
                            mutex.allocationDomain().orElseThrow());
                } finally {
                    guard.release();
                    context.self().stop();
                }
            });

            ref.send(1);
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void untrustedActorCannotConstructExplicitRuntimeSharedMutex() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnUntrusted(factoryContext -> {
                try {
                    OresMutex.shared(1);
                    throw new AssertionError("untrusted actor constructed SharedMutex");
                } catch (SecurityException expected) {
                    return (message, context) -> context.self().stop();
                }
            });

            ref.send(1);
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    private static java.util.UUID runtimeId(ActorRuntime.ActorContext<?> context) {
        return context.runtime().allocationRuntimeId();
    }
}
