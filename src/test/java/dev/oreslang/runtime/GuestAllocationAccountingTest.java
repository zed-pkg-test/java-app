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
                observedDomain.set(context.runtime().accountGuestHeapAllocation(256));
                assertEquals(256L, context.localMemory().usedBytes());
                assertEquals(256L, runtime.sharedActorLocalMemoryBytes());
                assertEquals(0L, runtime.privateMemoryBytes());
                assertEquals(0L, runtime.sharedMemoryBytes());
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
    void privateAndUntrustedGuestAllocationsStayConfined() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch privateChecked = new CountDownLatch(1);
            AtomicReference<ActorRuntime.AllocationDomain> privateDomain =
                    new AtomicReference<>();

            var isolated = runtime.<String>spawnPrivate(factory -> (message, context) -> {
                privateDomain.set(context.runtime().accountGuestHeapAllocation(128));
                assertEquals(128L, context.localMemory().usedBytes());
                privateChecked.countDown();
                context.self().stop();
            });

            isolated.send("allocate");
            assertTrue(privateChecked.await(2, TimeUnit.SECONDS));
            assertEquals(ActorRuntime.AllocationDomainKind.ACTOR_PRIVATE,
                    privateDomain.get().kind());
            assertTrue(isolated.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0L, runtime.privateMemoryBytes());

            CountDownLatch untrustedChecked = new CountDownLatch(1);
            AtomicReference<ActorRuntime.AllocationDomain> untrustedDomain =
                    new AtomicReference<>();

            var untrusted = runtime.<String>spawnUntrusted(factory -> (message, context) -> {
                untrustedDomain.set(context.runtime().accountGuestHeapAllocation(128));
                assertEquals(128L, context.localMemory().usedBytes());
                untrustedChecked.countDown();
                context.self().stop();
            });

            untrusted.send("allocate");
            assertTrue(untrustedChecked.await(2, TimeUnit.SECONDS));
            assertEquals(ActorRuntime.AllocationDomainKind.UNTRUSTED_ISOLATE,
                    untrustedDomain.get().kind());
            assertTrue(untrusted.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0L, runtime.privateMemoryBytes());
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
