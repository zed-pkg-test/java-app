package dev.oreslang.runtime;

import org.graalvm.polyglot.Engine;

/**
 * Process-lifetime Graal engine used by trusted and private Oreslang contexts.
 *
 * A single explicit Engine is the Graal sharing boundary for parsed ASTs and
 * optimized code. Actor heaps and OresContext instances remain independent.
 * Adversarial/untrusted contexts intentionally do not use this engine.
 */
public final class ProcessCodeEngine {
    private ProcessCodeEngine() { }

    private static final class Holder {
        private static final Engine ENGINE = Engine.newBuilder().build();
    }

    public static Engine shared() {
        return Holder.ENGINE;
    }
}
