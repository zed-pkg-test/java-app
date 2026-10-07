package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresMutex;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;

final class MutexRuntimeTest {

    private static final class BlockingSingleElementList extends AbstractList<Object> {
        private final Object value;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicBoolean blocked = new AtomicBoolean();

        private BlockingSingleElementList(
                Object value,
                CountDownLatch entered,
                CountDownLatch release) {
            this.value = value;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public Object get(int index) {
            if (index != 0) throw new IndexOutOfBoundsException(index);
            if (blocked.compareAndSet(false, true)) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release transport validation");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                }
            }
            return value;
        }

        @Override
        public int size() {
            return 1;
        }
    }

    @Test
    void localMutexIsNonReentrantAndExecutionDomainConfined() throws Exception {
        var mutex = OresMutex.local(new int[]{0});
        var guard = mutex.lock();
        guard.value()[0] = 7;

        assertThrows(OresMutex.RecursiveLockException.class, mutex::lock);
        assertTrue(mutex.tryLock().isEmpty(), "try_lock should report busy rather than recurse");

        AtomicReference<Throwable> otherThreadFailure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                mutex.tryLock();
            } catch (Throwable failure) {
                otherThreadFailure.set(failure);
            }
        });
        thread.join();

        assertInstanceOf(OresMutex.WrongMutexDomainException.class, otherThreadFailure.get());
        guard.release();

        var again = mutex.lock();
        assertEquals(7, again.value()[0]);
        again.release();
    }

    @Test
    void actorLocalMutexPersistsAcrossMessagesInOneSemanticDomain() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Integer> observed = new AtomicReference<>();

            var ref = runtime.<Integer>spawn(() -> {
                var mutex = OresMutex.local(new int[]{0});
                return (message, context) -> mutex.withLock(value -> {
                    value[0] += message;
                    if (value[0] == 3) {
                        observed.set(value[0]);
                        done.countDown();
                    }
                    return null;
                });
            });

            ref.send(1);
            ref.send(2);

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(3, observed.get());
        }
    }

    @Test
    void actorLocalMutexCannotBeUsedByAnotherActorEvenInSameProcess() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch created = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<OresMutex.Local<int[]>> local = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var owner = runtime.<String>spawn(() -> (message, context) -> {
                var mutex = OresMutex.local(new int[]{0});
                mutex.withLock(value -> {
                    value[0] = 41;
                    return null;
                });
                local.set(mutex);
                created.countDown();
            });

            var other = runtime.<String>spawn(() -> (message, context) -> {
                try {
                    assertTrue(created.await(2, TimeUnit.SECONDS));
                    assertThrows(OresMutex.WrongMutexDomainException.class, () -> local.get().tryLock());
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    checked.countDown();
                }
            });

            owner.send("create");
            other.send("check");

            assertTrue(checked.await(3, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }

    @Test
    void sharedMutexSerializesRealJvmThreads() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < 6; w++) {
            workers.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 500; i++) {
                    mutex.withLock(value -> {
                        value[0]++;
                        return null;
                    });
                }
            }));
        }
        for (Thread worker : workers) worker.join();
        assertEquals(3000, mutex.withLock(value -> value[0]).intValue());
    }

    @Test
    void recoveryCannotBeUsedAsAnOrdinaryLock() {
        var mutex = OresMutex.shared(new int[]{0});

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> mutex.recover(value -> {
                    value[0] = 99;
                    return null;
                }));

        assertTrue(error.getMessage().contains("not poisoned"));
        assertFalse(mutex.isPoisoned());
        assertEquals(0, mutex.withLock(value -> value[0]).intValue());
    }

    @Test
    void sharedMutexPoisonsAndRequiresExplicitRecovery() {
        var mutex = OresMutex.shared(new int[]{0});

        assertThrows(IllegalStateException.class, () -> mutex.withLock(value -> {
            value[0] = 99;
            throw new IllegalStateException("boom");
        }));

        assertTrue(mutex.isPoisoned());
        assertThrows(OresMutex.PoisonedMutexException.class, mutex::lock);

        mutex.recover(value -> {
            value[0] = 0;
            return null;
        });

        assertFalse(mutex.isPoisoned());
        assertEquals(0, mutex.withLock(value -> value[0]).intValue());
    }

    @Test
    void asyncMutexAcquisitionUsesTaggedGuardFutures() {
        var local = OresMutex.local(new int[]{1});
        var localFuture = local.lockAsync();
        assertInstanceOf(OresMutex.GuardFuture.class, localFuture);
        var localGuard = localFuture.join();
        localGuard.release();

        var shared = OresMutex.shared(new int[]{2});
        var sharedFuture = shared.lockAsync();
        assertInstanceOf(OresMutex.GuardFuture.class, sharedFuture);
        var sharedGuard = sharedFuture.join();
        sharedGuard.release();
    }

    @Test
    void sharedTryLockReportsBusyAndCancelledAsyncWaitDoesNotLeakPermit() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        assertTrue(mutex.tryLock().isEmpty());

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> waitingRef = new AtomicReference<>();
        CountDownLatch queued = new CountDownLatch(1);
        Thread requester = Thread.ofPlatform().start(() -> {
            waitingRef.set(mutex.lockAsync());
            queued.countDown();
        });
        requester.join();

        assertTrue(queued.await(1, TimeUnit.SECONDS));
        var waiting = waitingRef.get();
        assertNotNull(waiting);
        assertFalse(waiting.isDone());
        assertTrue(waiting.cancel(true));

        guard.release();

        var next = mutex.lock();
        next.release();
        assertTrue(waiting.isCancelled());
    }

    @Test
    void sharedMutexRejectsSameDomainAsyncReentryBeforeDeadlock() {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lockAsync().join();

        assertThrows(OresMutex.RecursiveLockException.class, mutex::lockAsync);

        guard.release();
        var next = mutex.lock();
        next.release();
    }

    @Test
    void releasedGuardsCannotExposeProtectedValues() {
        var local = OresMutex.local(new int[]{1});
        var localGuard = local.lock();
        assertEquals(1, localGuard.value()[0]);
        localGuard.release();
        assertThrows(IllegalStateException.class, localGuard::value);

        var shared = OresMutex.shared(new int[]{2});
        var sharedGuard = shared.lock();
        assertEquals(2, sharedGuard.value()[0]);
        sharedGuard.release();
        assertThrows(IllegalStateException.class, sharedGuard::value);
    }

    @Test
    void sharedMutexCannotCrossAnyActorMailboxBoundary() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });
            var shared = OresMutex.shared(new int[]{0});

            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> receiver.send(shared));
            assertTrue(denied.getMessage().contains("SHARED_MEMORY"));
            assertDoesNotThrow(() -> shared.withLock(value -> { value[0] = 3; return null; }));
            assertEquals(3, shared.withLock(value -> value[0]).intValue());
        }
    }

    @Test
    void actorCannotReceiveSharedMutexForRecovery() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });
            var shared = OresMutex.shared(new int[]{0});

            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> receiver.send(shared));
            assertTrue(denied.getMessage().contains("SHARED_MEMORY"));
            var guard = shared.lock();
            guard.fail();
            assertTrue(shared.isPoisoned());
            shared.recover(value -> { value[0] = 0; return null; });
            assertFalse(shared.isPoisoned());
        }
    }

    @Test
    void actorCannotReceiveSharedMutexForAsyncAcquisition() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });
            var shared = OresMutex.shared(new int[]{0});

            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> receiver.send(shared));
            assertTrue(denied.getMessage().contains("SHARED_MEMORY"));
            var acquired = shared.lockAsync().join();
            acquired.value()[0] = 1;
            acquired.release();
            assertEquals(1, shared.withLock(value -> value[0]).intValue());
        }
    }

    @Test
    void actorPublicationCannotBindLegacySharedMutex() {
        var shared = OresMutex.shared(new int[]{0});
        try (ActorRuntime first = new ActorRuntime();
             ActorRuntime second = new ActorRuntime()) {
            var a = first.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });
            var b = second.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });

            assertThrows(SecurityException.class, () -> a.send(shared));
            assertThrows(SecurityException.class, () -> b.send(shared));

            var guard = shared.lock();
            guard.value()[0] = 4;
            guard.release();
            assertEquals(4, shared.withLock(value -> value[0]).intValue());
        }
    }

    @Test
    void strictActorPolicyRejectsSharedMemoryHandles() {
        IsolatePolicy strict = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(strict)) {
            var ref = runtime.<OresMutex.Shared<int[]>>spawnPrivate(
                    strict, factoryContext -> (message, context) -> { });
            var shared = OresMutex.shared(new int[]{0});
            assertThrows(SecurityException.class, () -> ref.send(shared));
        }
    }

    @Test
    void strictPrivateActorRejectsCapturedSharedMutexBeforeStartup() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy strict = IsolatePolicy.strictFaas();
            var shared = OresMutex.shared(new int[]{0});

            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnPrivate(strict, factoryContext -> {
                        shared.tryLock();
                        return (message, context) -> { };
                    }));

            assertTrue(failure.getMessage().contains("stateless")
                    || failure.getMessage().contains("captured host state"));
        }
    }

    @Test
    void strictActorCannotCreateSharedMutexDirectly() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy strict = IsolatePolicy.strictFaas();

            var ref = runtime.<String>spawnPrivate(
                    strict,
                    factoryContext -> (message, context) -> OresMutex.shared(new int[]{0}));

            ref.send("check");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ref.failure().isEmpty() && System.nanoTime() < deadline) Thread.sleep(2);

            assertTrue(ref.failure().isPresent());
            assertInstanceOf(SecurityException.class, ref.failure().orElseThrow());
        }
    }


