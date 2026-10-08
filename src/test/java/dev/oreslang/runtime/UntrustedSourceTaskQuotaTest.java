package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Test-only host probes for #402: quotas belong to logical tasks, not carriers. */
@Timeout(20)
final class UntrustedSourceTaskQuotaTest {

    @Test
    void cooperateCannotMintFreshFuelAcrossDispatcherBatches() throws Exception {
        AtomicReference<OresFuture<Integer>> taskResult = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger resumes = new AtomicInteger();
        AtomicLong firstDispatch = new AtomicLong();
        AtomicLong secondDispatch = new AtomicLong();

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime()) {
            var image = store.publish("quota-fuel.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    return context.self()::id;
                }
                @Override public void receiveActor(String type, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail, ActorRuntime.ActorContext<Object> context) {
                    taskResult.set(context.runtime().startActorTask(resume -> {
                        if (resumes.getAndIncrement() == 0) {
                            firstDispatch.set(OresScheduler.currentDispatchId());
                            setActiveQuotaFuel(1L);
                            context.runtime().schedulerSafepoint();
                            return OresScheduler.cooperate();
                        }
                        secondDispatch.set(OresScheduler.currentDispatchId());
                        context.runtime().schedulerSafepoint();
                        return OresScheduler.done(42);
                    }));
                    started.countDown();
                }
            };
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.UNTRUSTED, executor, "Worker");
            try {
                actor.ready().get(5, TimeUnit.SECONDS);
                actor.send("fuel");
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var result = taskResult.get();
                assertNotNull(result);
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertInstanceOf(ActorRuntime.UntrustedActorQuotaExceededException.class,
                        actor.failure().orElseThrow(),
                        "an exhausted task quota must fail-stop its actor");
                assertEquals(2, resumes.get());
                assertNotEquals(0L, firstDispatch.get());
                assertNotEquals(0L, secondDispatch.get());
                assertNotEquals(firstDispatch.get(), secondDispatch.get(),
                        "cooperate must resume the same logical task on a new dispatch");
            } finally {
                actor.cancel();
                actor.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void awaitingLongerThanOriginalWallDeadlineFailsOnResume() throws Exception {
        AtomicReference<OresFuture<Integer>> taskResult = new AtomicReference<>();
        OresFuture<Integer> awaited = new OresFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch firstResume = new CountDownLatch(1);
        AtomicInteger resumes = new AtomicInteger();

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime()) {
            var image = store.publish("quota-await.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    return context.self()::id;
                }
                @Override public void receiveActor(String type, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail, ActorRuntime.ActorContext<Object> context) {
                    taskResult.set(context.runtime().startActorTask(resume -> {
                        resumes.incrementAndGet();
                        if (resume.initial()) {
                            firstResume.countDown();
                            return OresScheduler.await(awaited);
                        }
                        context.runtime().schedulerSafepoint();
                        return OresScheduler.done(42);
                    }));
                    started.countDown();
                }
            };
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.UNTRUSTED, executor, "Worker");
            try {
                actor.ready().get(5, TimeUnit.SECONDS);
                actor.send("await");
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertNotNull(taskResult.get());
                assertTrue(firstResume.await(5, TimeUnit.SECONDS));
                Thread.sleep(400); // UNTRUSTED policy's 250ms wall budget runs across await
                awaited.completeFromRuntime(1);
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertInstanceOf(ActorRuntime.UntrustedActorQuotaExceededException.class,
                        actor.failure().orElseThrow(),
                        "an expired awaited source task must fail-stop its actor");
                assertEquals(1, resumes.get(),
                        "expired task must fail before guest code is reentered");
            } finally {
                actor.cancel();
                actor.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void caughtFuelExhaustionStillFailsTheOwningActor() throws Exception {
        CountDownLatch scheduled = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean guestCaught = new java.util.concurrent.atomic.AtomicBoolean();

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime()) {
            var image = store.publish("quota-catch.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    return context.self()::id;
                }
                @Override public void receiveActor(String type, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail, ActorRuntime.ActorContext<Object> context) {
                    context.runtime().startActorTask(resume -> {
                        setActiveQuotaFuel(0L);
                        try {
                            context.runtime().schedulerSafepoint();
                        } catch (ActorRuntime.UntrustedActorQuotaExceededException swallowed) {
                            guestCaught.set(true);
                        }
                        return OresScheduler.done(7);
                    });
                    scheduled.countDown();
                }
            };
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.UNTRUSTED, executor, "Worker");
            try {
                actor.ready().get(5, TimeUnit.SECONDS);
                actor.send("catch");
                assertTrue(scheduled.await(5, TimeUnit.SECONDS));
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(guestCaught.get(), "the test must prove guest code caught the first exception");
                assertInstanceOf(ActorRuntime.UntrustedActorQuotaExceededException.class,
                        actor.failure().orElseThrow(),
                        "a swallowed fuel exception must still terminate the actor on unwind");
            } finally {
                actor.cancel();
                actor.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void setActiveQuotaFuel(long fuel) {
        try {
            Field scope = ActorRuntime.class.getDeclaredField("CURRENT_UNTRUSTED_TASK_QUOTA");
            scope.setAccessible(true);
            Object quota = ((ThreadLocal<?>) scope.get(null)).get();
            assertNotNull(quota, "source task must be running in a logical quota scope");
            Field remaining = quota.getClass().getDeclaredField("remainingFuel");
            remaining.setAccessible(true);
            remaining.setLong(quota, fuel);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("could not inject test-only quota boundary", ex);
        }
    }
}
