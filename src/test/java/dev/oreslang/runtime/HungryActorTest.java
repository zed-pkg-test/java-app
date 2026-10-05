package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class HungryActorTest {
    @Test
    void ownsOnePlatformThreadUntilBehaviorReleasesIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> firstThread = new AtomicReference<>();
        AtomicReference<String> secondThread = new AtomicReference<>();

        try (HungryActor<String> actor = new HungryActor<>(
                "cpu",
                8,
                (message, context) -> {
                    if (message.equals("one")) {
                        firstThread.set(Thread.currentThread().getName());
                        started.countDown();
                    } else if (message.equals("two")) {
                        secondThread.set(Thread.currentThread().getName());
                        context.release();
                    }
                })) {
            assertFalse(actor.isVirtualCarrier(), "HungryActor must reserve a platform/OS carrier");
            assertTrue(actor.usesNativeCarrier(), "HungryActor must use the JNI/pthread backend");
            assertTrue(actor.isAlive(), "dedicated thread starts with actor lifetime");

            actor.send("one");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            actor.send("two");

            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(firstThread.get(), secondThread.get());
            assertEquals(actor.threadName(), firstThread.get());
            assertNotEquals(0L, actor.nativeCarrierThreadId());
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void cpuBoundHungryActorDoesNotStarveOrdinaryActorDispatcher() throws Exception {
        CountDownLatch hungryStarted = new CountDownLatch(1);
        AtomicBoolean releaseCpu = new AtomicBoolean();

        try (HungryActor<String> hungry = new HungryActor<>(
                "hot-loop",
                2,
                (message, context) -> {
                    hungryStarted.countDown();
                    while (!releaseCpu.get()) {
                        context.schedulerSafepoint();
                    }
                    context.release();
                });
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(1, 1, 8))) {

            hungry.send("spin");
            assertTrue(hungryStarted.await(2, TimeUnit.SECONDS));

            CountDownLatch ordinaryRan = new CountDownLatch(1);
            var ordinary = runtime.<String>spawnPrivate(() -> (message, context) -> {
                ordinaryRan.countDown();
                context.self().stop();
            });
            ordinary.send("ping");

            assertTrue(
                    ordinaryRan.await(2, TimeUnit.SECONDS),
                    "dedicated CPU work must not consume the ordinary actor dispatcher");
            releaseCpu.set(true);
            assertTrue(hungry.awaitTermination(2, TimeUnit.SECONDS));
        }
    }


    @Test
    void closeFromInsideBehaviorDoesNotSelfDeadlock() throws Exception {
        CountDownLatch returnedFromClose = new CountDownLatch(1);

        HungryActor<String> actor = new HungryActor<>(
                "self-close",
                2,
                (message, context) -> {
                    context.self().close();
                    returnedFromClose.countDown();
                });
        try {
            actor.send("stop");
            assertTrue(returnedFromClose.await(2, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        } finally {
            actor.close();
        }
    }

    @Test
    void mailboxIsBoundedAndMessagesAreFrozen() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Object> observed = new AtomicReference<>();

        try (HungryActor<java.util.List<Integer>> actor = new HungryActor<>(
                "freeze",
                2,
                (message, context) -> {
                    observed.set(message);
                    received.countDown();
                    context.release();
                })) {
            java.util.ArrayList<Integer> mutable =
                    new java.util.ArrayList<>(java.util.List.of(1, 2));
            actor.send(mutable);
            mutable.add(3);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(java.util.List.of(1, 2), observed.get());
        }
    }

    @Test
    void mailboxCapacityIsEnforcedWithoutBlockingTheSender() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (HungryActor<String> actor = new HungryActor<>(
                "bounded",
                1,
                (message, context) -> {
                    if (message.equals("first")) {
                        firstStarted.countDown();
                        assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                    } else {
                        context.release();
                    }
                })) {
            actor.send("first");
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            actor.send("queued");
            IllegalStateException full = assertThrows(
                    IllegalStateException.class,
                    () -> actor.send("overflow"));
            assertTrue(full.getMessage().contains("mailbox limit exceeded"));

            releaseFirst.countDown();
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
