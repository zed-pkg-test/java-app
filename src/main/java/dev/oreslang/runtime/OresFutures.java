package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Structured combinators for language-level Future<T> values.
 *
 * Futures.all is the Oreslang equivalent of Promise.all: it preserves input
 * order, completes when every child completes, and propagates aggregate
 * cancellation to unfinished children.
 */
public final class OresFutures {
    private OresFutures() { }

    public record Settled<T>(T value, Throwable error) {
        public boolean ok() {
            return error == null;
        }
    }

    public static <T> OresFuture<List<T>> all(
            List<? extends CompletionStage<? extends T>> stages) {
        Objects.requireNonNull(stages, "stages");
        List<OresFuture<T>> children = new ArrayList<>(stages.size());
        for (CompletionStage<? extends T> stage : stages) {
            children.add(OresFuture.from(Objects.requireNonNull(stage, "future")));
        }

        OresFuture<List<T>> result = new OresFuture<>(
                () -> children.forEach(child -> child.cancel(true)));
        if (children.isEmpty()) {
            result.completeFromRuntime(List.of());
            return result;
        }

        CompletableFuture<?>[] array = children.toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(array).whenComplete((ignored, failure) -> {
            if (failure != null) {
                result.failFromRuntime(OresFuture.unwrap(failure));
                return;
            }
            ArrayList<T> values = new ArrayList<>(children.size());
            for (OresFuture<T> child : children) {
                values.add(child.join());
            }
            result.completeFromRuntime(List.copyOf(values));
        });
        return result;
    }

    public static <T> OresFuture<T> race(
            List<? extends CompletionStage<? extends T>> stages) {
        Objects.requireNonNull(stages, "stages");
        if (stages.isEmpty()) {
            return OresFuture.failed(
                    new IllegalArgumentException("Futures.race requires at least one future"));
        }

        List<OresFuture<T>> children = new ArrayList<>(stages.size());
        for (CompletionStage<? extends T> stage : stages) {
            children.add(OresFuture.from(Objects.requireNonNull(stage, "future")));
        }
        OresFuture<T> result = new OresFuture<>(
                () -> children.forEach(child -> child.cancel(true)));

        for (OresFuture<T> child : children) {
            child.whenComplete((value, failure) -> {
                if (result.isDone()) return;
                if (failure == null) {
                    result.completeFromRuntime(value);
                } else {
                    result.failFromRuntime(OresFuture.unwrap(failure));
                }
            });
        }
        return result;
    }

    public static <T> OresFuture<List<Settled<T>>> allSettled(
            List<? extends CompletionStage<? extends T>> stages) {
        Objects.requireNonNull(stages, "stages");
        List<OresFuture<T>> children = new ArrayList<>(stages.size());
        for (CompletionStage<? extends T> stage : stages) {
            children.add(OresFuture.from(Objects.requireNonNull(stage, "future")));
        }

        OresFuture<List<Settled<T>>> result = new OresFuture<>(
                () -> children.forEach(child -> child.cancel(true)));
        if (children.isEmpty()) {
            result.completeFromRuntime(List.of());
            return result;
        }

        CompletableFuture<?>[] normalized = children.stream()
                .map(child -> child.handle((value, failure) -> null))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(normalized).whenComplete((ignored, impossible) -> {
            ArrayList<Settled<T>> settled = new ArrayList<>(children.size());
            for (OresFuture<T> child : children) {
                try {
                    settled.add(new Settled<>(child.join(), null));
                } catch (Throwable failure) {
                    settled.add(new Settled<>(null, OresFuture.unwrap(failure)));
                }
            }
            result.completeFromRuntime(List.copyOf(settled));
        });
        return result;
    }
}
