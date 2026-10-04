package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Structured combinators for language-level Future<T> values.
 *
 * <p>The public language primitive is {@link OresFuture}. CompletionStage is
 * accepted only as a host-interop input and is immediately normalized into an
 * OresFuture so dependent Oreslang work never inherits a host callback
 * execution policy.</p>
 */
public final class OresFutures {
    private OresFutures() { }

    public record Settled<T>(T value, Throwable error, boolean cancelled) {
        public Settled(T value, Throwable error) {
            this(value, error, false);
        }

        public boolean ok() {
            return error == null && !cancelled;
        }
    }

    public static <T> OresFuture<List<T>> all(List<?> awaitables) {
        List<OresFuture<T>> children = normalize(awaitables, "Futures.all");
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration> registrations =
                new AtomicReferenceArray<>(children.size());
        OresFuture<List<T>> result = new OresFuture<>(() -> {
            detachRegistrations(registrations);
            children.forEach(child -> child.cancel(true));
        });
        if (children.isEmpty()) {
            result.completeFromRuntime(List.of());
            return result;
        }

        AtomicReferenceArray<Object> values = new AtomicReferenceArray<>(children.size());
        AtomicInteger remaining = new AtomicInteger(children.size());

        for (int index = 0; index < children.size(); index++) {
            int slot = index;
            OresFuture.RuntimeWaiterRegistration registration =
                    children.get(index).whenCompleteRuntime((value, failure) -> {
                        registrations.set(slot, null);
                        if (result.isDone()) return;
                        if (failure != null) {
                            Throwable terminal = OresFuture.unwrap(failure);
                            boolean won = children.get(slot).isCancelled()
                                    ? result.cancelFromRuntime(asCancellation(terminal))
                                    : result.failFromRuntime(terminal);
                            if (won) detachRegistrations(registrations);
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
                            if (result.completeFromRuntime(
                                    Collections.unmodifiableList(ordered))) {
                                detachRegistrations(registrations);
                            }
                        }
                    });
            registrations.set(slot, registration);
            if (result.isDone()) {
                registration.cancel();
                registrations.compareAndSet(slot, registration, null);
            }
        }
        return result;
    }

    public static <T> OresFuture<T> race(List<?> awaitables) {
        List<OresFuture<T>> children = normalize(awaitables, "Futures.race");
        if (children.isEmpty()) {
            return OresFuture.failed(
                    new IllegalArgumentException("Futures.race requires at least one future"));
        }

        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration> registrations =
                new AtomicReferenceArray<>(children.size());
        OresFuture<T> result = new OresFuture<>(() -> {
            detachRegistrations(registrations);
            children.forEach(child -> child.cancel(true));
        });
        for (int index = 0; index < children.size(); index++) {
            int slot = index;
            OresFuture.RuntimeWaiterRegistration registration =
                    children.get(index).whenCompleteRuntime((value, failure) -> {
                        registrations.set(slot, null);
                        boolean won;
                        if (failure == null) {
                            won = result.completeFromRuntime(value);
                        } else {
                            Throwable terminal = OresFuture.unwrap(failure);
                            won = children.get(slot).isCancelled()
                                    ? result.cancelFromRuntime(asCancellation(terminal))
                                    : result.failFromRuntime(terminal);
                        }
                        if (won) detachRegistrations(registrations);
                    });
            registrations.set(slot, registration);
            if (result.isDone()) {
                registration.cancel();
                registrations.compareAndSet(slot, registration, null);
            }
        }
        return result;
    }

    public static <T> OresFuture<List<Settled<T>>> allSettled(List<?> awaitables) {
        List<OresFuture<T>> children = normalize(awaitables, "Futures.all_settled");
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration> registrations =
                new AtomicReferenceArray<>(children.size());
        OresFuture<List<Settled<T>>> result = new OresFuture<>(() -> {
            detachRegistrations(registrations);
            children.forEach(child -> child.cancel(true));
        });
        if (children.isEmpty()) {
            result.completeFromRuntime(List.of());
            return result;
        }

        AtomicReferenceArray<Settled<T>> settled =
                new AtomicReferenceArray<>(children.size());
        AtomicInteger remaining = new AtomicInteger(children.size());

        for (int index = 0; index < children.size(); index++) {
            int slot = index;
            OresFuture.RuntimeWaiterRegistration registration =
                    children.get(index).whenCompleteRuntime((value, failure) -> {
                        registrations.set(slot, null);
                        settled.set(
                                slot,
                                new Settled<>(
                                        failure == null ? value : null,
                                        failure == null ? null : OresFuture.unwrap(failure),
                                        children.get(slot).isCancelled()));
                        if (remaining.decrementAndGet() == 0) {
                            ArrayList<Settled<T>> ordered =
                                    new ArrayList<>(children.size());
                            for (int i = 0; i < children.size(); i++) {
                                ordered.add(settled.get(i));
                            }
                            if (result.completeFromRuntime(List.copyOf(ordered))) {
                                detachRegistrations(registrations);
                            }
                        }
                    });
            registrations.set(slot, registration);
            if (result.isDone()) {
                registration.cancel();
                registrations.compareAndSet(slot, registration, null);
            }
        }
        return result;
    }

    private static CancellationException asCancellation(Throwable failure) {
        if (failure instanceof CancellationException cancellation) {
            return cancellation;
        }
        CancellationException cancellation =
                new CancellationException("child Future was cancelled");
        cancellation.initCause(failure);
        return cancellation;
    }

    private static void detachRegistrations(
            AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration> registrations) {
        for (int i = 0; i < registrations.length(); i++) {
            OresFuture.RuntimeWaiterRegistration registration =
                    registrations.getAndSet(i, null);
            if (registration != null) registration.cancel();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<OresFuture<T>> normalize(
            List<?> awaitables,
            String operation) {
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
                        operation + " expects every list element to be a Future");
            }
        }
        return List.copyOf(children);
    }
}
