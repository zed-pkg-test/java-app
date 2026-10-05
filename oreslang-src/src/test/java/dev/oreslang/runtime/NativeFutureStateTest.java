package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class NativeFutureStateTest {

    @Test
    void nativeKernelOwnsSingleWinnerSettlementState() {
        NativeFutureState state = new NativeFutureState();

        assertEquals(NativeFutureState.PENDING, state.state());
        assertFalse(state.isDone());

        assertTrue(state.tryBeginSettlement());
        assertEquals(NativeFutureState.SETTLING, state.state());
        assertFalse(state.tryBeginSettlement(),
                "a second producer must not acquire settlement authority");

        state.publish(NativeFutureState.SUCCESS);
        assertEquals(NativeFutureState.SUCCESS, state.state());
        assertTrue(state.isDone());
        assertFalse(state.isCancelled());
        assertFalse(state.tryBeginSettlement(),
                "terminal Futures must reject later settlement attempts");
    }

    @Test
    void oresFutureNoLongerUsesJavaAtomicReferenceAsSettlementAuthority() throws Exception {
        assertTrue(Arrays.stream(OresFuture.class.getDeclaredFields())
                .noneMatch(field -> field.getType() == AtomicReference.class),
                "OresFuture settlement state must remain behind the native kernel boundary");

        OresFuture<Integer> future = new OresFuture<>();
        assertTrue(future.completeFromRuntime(42));
        assertFalse(future.completeFromRuntime(43));
        assertTrue(future.isDone());
        assertEquals(42, future.get());
    }
}