@Test
    void guardFutureCannotBeForgedOrTimeoutCompletedByCallers() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> waitingRef = new AtomicReference<>();
        Thread requester = Thread.ofPlatform().start(() -> waitingRef.set(mutex.lockAsync()));
        requester.join();

        var waiting = waitingRef.get();
        assertInstanceOf(OresMutex.GuardFuture.class, waiting);
        assertFalse(waiting.isDone());

        assertThrows(UnsupportedOperationException.class, () -> waiting.complete(null));
        assertThrows(UnsupportedOperationException.class,
                () -> waiting.completeExceptionally(new RuntimeException("forged")));
        assertThrows(UnsupportedOperationException.class,
                () -> waiting.completeAsync(() -> null));
        assertThrows(UnsupportedOperationException.class,
                () -> waiting.orTimeout(1, TimeUnit.MILLISECONDS));
        assertThrows(UnsupportedOperationException.class,
                () -> waiting.completeOnTimeout(null, 1, TimeUnit.MILLISECONDS));
        assertThrows(UnsupportedOperationException.class, () -> waiting.obtrudeValue(null));
        assertThrows(UnsupportedOperationException.class,
                () -> waiting.obtrudeException(new RuntimeException("forged")));

        assertTrue(waiting.cancel(true));
        guard.release();

        var next = mutex.lock();
        next.release();
    }

