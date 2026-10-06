package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class GeneratorRuntimeTest {
    @Test
    void closeAtYieldWaitsForTheProducerToLeaveItsGuestTurn() throws Exception {
        CountDownLatch leaving = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime(turn -> {
            try { turn.run(); }
            finally {
                leaving.countDown();
                boolean interrupted = false;
                for (;;) {
                    try { allowExit.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }); var generator = GeneratorRuntime.generator(runtime, emitter -> emitter.emit(1))) {
            assertEquals(1, generator.nextStep().value());
            java.util.concurrent.FutureTask<Void> close =
                    new java.util.concurrent.FutureTask<>(() -> { generator.close(); return null; });
            Thread closer = Thread.ofVirtual().start(close);
            try {
                assertTrue(leaving.await(2, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> close.get(100, TimeUnit.MILLISECONDS));
            } finally { allowExit.countDown(); }
            close.get(2, TimeUnit.SECONDS);
            closer.join();
            assertTrue(generator.nextStep().done());
        }
    }

    @Test
    void terminalPullWaitsForTheProducerToLeaveItsGuestTurn() throws Exception {
        CountDownLatch leaving = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime(turn -> {
            turn.run();
            leaving.countDown();
            try { allowExit.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }); var generator = GeneratorRuntime.generator(runtime, emitter -> {})) {
            java.util.concurrent.FutureTask<GeneratorRuntime.Step<Object>> pull =
                    new java.util.concurrent.FutureTask<>(generator::nextStep);
            Thread consumer = Thread.ofVirtual().start(pull);
            try {
                assertTrue(leaving.await(2, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> pull.get(100, TimeUnit.MILLISECONDS));
            } finally { allowExit.countDown(); }
            assertTrue(pull.get(2, TimeUnit.SECONDS).done());
            consumer.join();
        }
    }
    @Test
    void rejectedBackingGuestTurnFailsThePullInsteadOfParkingForever() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            try (AsyncRuntime runtime = new AsyncRuntime(turn -> {
                throw new SecurityException("generator guest turn denied");
            }); var generator = GeneratorRuntime.generator(runtime, emitter -> emitter.emit(1))) {
                SecurityException denied = assertThrows(SecurityException.class, generator::nextStep);
                assertEquals("generator guest turn denied", denied.getMessage());
            }
        });
    }
    @Test
    void asyncPullsUseOresFutureIncludingTerminalPulls() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.asyncGenerator(runtime, emitter -> emitter.emit(42))) {
            var first = generator.nextStep();
            assertEquals(OresFuture.class, first.getClass());
            assertEquals(42, first.get(2, TimeUnit.SECONDS).value());
            assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
            generator.close();
            var terminal = generator.nextStep();
            assertEquals(OresFuture.class, terminal.getClass());
            assertTrue(terminal.get(2, TimeUnit.SECONDS).done());
        }
    }

    @Test
    void channelAsyncIteratorDrainsBufferedValuesThenCompletesOnNormalClose() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(2);
            assertTrue(channel.tryWrite(1));
            assertTrue(channel.tryWrite(2));
            channel.close();

            try (var iterator = GeneratorRuntime.channelAsyncIterator(runtime, channel)) {
                assertEquals(1, iterator.nextStep().get(2, TimeUnit.SECONDS).value());
                assertEquals(2, iterator.nextStep().get(2, TimeUnit.SECONDS).value());
                assertTrue(iterator.nextStep().get(2, TimeUnit.SECONDS).done());
            }
        }
    }

    @Test
    void channelAsyncIteratorPreservesRendezvousBackpressureUntilPull() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
            OresFuture<Void> writer = channel.writeAsync(5);
            assertFalse(writer.isDone());

            try (var iterator = GeneratorRuntime.channelAsyncIterator(runtime, channel)) {
                assertEquals(5, iterator.nextStep().get(2, TimeUnit.SECONDS).value());
                writer.get(2, TimeUnit.SECONDS);
                assertTrue(writer.isDone());
                channel.close();
                assertTrue(iterator.nextStep().get(2, TimeUnit.SECONDS).done());
            }
        }
    }

    @Test
    void channelAsyncIteratorPropagatesExceptionalCloseAfterDrain() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
            assertTrue(channel.tryWrite(7));
            channel.close(new IllegalStateException("boom"));

            try (var iterator = GeneratorRuntime.channelAsyncIterator(runtime, channel)) {
                assertEquals(7, iterator.nextStep().get(2, TimeUnit.SECONDS).value());
                java.util.concurrent.ExecutionException failure = assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> iterator.nextStep().get(2, TimeUnit.SECONDS));
                Throwable cause = failure.getCause();
                assertInstanceOf(ChannelRuntime.ChannelClosedException.class, cause);
                assertNotNull(cause.getCause());
                assertEquals("boom", cause.getCause().getMessage());
            }
        }
    }

    @Test
    void closingChannelIteratorCancelsPendingReadWithoutClosingOrStealingFromChannel() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
            var iterator = GeneratorRuntime.channelAsyncIterator(runtime, channel);
            OresFuture<GeneratorRuntime.Step<Integer>> pending = iterator.nextStep();

            // Give the producer carrier a chance to arm the channel read.
            Thread.sleep(25);
            iterator.close();
            assertTrue(pending.get(2, TimeUnit.SECONDS).done());
            assertFalse(channel.isClosed());

            assertTrue(channel.tryWrite(9));
            assertEquals(9, channel.tryRead().orElseThrow());
            channel.close();
        }
    }

    @Test
    void twoChannelIteratorViewsCompeteForValuesRatherThanBroadcasting() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(2);
            assertTrue(channel.tryWrite(1));
            assertTrue(channel.tryWrite(2));
            channel.close();

            try (var a = GeneratorRuntime.channelAsyncIterator(runtime, channel);
                 var b = GeneratorRuntime.channelAsyncIterator(runtime, channel)) {
                assertEquals(1, a.nextStep().get(2, TimeUnit.SECONDS).value());
                assertEquals(2, b.nextStep().get(2, TimeUnit.SECONDS).value());
                assertTrue(a.nextStep().get(2, TimeUnit.SECONDS).done());
                assertTrue(b.nextStep().get(2, TimeUnit.SECONDS).done());
            }
        }
    }

    @Test
    void closeUnblocksAnInFlightPullAndInterruptsProducer() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.asyncGenerator(runtime, emitter -> {
                 started.countDown();
                 try {
                     new CountDownLatch(1).await();
                 } catch (InterruptedException expected) {
                     interrupted.countDown();
                     throw expected;
                 }
             })) {
            var pull = generator.nextStep();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            generator.close();
            assertTrue(pull.get(2, TimeUnit.SECONDS).done());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeAtYieldDoesNotResumeGuestBody() throws Exception {
        CountDownLatch resumed = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.asyncGenerator(runtime, emitter -> {
                 emitter.emit(1);
                 resumed.countDown();
             })) {
            assertEquals(1, generator.nextStep().get(2, TimeUnit.SECONDS).value());
            generator.close();
            assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
            assertFalse(resumed.await(100, TimeUnit.MILLISECONDS));
        }
    }
}
