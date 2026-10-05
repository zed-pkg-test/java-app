package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Structured combinators for Oreslang-owned Future values. */
public final class OresFutures {
    private OresFutures() { }

    public static <T> OresFuture<List<T>> all(List<?> awaitables) {
        List<OresFuture<T>> children = normalize(awaitables, "Future.all");
        OresFuture<List<T>> result = new OresFuture<>();
        if (children.isEmpty()) {
            result.completeFromRuntime(List.of());
            return result;
        }

        AtomicReferenceArray<Object> values = new AtomicReferenceArray<>(children.size());
        AtomicInteger remaining = new AtomicInteger(children.size());
        for (int index = 0; index < children.size(); index++) {
            int slot = index;
            children.get(index).whenCompleteRuntime((value, failure) -> {
                if (result.isDone()) return;
                if (failure != null) {
                    result.failFromRuntime(failure);
                    return;
                }
                values.set(slot, value);
                if (remaining.decrementAndGet() == 0) {
                    ArrayList<T> ordered = new ArrayList<>(children.size());
                    for (int i = 0; i < children.size(); i++) {
                        @SuppressWarnings("unchecked")
                        T item = (T) values.get(i);
                        ordered.add(item);
                    }
                    result.completeFromRuntime(List.copyOf(ordered));
                }
            });
        }
        return result;
    }

    public static <T> OresFuture<T> race(List<?> awaitables) {
        List<OresFuture<T>> children = normalize(awaitables, "Future.race");
        if (children.isEmpty()) {
            return OresFuture.failed(
                    new IllegalArgumentException("Future.race requires at least one future"));
        }

        OresFuture<T> result = new OresFuture<>();
        for (OresFuture<T> child : children) {
            child.whenCompleteRuntime((value, failure) -> {
                if (failure == null) result.completeFromRuntime(value);
                else result.failFromRuntime(failure);
            });
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<OresFuture<T>> normalize(List<?> awaitables, String operation) {
        Objects.requireNonNull(awaitables, "awaitables");
        ArrayList<OresFuture<T>> children = new ArrayList<>(awaitables.size());
        for (Object awaitable : awaitables) {
            Objects.requireNonNull(awaitable, "future");
            if (awaitable instanceof OresFuture<?> ores) {
                children.add((OresFuture<T>) ores);
            } else if (awaitable instanceof CompletionStage<?> stage) {
                children.add(OresFuture.from((CompletionStage<? extends T>) stage));
            } else {
                throw new IllegalArgumentException(
                        operation + " expects every element to be a Future");
            }
        }
        return List.copyOf(children);
    }
}
