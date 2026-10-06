package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class SelectSchedulerProofTest {

    @Test
    void nbSelectFutureCompletionReentersOwningActorInsteadOfProducerThread() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            ChannelRuntime.Channel<String> left = new ChannelRuntime.Channel<>(0);
            ChannelRuntime.Channel<String> right = new ChannelRuntime.Channel<>(0);
            CountDownLatch armed = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);
            AtomicReference<Thread> producer = new AtomicReference<>();
            AtomicReference<Thread> continuation = new AtomicReference<>();
            AtomicReference<ChannelRuntime.SelectResult> observed = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                if (!message.equals("arm")) return;
                ActorRuntime.ContinuationTarget target =
                        context.runtime().captureCurrentContinuationTarget();
                OresFuture<ChannelRuntime.SelectResult> pending =
                        ChannelRuntime.SelectSet.of(
                                ChannelRuntime.read(left),
                                ChannelRuntime.read(right))
                                .selectAsync();

                context.runtime().enqueueOnCompletion(pending, target, (result, failure) -> {
                    assertNull(failure);
                    assertTrue(ActorRuntime.inActorExecution());
                    assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                    continuation.set(Thread.currentThread());
                    observed.set(result);
                    resumed.countDown();
                });
                armed.countDown();
            });

            actor.send("arm");
            assertTrue(armed.await(2, TimeUnit.SECONDS));

            Thread writer = Thread.ofPlatform().name("proof-select-producer").start(() -> {
                producer.set(Thread.currentThread());
                assertTrue(right.tryWrite("right"));
            });
            writer.join(2_000);
            assertFalse(writer.isAlive());

            assertTrue(resumed.await(2, TimeUnit.SECONDS));
            assertEquals(1, observed.get().index());
            assertEquals(ChannelRuntime.SelectOperation.READ, observed.get().operation());
            assertEquals("right", observed.get().value());
            assertNotSame(producer.get(), continuation.get(),
                    "select completion must enqueue actor work rather than execute guest code inline");

            actor.stop();
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void fairSelectRotatesAcrossSimultaneouslyReadyChannelsWithoutDoubleCommit() throws Exception {
        ChannelRuntime.Channel<Integer> left = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> right = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(left),
                ChannelRuntime.read(right));

        List<Integer> winners = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            assertTrue(left.tryWrite(round));
            assertTrue(right.tryWrite(round));

            ChannelRuntime.SelectResult selected =
                    set.selectAsync(ChannelRuntime.SelectPolicy.FAIR)
                            .get(2, TimeUnit.SECONDS);
            winners.add(selected.index());
            assertEquals(ChannelRuntime.SelectOperation.READ, selected.operation());
            assertEquals(round, selected.value());

            if (selected.index() == 0) {
                assertTrue(left.tryRead().isEmpty(), "winning read must commit exactly once");
                assertEquals(round, right.tryRead().orElseThrow(),
                        "losing ready case must remain uncommitted");
            } else {
                assertTrue(right.tryRead().isEmpty(), "winning read must commit exactly once");
                assertEquals(round, left.tryRead().orElseThrow(),
                        "losing ready case must remain uncommitted");
            }
        }

        assertEquals(List.of(0, 1, 0, 1, 0, 1, 0, 1, 0, 1), winners,
                "FAIR select should deterministically rotate the stable select set");
    }

    @Test
    void prioritySelectKeepsExplicitLexicalPriority() throws Exception {
        ChannelRuntime.Channel<Integer> left = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> right = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(left),
                ChannelRuntime.read(right));

        for (int round = 0; round < 6; round++) {
            assertTrue(left.tryWrite(round));
            assertTrue(right.tryWrite(round));

            ChannelRuntime.SelectResult selected =
                    set.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY)
                            .get(2, TimeUnit.SECONDS);
            assertEquals(0, selected.index());
            assertEquals(round, selected.value());
            assertEquals(round, right.tryRead().orElseThrow());
        }
    }

    @Test
    void cancelledNbSelectDetachesRegistrationsAndCannotStealLaterTraffic() throws Exception {
        ChannelRuntime.Channel<String> left = new ChannelRuntime.Channel<>(0);
        ChannelRuntime.Channel<String> right = new ChannelRuntime.Channel<>(0);
        OresFuture<ChannelRuntime.SelectResult> pending =
                ChannelRuntime.SelectSet.of(
                        ChannelRuntime.read(left),
                        ChannelRuntime.read(right))
                        .selectAsync();

        assertFalse(pending.isDone());
        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());

        assertFalse(left.tryWrite("orphan-left"),
                "cancelled select must detach its left registration");
        assertFalse(right.tryWrite("orphan-right"),
                "cancelled select must detach its right registration");

        OresFuture<String> realReader = left.readAsync();
        assertTrue(left.tryWrite("live"));
        assertEquals("live", realReader.get(2, TimeUnit.SECONDS));
    }

    @Test
    void dynamicSelectMapUsesTheSameRuntimeSelectionEngine() throws Exception {
        ChannelRuntime.Channel<String> alpha = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> beta = new ChannelRuntime.Channel<>(1);
        assertTrue(beta.tryWrite("beta"));

        Map<String, ChannelRuntime.SelectCase> cases = new LinkedHashMap<>();
        cases.put("alpha", ChannelRuntime.read(alpha));
        cases.put("beta", ChannelRuntime.read(beta));

        ChannelRuntime.SelectResult result =
                ChannelRuntime.SelectSet.fromMap(cases)
                        .selectAsync()
                        .get(2, TimeUnit.SECONDS);

        assertEquals(1, result.index());
        assertEquals(ChannelRuntime.SelectOperation.READ, result.operation());
        assertEquals("beta", result.value());
    }

    @Test
    void oneSharedCarrierMultiplexesManyActorsAndChannelContinuations() throws Exception {
        int actorCount = 12;
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 4))) {
            CountDownLatch armed = new CountDownLatch(actorCount);
            CountDownLatch completed = new CountDownLatch(actorCount);
            AtomicInteger callbacks = new AtomicInteger();
            List<ChannelRuntime.Channel<Integer>> channels = new ArrayList<>();
            List<ActorRuntime.ActorRef<String>> actors = new ArrayList<>();

            for (int i = 0; i < actorCount; i++) {
                int expected = i;
                ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
                channels.add(channel);

                ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                    if (!message.equals("arm")) return;
                    ActorRuntime.ContinuationTarget target =
                            context.runtime().captureCurrentContinuationTarget();
                    OresFuture<Integer> pending = channel.readAsync();
                    context.runtime().enqueueOnCompletion(pending, target, (value, failure) -> {
                        assertNull(failure);
                        assertEquals(expected, value);
                        assertTrue(ActorRuntime.inActorExecution());
                        assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                        callbacks.incrementAndGet();
                        completed.countDown();
                    });
                    armed.countDown();
                });
                actors.add(actor);
                actor.send("arm");
            }

            assertTrue(armed.await(3, TimeUnit.SECONDS));

            Thread producer = Thread.ofPlatform().name("proof-many-actor-producer").start(() -> {
                for (int i = 0; i < channels.size(); i++) {
                    assertTrue(channels.get(i).tryWrite(i));
                }
            });
            producer.join(3_000);
            assertFalse(producer.isAlive());

            assertTrue(completed.await(4, TimeUnit.SECONDS),
                    "one bounded shared carrier must make progress across every runnable actor");
            assertEquals(actorCount, callbacks.get());

            for (ActorRuntime.ActorRef<String> actor : actors) {
                actor.stop();
                assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }
}
