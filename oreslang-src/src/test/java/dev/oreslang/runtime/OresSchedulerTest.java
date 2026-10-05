package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresSchedulerTest {

    @Test
    void awaitResumesOnlyOnOwningSchedulerNotProducerThread() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> source = new OresFuture<>();
            AtomicReference<Thread> producer = new AtomicReference<>();
            AtomicInteger state = new AtomicInteger();
            CountDownLatch firstTurnReached = new CountDownLatch(1);

            OresFuture<Integer> result = scheduler.start(resume -> {
                int pc = state.getAndIncrement();
                assertSame(scheduler, OresScheduler.current());
                assertTrue(NativeCarrierExecutor.isNativeCarrierThread(),
                        "OresScheduler task turns must run on JNI pthread carriers");
                assertNotEquals(0L, NativeCarrierExecutor.currentNativeThreadId());

                if (pc == 0) {
                    assertTrue(resume.initial());
                    IllegalStateException joinFailure = assertThrows(
                            IllegalStateException.class,
                            source::join);
                    assertTrue(joinFailure.getMessage().contains("use await"));
                    assertThrows(
                            IllegalStateException.class,
                            () -> source.get(1, TimeUnit.MILLISECONDS));
                    firstTurnReached.countDown();
                    return OresScheduler.await(source);
                }

                assertFalse(resume.initial());
                assertNull(resume.failure());
                assertEquals(41, resume.value());
                assertNotSame(producer.get(), Thread.currentThread(),
                        "producer/completion thread must never execute guest continuation");
                return OresScheduler.done(42);
            });

            Thread completionThread = Thread.ofPlatform().start(() -> {
                producer.set(Thread.currentThread());
                try {
                    assertTrue(firstTurnReached.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail(interrupted);
                }
                source.completeFromRuntime(41);
            });
            completionThread.join();

            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, state.get());
        }
    }

    @Test
    void alreadyCompletedFutureStillCreatesLaterSchedulerTurn() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> completed = OresFuture.completed(7);
            AtomicInteger state = new AtomicInteger();
            AtomicBoolean insideFirstResume = new AtomicBoolean();

            OresFuture<Integer> result = scheduler.start(resume -> {
                int pc = state.getAndIncrement();
                if (pc == 0) {
                    insideFirstResume.set(true);
                    try {
                        return OresScheduler.await(completed);
                    } finally {
                        insideFirstResume.set(false);
                    }
                }

                assertFalse(insideFirstResume.get(),
                        "await continuation must not resume inline in the suspending turn");
                assertEquals(7, resume.value());
                return OresScheduler.done(8);
            });

            assertEquals(8, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, state.get());
        }
    }

    @Test
    void completedAwaitMayReuseSameCarrierButAlwaysGetsFreshDispatch() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> completed = OresFuture.completed(7);
            AtomicInteger pc = new AtomicInteger();
            AtomicReference<Thread> firstCarrier = new AtomicReference<>();
            AtomicReference<Long> firstDispatch = new AtomicReference<>();

            OresFuture<Integer> result = scheduler.start(resume -> {
                if (pc.getAndIncrement() == 0) {
                    firstCarrier.set(Thread.currentThread());
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    assertNotEquals(0L, firstDispatch.get().longValue());
                    return OresScheduler.await(completed);
                }

                // A one-thread pool guarantees physical carrier reuse. The
                // logical scheduler dispatch must nevertheless be new.
                assertSame(firstCarrier.get(), Thread.currentThread());
                assertNotEquals(
                        firstDispatch.get().longValue(),
                        OresScheduler.currentDispatchId(),
                        "await must unwind and re-enter through a fresh scheduler dispatch");
                assertEquals(7, resume.value());
                return OresScheduler.done(8);
            });

            assertEquals(8, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, pc.get());
        }
    }

    @Test
    void runtimeOwnedCompletionPublishesOnlyAfterGuestTurnAdmissionExits() throws Exception {
        ExecutorService carrier = Executors.newSingleThreadExecutor();
        AtomicBoolean insideGuestTurn = new AtomicBoolean();
        CountDownLatch completionObserved = new CountDownLatch(1);
        AtomicBoolean completionPublishedInsideGuestTurn = new AtomicBoolean();

        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "test-runtime-owned",
                1,
                carrier,
                turn -> {
                    assertFalse(
                            insideGuestTurn.get(),
                            "runtime scheduler guest turns must not nest context admission");
                    insideGuestTurn.set(true);
                    try {
                        turn.run();
                    } finally {
                        insideGuestTurn.set(false);
                    }
                })) {
            OresFuture<Integer> result =
                    scheduler.start(resume -> OresScheduler.done(42));

            result.whenCompleteRuntime((value, failure) -> {
                completionPublishedInsideGuestTurn.set(insideGuestTurn.get());
                completionObserved.countDown();
            });

            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertTrue(completionObserved.await(5, TimeUnit.SECONDS));
            assertFalse(
                    completionPublishedInsideGuestTurn.get(),
                    "terminal Future publication must happen only after guest/context exit");
        } finally {
            carrier.shutdownNow();
            assertTrue(carrier.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void tailAwaitAlwaysUsesFreshDispatchWithoutChangingLogicalTaskDomain() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger pc = new AtomicInteger();
            AtomicReference<Object> domain = new AtomicReference<>();
            AtomicReference<Long> firstDispatch = new AtomicReference<>();
            AtomicBoolean insideFirstTurn = new AtomicBoolean();

            OresFuture<Integer> result = scheduler.start(resume -> {
                int turn = pc.getAndIncrement();
                if (turn == 0) {
                    assertTrue(resume.initial());
                    domain.set(OresScheduler.currentTaskDomain());
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    insideFirstTurn.set(true);
                    try {
                        return OresScheduler.tailAwait();
                    } finally {
                        insideFirstTurn.set(false);
                    }
                }

                assertFalse(resume.initial());
                assertFalse(insideFirstTurn.get(),
                        "tail-await replacement must never execute inline");
                assertSame(domain.get(), OresScheduler.currentTaskDomain(),
                        "proper async tail transfer keeps one logical scheduler task");
                assertNotEquals(firstDispatch.get(), OresScheduler.currentDispatchId(),
                        "tail-await must re-enter through a fresh scheduler dispatch");
                return OresScheduler.done(42);
            });

            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, pc.get());
        }
    }

    @Test
    void oneFutureMayResumeWaitersOnDifferentSchedulers() throws Exception {
        try (OresScheduler left = new OresScheduler(1);
             OresScheduler right = new OresScheduler(1)) {

            OresFuture<Integer> shared = new OresFuture<>();
            AtomicInteger leftPc = new AtomicInteger();
            AtomicInteger rightPc = new AtomicInteger();

            OresFuture<String> leftResult = left.start(resume -> {
                if (leftPc.getAndIncrement() == 0) {
                    assertSame(left, OresScheduler.current());
                    return OresScheduler.await(shared);
                }
                assertSame(left, OresScheduler.current());
                return OresScheduler.done("left:" + resume.value());
            });

            OresFuture<String> rightResult = right.start(resume -> {
                if (rightPc.getAndIncrement() == 0) {
                    assertSame(right, OresScheduler.current());
                    return OresScheduler.await(shared);
                }
                assertSame(right, OresScheduler.current());
                return OresScheduler.done("right:" + resume.value());
            });

            shared.completeFromRuntime(9);

            assertEquals("left:9", leftResult.get(5, TimeUnit.SECONDS));
            assertEquals("right:9", rightResult.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void awaitFailureIsDeliveredBackToOwningTask() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source =
                    OresFuture.failed(new IllegalStateException("boom"));
            AtomicInteger pc = new AtomicInteger();

            OresFuture<String> result = scheduler.start(resume -> {
                if (pc.getAndIncrement() == 0) {
                    return OresScheduler.await(source);
                }
                assertInstanceOf(IllegalStateException.class, resume.failure());
                assertEquals("boom", resume.failure().getMessage());
                return OresScheduler.done("handled");
            });

            assertEquals("handled", result.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void synchronousTaskIsStillSchedulerBound() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Boolean> result =
                    scheduler.startSync(() -> OresScheduler.current() == scheduler);
            assertTrue(result.get(5, TimeUnit.SECONDS));
        }
    }
    @Test
    void logicalTaskDomainSurvivesAwaitButIsDistinctPerTask() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<Object> firstDomain = new AtomicReference<>();
            AtomicReference<Object> resumedDomain = new AtomicReference<>();
            AtomicReference<Object> secondTaskDomain = new AtomicReference<>();
            AtomicInteger firstPc = new AtomicInteger();

            OresFuture<Integer> first = scheduler.start(resume -> {
                if (firstPc.getAndIncrement() == 0) {
                    firstDomain.set(OresScheduler.currentTaskDomain());
                    assertNotNull(firstDomain.get());
                    return OresScheduler.await(gate);
                }
                resumedDomain.set(OresScheduler.currentTaskDomain());
                return OresScheduler.done(1);
            });

            OresFuture<Integer> second = scheduler.start(resume -> {
                secondTaskDomain.set(OresScheduler.currentTaskDomain());
                return OresScheduler.done(2);
            });

            assertEquals(2, second.get(5, TimeUnit.SECONDS));
            gate.completeFromRuntime(0);
            assertEquals(1, first.get(5, TimeUnit.SECONDS));

            assertSame(firstDomain.get(), resumedDomain.get(),
                    "await/resume must preserve the logical task execution domain");
            assertNotSame(firstDomain.get(), secondTaskDomain.get(),
                    "two tasks on one scheduler must not share mutex/borrow ownership");
        }
    }

    @Test
    void schedulerCannotCloseItselfFromOwnTaskTurn() throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        try {
            OresFuture<Boolean> result = scheduler.startSync(() -> {
                IllegalStateException failure =
                        assertThrows(IllegalStateException.class, scheduler::close);
                return failure.getMessage().contains("outside/root");
            });

            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertFalse(scheduler.isClosed(),
                    "failed self-close must leave scheduler usable");
        } finally {
            scheduler.close();
        }
    }

}
