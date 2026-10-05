package dev.oreslang.runtime;

/**
 * Runtime projection for values that participate in Oreslang `await`.
 *
 * <p>The source-level protocol is the built-in `Awaitable<T>` interface with
 * a compiler-known `getAwait()` hook. Runtime implementations return the
 * concrete OresFuture that controls suspension. User source classes are
 * validated against the same contract by the type checker.</p>
 */
public interface OresAwaitable<T> {
    OresFuture<T> getAwait();
}
