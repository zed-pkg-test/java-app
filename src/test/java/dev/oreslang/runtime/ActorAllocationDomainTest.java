package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
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

    private static java.util.UUID runtimeId(ActorRuntime.ActorContext<?> context) {
        return context.runtime().allocationRuntimeId();
    }
}