@Test
    void cancelledQueuedWaiterIsSkippedByDirectHandoff() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> firstRef = new AtomicReference<>();
        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> secondRef = new AtomicReference<>();

        Thread firstRequester = Thread.ofPlatform().start(() -> firstRef.set(mutex.lockAsync()));
        Thread secondRequester = Thread.ofPlatform().start(() -> secondRef.set(mutex.lockAsync()));
        firstRequester.join();
        secondRequester.join();

        var first = firstRef.get();
        var second = secondRef.get();
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertTrue(first.cancel(true));

        guard.release();

        var secondGuard = second.get(2, TimeUnit.SECONDS);
        secondGuard.value()[0] = 9;
        secondGuard.release();

        assertTrue(first.isCancelled());
        assertEquals(9, mutex.withLock(value -> value[0]).intValue());
    }

@Test
    void poisoningFailsQueuedAsyncWaitersAndRecoveryRestoresHandoff() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> firstRef = new AtomicReference<>();
        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> secondRef = new AtomicReference<>();
        Thread firstRequester = Thread.ofPlatform().start(() -> firstRef.set(mutex.lockAsync()));
        Thread secondRequester = Thread.ofPlatform().start(() -> secondRef.set(mutex.lockAsync()));
        firstRequester.join();
        secondRequester.join();

        guard.value()[0] = 17;
        guard.fail();

        for (var waiting : List.of(firstRef.get(), secondRef.get())) {
            var failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> waiting.get(2, TimeUnit.SECONDS));
            assertInstanceOf(OresMutex.PoisonedMutexException.class, failure.getCause());
        }

        assertTrue(mutex.isPoisoned());
        mutex.recover(value -> {
            value[0] = 0;
            return null;
        });

        var next = mutex.lockAsync().get(2, TimeUnit.SECONDS);
        next.value()[0] = 3;
        next.release();
        assertEquals(3, mutex.withLock(value -> value[0]).intValue());
    }

    @Test
    void asyncFastPathDoesNotBargeAfterReleaseToQueuedHostWaiter() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var initial = mutex.lock();

        CountDownLatch blockingAttempted = new CountDownLatch(1);
        CountDownLatch blockingAcquired = new CountDownLatch(1);
        CountDownLatch releaseBlocking = new CountDownLatch(1);
        AtomicReference<Throwable> blockingFailure = new AtomicReference<>();

        Thread blocking = Thread.ofPlatform().start(() -> {
            blockingAttempted.countDown();
            try {
                var guard = mutex.lock();
                blockingAcquired.countDown();
                releaseBlocking.await();
                guard.release();
            } catch (Throwable failure) {
                blockingFailure.set(failure);
            }
        });

        assertTrue(blockingAttempted.await(1, TimeUnit.SECONDS));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (blocking.getState() != Thread.State.WAITING
                && blocking.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(blocking.getState() == Thread.State.WAITING
                        || blocking.getState() == Thread.State.TIMED_WAITING,
                "blocking host waiter must be queued before release");

        // No async waiter exists yet, so this release deliberately exposes the
        // fair Semaphore permit to the already-queued blocking host waiter.
        initial.release();

        // Arrive only after the release. An untimed tryAcquire() may barge here
        // before the awakened host thread runs; timed-zero fair acquisition may not.
        var async = mutex.lockAsync();

        assertTrue(blockingAcquired.await(2, TimeUnit.SECONDS),
                "queued blocking waiter must retain its fair position after release");
        assertFalse(async.isDone(),
                "later async fast-path request must not steal the released permit");

        releaseBlocking.countDown();
        var asyncGuard = async.get(2, TimeUnit.SECONDS);
        asyncGuard.release();

        blocking.join();
        assertNull(blockingFailure.get());
    }

    @Test
    void blockingAndAsyncWaitersBothMakeProgress() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var initial = mutex.lock();
        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch blockingDone = new CountDownLatch(1);
        AtomicReference<Throwable> blockingFailure = new AtomicReference<>();

        Thread blocking = Thread.ofPlatform().start(() -> {
            blockingStarted.countDown();
            try {
                var guard = mutex.lock();
                guard.value()[0] += 1;
                guard.release();
            } catch (Throwable failure) {
                blockingFailure.set(failure);
            } finally {
                blockingDone.countDown();
            }
        });
        assertTrue(blockingStarted.await(1, TimeUnit.SECONDS));

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> asyncRef = new AtomicReference<>();
        Thread asyncRequester = Thread.ofPlatform().start(() -> asyncRef.set(mutex.lockAsync()));
        asyncRequester.join();
        var async = asyncRef.get();
        assertFalse(async.isDone());

        initial.release();

        var asyncGuard = async.get(2, TimeUnit.SECONDS);
        asyncGuard.value()[0] += 10;
        asyncGuard.release();

        assertTrue(blockingDone.await(2, TimeUnit.SECONDS));
        blocking.join();
        assertNull(blockingFailure.get());
        assertEquals(11, mutex.withLock(value -> value[0]).intValue());
    }

