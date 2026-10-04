package dev.oreslang.runtime;

/** Recoverable error raised when no switch pattern clause matches a value. */
public final class PatternMatchError extends RuntimeException {
    public PatternMatchError(String message) {
        super(message);
    }
}
