package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeProxyTest {

    @Test
    void fairReadWriteProxyAllowsParallelReadersAndExclusiveWriter() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            int[] state = {0};
            ActorRuntime.Proxy<int[]> proxy = runtime.proxy(state);

            CountDownLatch readersEntered = new CountDownLatch(2);
            CountDownLatch releaseReaders = new CountDownLatch(1);
            CountDownLatch readersDone = new CountDownLatch(2);

            Runnable reader = () -> proxy.read(value -> {
                readersEntered.countDown();
                try {
                    assertTrue(releaseReaders.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail(interrupted);
                } finally {
                    readersDone.countDown();
                }
                return value[0];
            });

            Thread first = Thread.ofPlatform().start(reader);
            Thread second = Thread.ofPlatform().start(reader);
            assertTrue(readersEntered.await(2, TimeUnit.SECONDS),
                    "two proxy readers should coexist under the read lock");

            CountDownLatch writerEntered = new CountDownLatch(1);
            Thread writer = Thread.ofPlatform().start(() ->
                    proxy.write(value -> {
                        writerEntered.countDown();
                        value[0] = 7;
                        return null;
                    }));

            assertFalse(writerEntered.await(100, TimeUnit.MILLISECONDS),
                    "writer must not enter while readers hold the proxy");
            releaseReaders.countDown();
            assertTrue(readersDone.await(2, TimeUnit.SECONDS));
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS));

            first.join(2_000L);
            second.join(2_000L);
            writer.join(2_000L);
            assertEquals(7, proxy.read(value -> value[0]).intValue());
            assertTrue(proxy.readAcquisitions() >= 3);
            assertTrue(proxy.writeAcquisitions() >= 1);
        }
    }

    @Test
    void cooperativeRwLeasesPreserveWriterFairnessWithoutThreadOwnership() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.Proxy<int[]> proxy = runtime.proxy(new int[]{1});

            var firstReader = proxy.acquireReadAsync().get(2, TimeUnit.SECONDS);
            var writer = proxy.acquireWriteAsync();
            var lateReader = proxy.acquireReadAsync();

            assertFalse(writer.isDone(), "writer must wait behind an active reader");
            assertFalse(lateReader.isDone(),
                    "reader arriving behind a queued writer must not barge");

            firstReader.close();

            var writeLease = writer.get(2, TimeUnit.SECONDS);
            assertFalse(lateReader.isDone(),
                    "queued writer must receive the next exclusive grant");
            writeLease.write(value -> {
                value[0] = 9;
                return null;
            });
            writeLease.close();

            var secondReader = lateReader.get(2, TimeUnit.SECONDS);
            assertEquals(9, secondReader.read(value -> value[0]).intValue());
            secondReader.close();
            assertEquals(0, proxy.queuedWaiters());
        }
    }

    @Test
    void cancellingQueuedProxyLeaseRemovesWaiterAndDoesNotLoseTheNextGrant() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.Proxy<int[]> proxy = runtime.proxy(new int[]{4});

            var writer = proxy.acquireWriteAsync().get(2, TimeUnit.SECONDS);
            var cancelledReader = proxy.acquireReadAsync();
            var survivingReader = proxy.acquireReadAsync();

            assertEquals(2, proxy.queuedWaiters());
            assertTrue(cancelledReader.cancel(false));
            assertEquals(1, proxy.queuedWaiters(),
                    "cancellation hook must detach the queued waiter immediately");

            writer.close();

            var reader = survivingReader.get(2, TimeUnit.SECONDS);
            assertEquals(4, reader.read(value -> value[0]).intValue());
            reader.close();
            assertEquals(0, proxy.queuedWaiters());
        }
    }

    @Test
    void crossProxyNestingAndReadToWriteUpgradeFailClosed() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.Proxy<int[]> first = runtime.proxy(new int[]{1});
            ActorRuntime.Proxy<int[]> second = runtime.proxy(new int[]{2});

            IllegalStateException cross = assertThrows(
                    IllegalStateException.class,
                    () -> first.read(left ->
                            second.read(right -> left[0] + right[0])));
            assertTrue(cross.getMessage().contains("different rt Proxy"));

            IllegalStateException upgrade = assertThrows(
                    IllegalStateException.class,
                    () -> first.read(value ->
                            first.write(same -> {
                                same[0]++;
                                return same[0];
                            })));
            assertTrue(upgrade.getMessage().contains("upgrade"));
        }
    }


    @Test
    void closingProxyOrRuntimeFromReadSectionFailsFastWithoutPoisoningState() {
        ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer());
        try {
            ActorRuntime.Proxy<int[]> proxy = runtime.proxy(new int[]{5});

            proxy.read(value -> {
                IllegalStateException proxyClose = assertThrows(
                        IllegalStateException.class,
                        proxy::close);
                assertTrue(proxyClose.getMessage().contains("upgrade"));

                IllegalStateException runtimeClose = assertThrows(
                        IllegalStateException.class,
                        runtime::close);
                assertTrue(runtimeClose.getMessage().contains("critical section"));
                return value[0];
            });

            assertFalse(proxy.closed());
            assertEquals(5, proxy.read(value -> value[0]).intValue());
            proxy.close();
            assertTrue(proxy.closed());
        } finally {
            runtime.close();
        }
    }

    @Test
    void sharedActorMayReceiveProxyButPrivateActorCannot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.Proxy<int[]> proxy = runtime.proxy(new int[]{3});

            var shared = runtime.<ActorRuntime.Proxy<int[]>>spawnSharedTrusted(
                    context -> (message, actorContext) -> {
                        message.write(value -> {
                            value[0] = 7;
                            return null;
                        });
                        actorContext.self().stop();
                    });

            var isolated = runtime.<ActorRuntime.Proxy<int[]>>spawnPrivateTrusted(
                    context -> (message, actorContext) ->
                            actorContext.self().stop());

            shared.send(proxy);
            assertTrue(shared.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(shared.failure().isEmpty());
            assertEquals(7, proxy.read(value -> value[0]).intValue());

            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> isolated.send(proxy));
            assertTrue(denied.getMessage().contains("SHARED"));
        }
    }

    @Test
    void proxyHandleBudgetIsReclaimedOnClose() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            long before = runtime.sharedMemoryBytes();
            ActorRuntime.Proxy<int[]> proxy = runtime.proxy(new int[]{1});
            assertEquals(before + 96L, runtime.sharedMemoryBytes());
            proxy.close();
            assertEquals(before, runtime.sharedMemoryBytes());
            assertTrue(proxy.closed());
        }
    }

    @Test
    void nestedProxyViewsReuseIdentityAndQuotaWithinOneLockDomain() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            int[] nested = {4};
            long before = runtime.sharedMemoryBytes();
            ActorRuntime.Proxy<int[][]> parent = runtime.proxy(new int[][]{nested});
            assertEquals(before + 96L, runtime.sharedMemoryBytes());

            ActorRuntime.Proxy<int[]> first = parent.child(nested);
            assertEquals(before + 192L, runtime.sharedMemoryBytes());
            ActorRuntime.Proxy<int[]> second = parent.child(nested);

            assertSame(first, second,
                    "repeated nested projections must reuse one synchronized capability handle");
            assertEquals(before + 192L, runtime.sharedMemoryBytes(),
                    "repeated nested reads must not leak proxy-handle quota");

            first.write(value -> {
                value[0] = 9;
                return null;
            });
            assertEquals(9, second.read(value -> value[0]).intValue());
        }
    }


    @Test
    void runtimeCloseRevokesProxyWithoutWaitingForActiveLease() throws Exception {
        ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer());
        ActorRuntime.Proxy<int[]> proxy = runtime.proxy(new int[]{11});
        ActorRuntime.Proxy<int[]>.Access held =
                proxy.acquireReadAsync().get(2, TimeUnit.SECONDS);

        AtomicInteger closeState = new AtomicInteger();
        Thread closer = Thread.ofPlatform().start(() -> {
            runtime.close();
            closeState.set(1);
        });

        closer.join(2_000L);
        assertFalse(closer.isAlive(),
                "runtime close must not join behind an active proxy lease");
        assertEquals(1, closeState.get());
        assertTrue(proxy.closed(),
                "runtime close must revoke new proxy access immediately");
        assertThrows(IllegalStateException.class,
                () -> held.read(value -> value[0]),
                "an already-issued lease must not start new guest access after revocation");

        held.close();
    }

    @Test
    void runtimeCloseFailsQueuedProxyWaiters() throws Exception {
        ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer());
        ActorRuntime.Proxy<int[]> proxy = runtime.proxy(new int[]{5});
        ActorRuntime.Proxy<int[]>.Access writer =
                proxy.acquireWriteAsync().get(2, TimeUnit.SECONDS);
        OresFuture<ActorRuntime.Proxy<int[]>.Access> queued =
                proxy.acquireReadAsync();

        assertFalse(queued.isDone());
        assertEquals(1, proxy.queuedWaiters());

        runtime.close();

        assertTrue(queued.isDone(),
                "runtime teardown must settle suspended proxy waiters");
        assertThrows(java.util.concurrent.CancellationException.class, queued::join);
        writer.close();
    }


    @Test
    @SuppressWarnings("unchecked")
    void contendedProxyWaitersReleaseTheOnlySharedCarrier() throws Exception {
        ActorRuntime.DispatcherConfig config =
                new ActorRuntime.DispatcherConfig(1, 1, 1, 64);
        try (ActorRuntime runtime =
                     new ActorRuntime(IsolatePolicy.developer(), config)) {
            int contenders = 8;
            ActorRuntime.Proxy<int[]> proxy = runtime.proxy(new int[]{0});
            ActorRuntime.Proxy<int[]>.Access blocker =
                    proxy.acquireWriteAsync().get(2, TimeUnit.SECONDS);

            CountDownLatch registered = new CountDownLatch(contenders);
            CountDownLatch terminal = new CountDownLatch(contenders);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ArrayList<ActorRuntime.ActorRef<String>> refs = new ArrayList<>();

            try {
                for (int i = 0; i < contenders; i++) {
                    ActorRuntime.ActorRef<String> ref =
                            runtime.spawnSharedTrusted(factoryContext ->
                                    (message, actorContext) -> {
                                        OresFuture<Void> task =
                                                actorContext.runtime().startActorTask(
                                                        new OresScheduler.Task<Void>() {
                                                            @Override
                                                            public OresScheduler.Step<Void> resume(
                                                                    OresScheduler.Resume resume) {
                                                                if (resume.initial()) {
                                                                    OresFuture<ActorRuntime.Proxy<int[]>.Access> pending =
                                                                            proxy.acquireWriteAsync();
                                                                    registered.countDown();
                                                                    return OresScheduler.<Void>await(pending);
                                                                }
                                                                if (resume.failure() != null) {
                                                                    throw new RuntimeException(resume.failure());
                                                                }
                                                                ActorRuntime.Proxy<int[]>.Access access =
                                                                        (ActorRuntime.Proxy<int[]>.Access) resume.value();
                                                                try (access) {
                                                                    access.write(value -> {
                                                                        value[0]++;
                                                                        return null;
                                                                    });
                                                                }
                                                                return OresScheduler.done(null);
                                                            }
                                                        });
                                        actorContext.runtime().ownCurrentActorFuture(task);
                                        task.whenCompleteRuntime((ignored, taskFailure) -> {
                                            if (taskFailure != null) {
                                                failure.compareAndSet(null, taskFailure);
                                            }
                                            terminal.countDown();
                                        });
                                    });
                    refs.add(ref);
                    ref.send("go");
                }

                assertTrue(
                        registered.await(3, TimeUnit.SECONDS),
                        "all actor tasks must register proxy waiters even with only one shared carrier");
                assertEquals(
                        contenders,
                        proxy.queuedWaiters(),
                        "contended actor-side proxy acquisition must suspend the logical task instead of parking the carrier");
            } finally {
                blocker.close();
            }

            assertTrue(
                    terminal.await(5, TimeUnit.SECONDS),
                    "all suspended actor tasks should resume after the proxy grant chain advances");
            assertNull(failure.get(), () -> "proxy actor task failed: " + failure.get());
            assertEquals(contenders, proxy.read(value -> value[0]).intValue());
            assertEquals(0, proxy.queuedWaiters());

            for (ActorRuntime.ActorRef<String> ref : refs) ref.stop();
            for (ActorRuntime.ActorRef<String> ref : refs) {
                assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(ref.failure().isEmpty());
            }
        }
    }


    @Test
    void runtimeCloseCancelsQueuedProxyDispose() throws Exception {
        ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer());
        ActorRuntime.Proxy<int[]> proxy = runtime.proxy(new int[]{17});
        ActorRuntime.Proxy<int[]>.Access reader =
                proxy.acquireReadAsync().get(2, TimeUnit.SECONDS);

        OresFuture<Void> closing = proxy.closeAsync();
        assertFalse(closing.isDone(),
                "dispose must wait cooperatively behind the active reader");
        assertEquals(1, proxy.queuedWaiters());

        runtime.close();

        assertTrue(closing.isCancelled(),
                "runtime teardown must preserve cancellation through Proxy.closeAsync");
        assertThrows(java.util.concurrent.CancellationException.class, closing::join);
        assertTrue(proxy.closed());

        reader.close();
    }

}