@Test
    void sharedTimedLockSaturatesHugePositiveDurations() {
        var mutex = OresMutex.shared(new int[]{1});

        var guard = mutex.lockFor(Duration.ofSeconds(Long.MAX_VALUE)).orElseThrow();
        assertEquals(1, guard.value()[0]);
        guard.release();
    }

@Test
    void failedRecoveryKeepsMutexPoisoned() {
        var mutex = OresMutex.shared(new int[]{0});

        assertThrows(IllegalStateException.class, () -> mutex.withLock(value -> {
            value[0] = 9;
            throw new IllegalStateException("poison");
        }));

        assertThrows(IllegalStateException.class, () -> mutex.recover(value -> {
            value[0] = 3;
            throw new IllegalStateException("repair failed");
        }));

        assertTrue(mutex.isPoisoned());
        assertThrows(OresMutex.PoisonedMutexException.class, mutex::tryLock);

        mutex.recover(value -> {
            value[0] = 0;
            return null;
        });
        assertFalse(mutex.isPoisoned());
    }

@Test
    void normalReleaseFollowedByFailDoesNotPoisonMutex() {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        guard.release();
        guard.fail();

        assertFalse(mutex.isPoisoned());
        var next = mutex.lock();
        next.release();
    }


    @Test
    void rejectedActorAdmissionLeavesLegacySharedMutexUsableByHost() {
        var shared = OresMutex.shared(new int[]{0});
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });
            assertThrows(SecurityException.class, () -> receiver.send(shared));

            var guard = shared.tryLock().orElseThrow();
            guard.value()[0] = 42;
            guard.release();
            assertEquals(42, shared.withLock(value -> value[0]).intValue());
        }
    }

    @Test
    void actorCannotReceiveLegacySharedMutexForBlockingApis() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });
            var shared = OresMutex.shared(new int[]{0});

            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> receiver.send(shared));
            assertTrue(denied.getMessage().contains("SHARED_MEMORY"));
            
        }
    }

    @Test
    void actorCannotReceiveSharedMutexOrItsGuardDomain() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });
            var shared = OresMutex.shared(new int[]{0});

            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> receiver.send(shared));
            assertTrue(denied.getMessage().contains("SHARED_MEMORY"));
            
        }
    }

    @Test
    void shutdownAndActorSendCannotAdmitLegacySharedMutex() {
        ActorRuntime runtime = new ActorRuntime();
        var shared = OresMutex.shared(new int[]{0});
        var target = runtime.<OresMutex.Shared<int[]>>spawnShared(
                () -> (message, context) -> { });

        assertThrows(SecurityException.class, () -> target.send(shared));
        runtime.close();
        assertThrows(IllegalStateException.class, () -> target.send(shared));
    }

    @Test
    void mixedRuntimeActorPublicationRejectsAllLegacySharedMutexHandles() {
        var first = OresMutex.shared(new int[]{1});
        var second = OresMutex.shared(new int[]{2});
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<java.util.List<OresMutex.Shared<int[]>>>spawnShared(
                    () -> (message, context) -> { });
            assertThrows(
                    SecurityException.class,
                    () -> receiver.send(java.util.List.of(first, second)));
            assertEquals(1, first.withLock(value -> value[0]).intValue());
            assertEquals(2, second.withLock(value -> value[0]).intValue());
        }
    }



    @Test
    void actorTransportRejectsLegacySharedMutexBeforePayloadInspection() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<Object>>spawnShared(
                    () -> (message, context) -> { });

            var nestedLocal = OresMutex.shared((Object) OresMutex.local(new int[]{1}));
            var opaque = OresMutex.shared(new Object());

            assertThrows(SecurityException.class, () -> receiver.send(nestedLocal));
            assertThrows(SecurityException.class, () -> receiver.send(opaque));
        }
    }

    @Test
    void actorTransportRejectsEvenRuntimeInspectableLegacySharedState() {
        record SafeBox(int value) implements OresMutex.SharedState {
            @Override public Iterable<?> sharedStateChildren() { return java.util.List.of(value); }
        }
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<SafeBox>>spawnShared(
                    () -> (message, context) -> { });
            var shared = OresMutex.shared(new SafeBox(7));
            assertThrows(SecurityException.class, () -> receiver.send(shared));
            assertEquals(7, shared.withLock(SafeBox::value).intValue());
        }
    }


    @Test
    void actorTransportRejectsNestedLegacySharedMutexBeforeLockOrderingMatters() {
        record Box(OresMutex.Shared<int[]> inner) implements OresMutex.SharedState {
            @Override public Iterable<?> sharedStateChildren() { return java.util.List.of(inner); }
        }
        var outer = OresMutex.shared(new Box(OresMutex.shared(new int[]{3})));
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<Box>>spawnShared(
                    () -> (message, context) -> { });
            assertThrows(SecurityException.class, () -> receiver.send(outer));
        }
    }

    @Test
    void actorRejectionDoesNotBlockOnLockedLegacySharedMutex() {
        var shared = OresMutex.shared(new int[]{1});
        var guard = shared.lock();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });
            long started = System.nanoTime();
            assertThrows(SecurityException.class, () -> receiver.send(shared));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
        } finally {
            guard.release();
        }
    }

    @Test
    void actorRejectionPrecedesCyclicLegacySharedStateInspection() {
        final class CyclicBox implements OresMutex.SharedState {
            private Object child;
            @Override public Iterable<?> sharedStateChildren() { return java.util.List.of(child); }
        }
        CyclicBox box = new CyclicBox();
        box.child = box;
        var shared = OresMutex.shared(box);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<CyclicBox>>spawnShared(
                    () -> (message, context) -> { });
            assertThrows(SecurityException.class, () -> receiver.send(shared));
        }
    }


    @Test
    void asyncTimedLockTimesOutAndReleasesItsDomainReservation() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> waitingRef = new AtomicReference<>();
        Thread requester = Thread.ofPlatform().start(
                () -> waitingRef.set(mutex.lockAsyncFor(Duration.ofMillis(25))));
        requester.join();

        var waiting = waitingRef.get();
        var failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> waiting.get(2, TimeUnit.SECONDS));
        assertInstanceOf(OresMutex.LockTimeoutException.class, failure.getCause());

        guard.release();
        var next = mutex.lock();
        next.release();
    }

    @Test
    void asyncTimedLockCanWinBeforeDeadline() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> waitingRef = new AtomicReference<>();
        Thread requester = Thread.ofPlatform().start(
                () -> waitingRef.set(mutex.lockAsyncFor(Duration.ofSeconds(2))));
        requester.join();
        var waiting = waitingRef.get();
        assertFalse(waiting.isDone());

        guard.release();

        var acquired = waiting.get(2, TimeUnit.SECONDS);
        acquired.value()[0] = 5;
        acquired.release();
        assertEquals(5, mutex.withLock(value -> value[0]).intValue());
    }

    @Test
    void asyncTimedLockZeroAndNegativeTimeoutsAreWellDefined() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> zeroRef = new AtomicReference<>();
        Thread requester = Thread.ofPlatform().start(
                () -> zeroRef.set(mutex.lockAsyncFor(Duration.ZERO)));
        requester.join();

        var zero = zeroRef.get();
        var failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> zero.get(2, TimeUnit.SECONDS));
        assertInstanceOf(OresMutex.LockTimeoutException.class, failure.getCause());

        assertThrows(IllegalArgumentException.class,
                () -> mutex.lockAsyncFor(Duration.ofMillis(-1)));

        guard.release();
        var next = mutex.lock();
        next.release();
    }


    @Test
    void crossMutexTwoDomainCycleIsRejectedInsteadOfHanging() throws Exception {
        var left = OresMutex.shared(new int[]{0});
        var right = OresMutex.shared(new int[]{0});
        CountDownLatch bothHeld = new CountDownLatch(2);
        CountDownLatch startCrossAcquire = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread first = Thread.ofPlatform().start(() -> {
            OresMutex.Guard<int[]> leftGuard = left.lock();
            try {
                bothHeld.countDown();
                assertTrue(bothHeld.await(2, TimeUnit.SECONDS));
                assertTrue(startCrossAcquire.await(2, TimeUnit.SECONDS));
                try {
                    var rightGuard = right.lock();
                    rightGuard.release();
                } catch (Throwable failure) {
                    firstFailure.set(failure);
                }
            } catch (Throwable failure) {
                firstFailure.compareAndSet(null, failure);
            } finally {
                leftGuard.release();
            }
        });

        Thread second = Thread.ofPlatform().start(() -> {
            OresMutex.Guard<int[]> rightGuard = right.lock();
            try {
                bothHeld.countDown();
                assertTrue(bothHeld.await(2, TimeUnit.SECONDS));
                assertTrue(startCrossAcquire.await(2, TimeUnit.SECONDS));
                try {
                    var leftGuard = left.lock();
                    leftGuard.release();
                } catch (Throwable failure) {
                    secondFailure.set(failure);
                }
            } catch (Throwable failure) {
                secondFailure.compareAndSet(null, failure);
            } finally {
                rightGuard.release();
            }
        });

        assertTrue(bothHeld.await(2, TimeUnit.SECONDS));
        startCrossAcquire.countDown();
        first.join(3_000);
        second.join(3_000);

        assertFalse(first.isAlive(), "first domain must not remain deadlocked");
        assertFalse(second.isAlive(), "second domain must not remain deadlocked");
        assertTrue(
                firstFailure.get() instanceof OresMutex.DeadlockDetectedException
                        || secondFailure.get() instanceof OresMutex.DeadlockDetectedException,
                "at least one edge that closes the cycle must be rejected");
    }

    @Test
    void timedOutAsyncWaitRemovesDeadlockGraphEdge() throws Exception {
        var held = OresMutex.shared(new int[]{0});
        var free = OresMutex.shared(new int[]{0});
        var owner = held.lock();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                var timed = held.lockAsyncFor(Duration.ofMillis(25));
                var timedFailure = assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> timed.get(2, TimeUnit.SECONDS));
                assertInstanceOf(OresMutex.LockTimeoutException.class, timedFailure.getCause());

                var next = free.lock();
                next.release();
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });

        waiter.join(3_000);
        assertFalse(waiter.isAlive());
        assertNull(failure.get());
        owner.release();
    }

    @Test
    void zeroDurationLockForRemainsTryOnly() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var owner = mutex.lock();
        AtomicReference<Optional<OresMutex.Guard<int[]>>> result = new AtomicReference<>();
        Thread contender = Thread.ofPlatform().start(
                () -> result.set(mutex.lockFor(Duration.ZERO)));
        contender.join();
        assertTrue(result.get().isEmpty());
        owner.release();
    }


}
