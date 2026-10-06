package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorStopMailboxRaceTest {
    @Test
    void hostStopAfterTaskCompletionDoesNotTurnMailboxClosureIntoActorFailure() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            for (int round = 0; round < 1000; round++) {
                AtomicReference<OresFuture<Integer>> task = new AtomicReference<>();
                CountDownLatch started = new CountDownLatch(1);
                ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                    task.set(runtime.startActorTask(resume -> OresScheduler.done(42)));
                    started.countDown();
                });
                actor.send("complete");
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertEquals(42, task.get().get(2, TimeUnit.SECONDS));
                actor.stop();
                assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(actor.failure().isEmpty(), () -> "normal stop recorded failure: " + actor.failure());
            }
        }
    }
}
