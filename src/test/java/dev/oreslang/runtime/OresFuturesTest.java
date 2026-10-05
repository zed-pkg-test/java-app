package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class OresFuturesTest {

    @Test
    void allPreservesInputOrder() {
        OresFuture<Integer> first = new OresFuture<>();
        OresFuture<Integer> second = new OresFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(first, second));

        second.completeFromRuntime(2);
        assertFalse(all.isDone());
        first.completeFromRuntime(1);

        assertEquals(List.of(1, 2), AsyncRuntime.await(all));
    }

    @Test
    void allFailsWhenAnyChildFails() {
        OresFuture<Integer> first = new OresFuture<>();
        OresFuture<Integer> second = new OresFuture<>();
        OresFuture<List<Integer>> all = OresFutures.all(List.of(first, second));

        IllegalStateException boom = new IllegalStateException("boom");
        first.completeFromRuntime(1);
        second.failFromRuntime(boom);

        IllegalStateException observed =
                assertThrows(IllegalStateException.class, () -> AsyncRuntime.await(all));
        assertSame(boom, observed);
    }

    @Test
    void raceSettlesWithFirstCompletion() {
        OresFuture<Integer> first = new OresFuture<>();
        OresFuture<Integer> second = new OresFuture<>();
        OresFuture<Integer> race = OresFutures.race(List.of(first, second));

        second.completeFromRuntime(9);
        first.completeFromRuntime(1);

        assertEquals(9, AsyncRuntime.await(race));
    }

    @Test
    void raceRejectsEmptyInput() {
        OresFuture<Integer> race = OresFutures.race(List.of());
        assertThrows(IllegalArgumentException.class, () -> AsyncRuntime.await(race));
    }
}
