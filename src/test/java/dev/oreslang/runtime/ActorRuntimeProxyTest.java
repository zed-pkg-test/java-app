package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
}
