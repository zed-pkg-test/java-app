package dev.oreslang.runtime;

import java.lang.ref.Cleaner;
import java.lang.ref.Reference;

/**
 * Opaque JNI-backed state machine for OresFuture settlement arbitration.
 *
 * <p>This class intentionally owns no guest payload. Java/Truffle objects remain
 * bridge values in the interpreter, while the single-winner settlement state is
 * owned by the native Ores runtime. The handle is never exposed to guest code.
 *
 * <p>The Cleaner is resource-lifecycle plumbing only; it does not schedule or
 * execute Oreslang guest work.</p>
 */
final class NativeFutureState {
    static final int PENDING = 0;
    static final int SETTLING = 1;
    static final int SUCCESS = 2;
    static final int FAILURE = 3;
    static final int CANCELLED = 4;

    private static final Cleaner CLEANER = Cleaner.create();

    private static final class NativeAllocation implements Runnable {
        private long handle;

        NativeAllocation(long handle) {
            this.handle = handle;
        }

        long handle() {
            long current = handle;
            if (current == 0L) {
                throw new IllegalStateException("native Future state is closed");
            }
            return current;
        }

        @Override
        public synchronized void run() {
            long current = handle;
            if (current == 0L) return;
            handle = 0L;
            nativeDestroy(current);
        }
    }

    private final NativeAllocation allocation;
    @SuppressWarnings("unused")
    private final Cleaner.Cleanable cleanable;

    NativeFutureState() {
        NativeCarrierExecutor.ensureNativeLibraryLoaded();
        long handle = nativeCreate();
        if (handle == 0L) {
            throw new IllegalStateException("native Future state returned a null handle");
        }
        allocation = new NativeAllocation(handle);
        cleanable = CLEANER.register(this, allocation);
    }

    boolean tryBeginSettlement() {
        boolean won = nativeTryBeginSettlement(allocation.handle());
        Reference.reachabilityFence(this);
        return won;
    }

    void publish(int terminalState) {
        if (terminalState != SUCCESS
                && terminalState != FAILURE
                && terminalState != CANCELLED) {
            throw new IllegalArgumentException(
                    "native Future terminal state must be success/failure/cancelled");
        }
        nativePublish(allocation.handle(), terminalState);
        Reference.reachabilityFence(this);
    }

    int state() {
        int observed = nativeState(allocation.handle());
        Reference.reachabilityFence(this);
        return observed;
    }

    boolean isDone() {
        return state() >= SUCCESS;
    }

    boolean isCancelled() {
        return state() == CANCELLED;
    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long handle);
    private static native boolean nativeTryBeginSettlement(long handle);
    private static native void nativePublish(long handle, int terminalState);
    private static native int nativeState(long handle);
}
