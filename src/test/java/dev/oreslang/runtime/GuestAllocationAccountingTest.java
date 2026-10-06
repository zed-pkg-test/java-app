package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class GuestAllocationAccountingTest {

    @Test
    void rootGuestAllocationsRemainOutsideActorLocalAccounting() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.AllocationDomain domain =
                    runtime.accountGuestHeapAllocation(4096);

            assertEquals(ActorRuntime.AllocationDomainKind.ROOT, domain.kind());
            assertTrue(runtime.currentLocalMemory().isEmpty());
            assertEquals(0L, runtime.actorLocalMemoryBytes());
            assertEquals(0L, runtime.privateMemoryBytes());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void zeroByteCopyElidedAccountingDoesNotConsumeActorHeap() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicLong observed = new AtomicLong(-1);

            var ref = runtime.<String>spawnSharedTrusted(factory -> (message, context) -> {
                long before = context.localMemory().usedBytes();
                ActorRuntime.AllocationDomain domain =
                        context.runtime().accountGuestHeapAllocation(0);
                long after = context.localMemory().usedBytes();

                assertEquals(ActorRuntime.AllocationDomainKind.ACTOR_LOCAL, domain.kind());
                assertEquals(before, after);
                observed.set(after);
                checked.countDown();
                context.self().stop();
            });

            ref.send("copy-elided");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertEquals(0L, observed.get());
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void sharedActorGuestAllocationUsesActorLocalNotRuntimeSharedDomain() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<ActorRuntime.AllocationDomain> observedDomain =
                    new AtomicReference<>();

            var ref = runtime.<String>spawnSharedTrusted(factory -> (message, context) -> {
                long sharedBeforeLocalAllocation = runtime.sharedMemoryBytes();
                observedDomain.set(context.runtime().accountGuestHeapAllocation(256));
                assertEquals(256L, context.localMemory().usedBytes());
                assertEquals(256L, runtime.sharedActorLocalMemoryBytes());
                assertEquals(0L, runtime.privateMemoryBytes());
                assertEquals(
                        sharedBeforeLocalAllocation,
                        runtime.sharedMemoryBytes(),
                        "guest-local allocation must not add runtime-shared bytes");
                checked.countDown();
                context.self().stop();
            });

            ref.send("allocate");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertEquals(ActorRuntime.AllocationDomainKind.ACTOR_LOCAL,
                    observedDomain.get().kind());
            assertEquals(ref.id(), observedDomain.get().actorId());
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void privateAndUntrustedGuestAllocationsStayConfined() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            String privateKind = runtime.invoke(
                    ActorRuntime.ActorKind.PRIVATE,
                    "allocate",
                    (message, context) -> {
                        long before = context.localMemory().usedBytes();
                        ActorRuntime.AllocationDomain domain =
                                context.runtime().accountGuestHeapAllocation(128);
                        assertEquals(
                                before + 128L,
                                context.localMemory().usedBytes(),
                                "guest allocation must charge exactly its requested bytes on top of existing actor-local state");
                        assertEquals(
                                ActorRuntime.AllocationDomainKind.ACTOR_PRIVATE,
                                domain.kind());
                        return domain.kind().name();
                    });

            assertEquals(
                    ActorRuntime.AllocationDomainKind.ACTOR_PRIVATE.name(),
                    privateKind);
            assertEquals(0L, runtime.privateMemoryBytes(),
                    "one-shot private actor retirement must reclaim its local arena");

            String untrustedKind = runtime.invoke(
                    ActorRuntime.ActorKind.UNTRUSTED,
                    "allocate",
                    (message, context) -> {
                        long before = context.localMemory().usedBytes();
                        ActorRuntime.AllocationDomain domain =
                                context.runtime().accountGuestHeapAllocation(128);
                        assertEquals(
                                before + 128L,
                                context.localMemory().usedBytes(),
                                "guest allocation must charge exactly its requested bytes on top of existing untrusted-local state");
                        assertEquals(
                                ActorRuntime.AllocationDomainKind.UNTRUSTED_ISOLATE,
                                domain.kind());
                        return domain.kind().name();
                    });

            assertEquals(
                    ActorRuntime.AllocationDomainKind.UNTRUSTED_ISOLATE.name(),
                    untrustedKind);
            assertEquals(0L, runtime.privateMemoryBytes(),
                    "one-shot untrusted actor retirement must reclaim its local arena");
        }
    }

    @Test
    void foreignRuntimeAndMailmanCannotAcquireLocalAllocatorAuthority() throws Exception {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);

            var ref = owner.<String>spawnSharedTrusted(factory -> (message, context) -> {
                assertThrows(SecurityException.class, foreign::currentLocalMemory);
                assertThrows(SecurityException.class,
                        () -> foreign.accountGuestHeapAllocation(1));
                checked.countDown();
                context.self().stop();
            });

            ref.send("check");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
