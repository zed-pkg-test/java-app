package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorLocalAllocationHeapTest {

    @Test
    void sharedActorLocalSliceMatchesSemanticAllocationDomainAndStaysOutOfSharedAccounting()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var explicitShared = runtime.syncCell("shared-value");
            long explicitSharedBytes = runtime.sharedMemoryBytes();
            assertTrue(explicitSharedBytes > 0L);

            CountDownLatch observed = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorMemorySlice> leaked = new AtomicReference<>();
            AtomicReference<ActorRuntime.MemoryReservation> reservation = new AtomicReference<>();

            var ref = runtime.<String>spawnSharedTrusted(context -> (message, turn) -> {
                assertTrue(context.localMemory().isPresent());
                assertTrue(context.privateMemory().isEmpty());

                ActorRuntime.ActorMemorySlice local = context.localMemory().orElseThrow();
                assertEquals(ActorRuntime.ActorKind.SHARED, local.kind());
                assertEquals(context.self().id(), local.owner());
                assertEquals(context.allocationDomain(), local.allocationDomain());

                leaked.set(local);
                reservation.set(local.reserveHeap(4096));
                observed.countDown();
            });

            ref.send("reserve");
            assertTrue(observed.await(2, TimeUnit.SECONDS));

            assertTrue(runtime.sharedActorLocalMemoryBytes() >= 4096);
            assertEquals(0L, runtime.privateMemoryBytes());
            assertEquals(0L, runtime.untrustedLocalMemoryBytes());
            assertEquals(explicitSharedBytes, runtime.sharedMemoryBytes(),
                    "ordinary shared-actor local storage must not become RUNTIME_SHARED");

            assertThrows(IllegalStateException.class, () -> leaked.get().reserveHeap(1),
                    "a leaked actor-local slice must not be usable outside its owner turn");

            ref.stop();
            assertTrue(leaked.get().closed());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
            assertEquals(explicitSharedBytes, runtime.sharedMemoryBytes(),
                    "actor exit must not reclaim unrelated explicit shared state");
            assertEquals("shared-value", explicitShared.snapshot());

            reservation.get().close();
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void privateActorKeepsCompatibilityViewAndPrivateAccounting() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch observed = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorMemorySlice> local = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivateTrusted(context -> (message, turn) -> {
                ActorRuntime.ActorMemorySlice localMemory = context.localMemory().orElseThrow();
                assertSame(localMemory, context.privateMemory().orElseThrow());
                assertEquals(ActorRuntime.ActorKind.PRIVATE, localMemory.kind());
                assertEquals(context.allocationDomain(), localMemory.allocationDomain());
                localMemory.reserveHeap(2048);
                local.set(localMemory);
                observed.countDown();
            });

            ref.send("reserve");
            assertTrue(observed.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.privateMemoryBytes() >= 2048);
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
            assertEquals(0L, runtime.untrustedLocalMemoryBytes());

            ref.stop();
            assertTrue(local.get().closed());
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }

    @Test
    void untrustedActorUsesDistinctLocalAccountingAndConfinedView() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var ref = runtime.<String>spawnUntrusted(context -> {
                ActorRuntime.ActorMemorySlice local = context.localMemory().orElseThrow();
                assertSame(local, context.privateMemory().orElseThrow());
                assertEquals(ActorRuntime.ActorKind.UNTRUSTED, local.kind());
                assertEquals(ActorRuntime.AllocationDomainKind.UNTRUSTED_ISOLATE,
                        local.allocationDomain().kind());
                assertEquals(context.allocationDomain(), local.allocationDomain());

                return (message, turn) -> {
                    local.reserveHeap(1024);
                    assertTrue(turn.runtime().untrustedLocalMemoryBytes() >= 1024);
                    assertEquals(0L, turn.runtime().sharedActorLocalMemoryBytes());
                    turn.self().stop();
                };
            });

            ref.send("reserve");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty(), () -> String.valueOf(ref.failure().orElse(null)));
            assertEquals(0L, runtime.untrustedLocalMemoryBytes(),
                    "untrusted local accounting must bulk-release at actor exit");
        }
    }

    @Test
    void directConfinedBlocksRemainUnavailableToSharedActors() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnShared(context -> (message, turn) -> {
                assertThrows(IllegalStateException.class,
                        () -> context.localMemory().orElseThrow().allocatePrivateBytes(16));
                turn.self().stop();
            });
            ref.send("check");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void allLocalAndExplicitSharedCategoriesCompeteForOneRuntimeHeapCeiling() throws Exception {
        IsolatePolicy tiny = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                4096,
                IsolatePolicy.developer().maxMailboxMessages(),
                IsolatePolicy.developer().maxWallTime(),
                false);

        try (ActorRuntime runtime = new ActorRuntime(tiny)) {
            CountDownLatch reserved = new CountDownLatch(1);
            var ref = runtime.<String>spawnSharedTrusted(tiny, context -> (message, turn) -> {
                context.localMemory().orElseThrow().reserveHeap(3072);
                reserved.countDown();
            });

            ref.send("reserve");
            assertTrue(reserved.await(2, TimeUnit.SECONDS));

            IllegalStateException exceeded = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.syncCell("this shared cell must exceed the remaining aggregate budget"));
            assertTrue(exceeded.getMessage().contains("aggregate runtime limit exceeded"),
                    exceeded.getMessage());

            ref.stop();
        }
    }
}
