package dev.oreslang;

/** Test-only host hook for proving scheduler cancellation bypasses guest trap boundaries. */
public final class TrapCancellationTestHelper {
    private TrapCancellationTestHelper() { }

    public static void interruptCurrentThread() {
        Thread.currentThread().interrupt();
    }
}
