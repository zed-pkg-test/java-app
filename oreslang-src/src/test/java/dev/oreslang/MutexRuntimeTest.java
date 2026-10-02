package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresMutex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class MutexRuntimeTest {
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
    void sharedMutexCannotBeClosureCapturedAcrossActorRuntimes() throws Exception {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});
            CountDownLatch bound = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var owner = runtimeA.<OresMutex.Shared<int[]>>spawn(() -> (mutex, context) -> {
                // The send itself binds the SharedMutex to runtimeA.
                assertFalse(mutex.isPoisoned());
                bound.countDown();
            });
            owner.send(shared);
            assertTrue(bound.await(2, TimeUnit.SECONDS));

            var foreign = runtimeB.<String>spawn(() -> (message, context) -> {
                try {
                    shared.tryLock();
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    checked.countDown();
                }
            });
            foreign.send("check");

            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(OresMutex.WrongMutexDomainException.class, failure.get());
        }
    }

    @Test
    void actorCanRecoverPoisonedSharedMutexWithoutBlocking() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});
            CountDownLatch poisoned = new CountDownLatch(1);
            CountDownLatch recovered = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var poisoner = runtime.<OresMutex.Shared<int[]>>spawn(() -> (mutex, context) -> {
                try {
                    var guard = mutex.lockAsync().join();
                    guard.value()[0] = 17;
                    guard.fail();
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    poisoned.countDown();
                }
            });

            poisoner.send(shared);
            assertTrue(poisoned.await(2, TimeUnit.SECONDS));
            assertTrue(shared.isPoisoned());

            var repairer = runtime.<OresMutex.Shared<int[]>>spawn(() -> (mutex, context) -> {
                try {
                    mutex.recover(value -> {
                        value[0] = 0;
                        return null;
                    });
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    recovered.countDown();
                }
            });

            repairer.send(shared);
            assertTrue(recovered.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertFalse(shared.isPoisoned());
            assertEquals(0, shared.withLock(value -> value[0]).intValue());
        }
    }

    @Test
    void actorUsesAsyncAcquisitionForSharedMutex() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var ref = runtime.<OresMutex.Shared<int[]>>spawn(() -> (mutex, context) -> {
                try {
                    assertThrows(OresMutex.WrongMutexDomainException.class, mutex::lock);
                    var guard = mutex.lockAsync().join();
                    guard.value()[0]++;
                    guard.release();
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    done.countDown();
                }
            });

            var shared = OresMutex.shared(new int[]{0});
            ref.send(shared);

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(1, shared.withLock(value -> value[0]).intValue());
        }
    }

    @Test
    void sharedMutexBindsOnlyAfterAuthorizedRuntimePublication() {
        IsolatePolicy strict = IsolatePolicy.strictFaas();
        var shared = OresMutex.shared(new int[]{0});

        try (ActorRuntime denied = new ActorRuntime(strict);
             ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var deniedRef = denied.<OresMutex.Shared<int[]>>spawn(
                    strict, () -> (message, context) -> { });
            var refA = runtimeA.<OresMutex.Shared<int[]>>spawn(
                    () -> (message, context) -> { });
            var refB = runtimeB.<OresMutex.Shared<int[]>>spawn(
                    () -> (message, context) -> { });

            assertThrows(SecurityException.class, () -> deniedRef.send(shared));
            assertDoesNotThrow(() -> refA.send(shared));

            IllegalArgumentException crossRuntime = assertThrows(
                    IllegalArgumentException.class,
                    () -> refB.send(shared));
            assertTrue(crossRuntime.getMessage().contains("owning ActorRuntime"));
        }
    }

    @Test
    void strictActorPolicyRejectsSharedMemoryHandles() {
        IsolatePolicy strict = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(strict)) {
            var ref = runtime.<OresMutex.Shared<int[]>>spawn(strict, () -> (message, context) -> { });
            var shared = OresMutex.shared(new int[]{0});
            assertThrows(SecurityException.class, () -> ref.send(shared));
        }
    }

    @Test
    void strictActorCannotUseCapturedSharedMutexWithoutCapability() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy strict = IsolatePolicy.strictFaas();
            var shared = OresMutex.shared(new int[]{0});
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            var ref = runtime.<String>spawn(strict, () -> (message, context) -> {
                try {
                    shared.tryLock();
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    checked.countDown();
                }
            });

            ref.send("check");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, observed.get());

            // The denied touch must not bind the handle to the strict actor's runtime.
            try (ActorRuntime otherRuntime = new ActorRuntime()) {
                var other = otherRuntime.<OresMutex.Shared<int[]>>spawn(
                        () -> (mutex, context) -> { });
                assertDoesNotThrow(() -> other.send(shared));
            }
        }
    }

    @Test
    void strictActorCannotCreateSharedMutexDirectly() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy strict = IsolatePolicy.strictFaas();
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            var ref = runtime.<String>spawn(strict, () -> (message, context) -> {
                try {
                    OresMutex.shared(new int[]{0});
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    checked.countDown();
                }
            });

            ref.send("check");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, observed.get());
        }
    }

}
