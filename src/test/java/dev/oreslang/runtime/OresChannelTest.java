package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresChannelTest {
    @Test
    void bufferedReadWriteAndTryOperationsAreExactOnce() {
        OresChannel<Integer> channel = new OresChannel<>(1);

        assertTrue(channel.tryWrite(7));
        assertFalse(channel.tryWrite(8));
        assertEquals(Optional.of(7), channel.tryRead());
        assertEquals(Optional.empty(), channel.tryRead());

        channel.write(9);
        assertEquals(9, channel.read());
    }

    @Test
    void rendezvousChannelPairsBlockingReaderAndWriter() throws Exception {
        OresChannel<String> channel = new OresChannel<>(0);
        AtomicReference<String> observed = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);

        Thread reader = Thread.startVirtualThread(() -> {
            started.countDown();
            observed.set(channel.read());
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        channel.write("payload");
        reader.join(1_000);

        assertFalse(reader.isAlive());
        assertEquals("payload", observed.get());
        assertEquals(0, channel.bufferedSize());
    }

    @Test
    void selectCommitsExactlyOneReadyCaseAndLeavesLosersUntouched() {
        OresChannel<Integer> left = new OresChannel<>(1);
        OresChannel<Integer> right = new OresChannel<>(1);
        left.write(11);
        right.write(22);

        OresChannel.SelectionResult selected = OresChannel.select(List.of(
                OresChannel.readCase(left),
                OresChannel.readCase(right)));

        assertTrue(selected.index() == 0 || selected.index() == 1);
        if (selected.index() == 0) {
            assertEquals(11, selected.value());
            assertEquals(Optional.of(22), right.tryRead());
        } else {
            assertEquals(22, selected.value());
            assertEquals(Optional.of(11), left.tryRead());
        }
    }

    @Test
    void trySelectNeverConsumesWhenNothingIsReady() {
        OresChannel<Integer> channel = new OresChannel<>(1);
        assertTrue(OresChannel.trySelect(List.of(OresChannel.readCase(channel))).isEmpty());
        assertTrue(channel.tryWrite(1));
        assertEquals(Optional.of(1), channel.tryRead());
    }

    @Test
    void dynamicMapSelectionPreservesKeyAndSupportsMixedCases() {
        OresChannel<Integer> incoming = new OresChannel<>(1);
        OresChannel<Integer> outgoing = new OresChannel<>(0);
        incoming.write(41);

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("read-ready", OresChannel.readCase(incoming));
        entries.put("write-ready", OresChannel.writeCase(outgoing, 99));

        OresChannel.SelectionResult selected = OresChannel.selectDynamic(entries);
        assertEquals("read-ready", selected.key());
        assertEquals(OresChannel.Operation.READ, selected.operation());
        assertEquals(41, selected.value());
        assertTrue(outgoing.tryRead().isEmpty());
    }

    @Test
    void twoBlockingSelectsCanRendezvousWithoutPolling() throws Exception {
        OresChannel<Integer> channel = new OresChannel<>(0);
        AtomicReference<OresChannel.SelectionResult> writerResult = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);

        Thread writer = Thread.startVirtualThread(() -> {
            started.countDown();
            writerResult.set(OresChannel.select(List.of(
                    OresChannel.writeCase(channel, 123))));
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        OresChannel.SelectionResult read = OresChannel.select(List.of(
                OresChannel.readCase(channel)));
        writer.join(1_000);

        assertFalse(writer.isAlive());
        assertEquals(123, read.value());
        assertNotNull(writerResult.get());
        assertEquals(OresChannel.Operation.WRITE, writerResult.get().operation());
        assertEquals(123, writerResult.get().value());
    }

    @Test
    void dynamicBareChannelEntriesMeanReadCases() {
        OresChannel<String> first = new OresChannel<>(1);
        OresChannel<String> second = new OresChannel<>(1);
        second.write("ready");

        OresChannel.SelectionResult selected = OresChannel.selectDynamic(List.of(first, second));
        assertEquals(1, selected.index());
        assertEquals(1L, selected.key());
        assertEquals("ready", selected.value());
    }
}
