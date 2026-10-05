package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class NativeTimerServiceTest {

    @Test
    void oneShotDeadlineRunsOnNativeTimerPthread() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();

        NativeTimerService.process().schedule(() -> {
            callbacks.incrementAndGet();
            fired.countDown();
        }, TimeUnit.MILLISECONDS.toNanos(10));

        assertTrue(fired.await(2, TimeUnit.SECONDS));
        assertEquals(1, callbacks.get());
    }

    @Test
    void cancelledDeadlineDoesNotRun() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        NativeTimerService.Ticket ticket =
                NativeTimerService.process().schedule(
                        fired::countDown,
                        TimeUnit.MILLISECONDS.toNanos(150));

        assertTrue(ticket.cancel());
        assertFalse(fired.await(250, TimeUnit.MILLISECONDS));
    }

    @Test
    void fixedDelayCanBeCancelledWithoutJavaSchedulerThread() throws Exception {
        CountDownLatch three = new CountDownLatch(3);
        AtomicInteger callbacks = new AtomicInteger();

        NativeTimerService.Ticket repeating =
                NativeTimerService.process().scheduleWithFixedDelay(() -> {
                    callbacks.incrementAndGet();
                    three.countDown();
                }, 0L, TimeUnit.MILLISECONDS.toNanos(10));

        assertTrue(three.await(2, TimeUnit.SECONDS));
        assertTrue(repeating.cancel());
        int afterCancel = callbacks.get();
        Thread.sleep(75);
        assertEquals(afterCancel, callbacks.get());
    }
}
