package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActorHeapAdmissionTest {
    private static final ActorHeapAdmission.OwnershipProof COMPLETE =
            new ActorHeapAdmission.OwnershipProof(
                    true, true, true, true, true, true, true, true);

    @Test
    void ordinaryJavaAndDirectBufferActorsAreNeverStrictGcFree() {
        for (var backing : new ActorHeapAdmission.Backing[] {
                ActorHeapAdmission.Backing.JVM_OBJECT_HEAP,
                ActorHeapAdmission.Backing.JVM_DIRECT_BYTE_BUFFER,
                ActorHeapAdmission.Backing.NATIVE_IMAGE_JAVA_ISOLATE }) {
            assertThrows(IllegalStateException.class,
                    () -> ActorHeapAdmission.requireZeroGuestTracing(backing, COMPLETE),
                    backing.name());
        }
    }

    @Test
    void nativeRegionsAreOnlyEligibleAfterEveryOwnershipAndQuiescenceProof() {
        for (var backend : new ActorHeapAdmission.Backing[] {
                ActorHeapAdmission.Backing.EXPLICIT_NATIVE_REGION,
                ActorHeapAdmission.Backing.LLVM_NATIVE_ARENA }) {
            assertDoesNotThrow(() -> ActorHeapAdmission.requireZeroGuestTracing(
                    backend, COMPLETE));
            assertThrows(IllegalStateException.class, () ->
                    ActorHeapAdmission.requireZeroGuestTracing(
                            backend, ActorHeapAdmission.OwnershipProof.unproven()));
            for (int failed = 0; failed < 8; ++failed) {
                boolean[] proof = {true,true,true,true,true,true,true,true};
                proof[failed] = false;
                var missing = new ActorHeapAdmission.OwnershipProof(
                        proof[0], proof[1], proof[2], proof[3], proof[4], proof[5],
                        proof[6], proof[7]);
                assertThrows(IllegalStateException.class,
                        () -> ActorHeapAdmission.requireZeroGuestTracing(backend, missing));
            }
        }
    }

    @Test
    void jvmModesDoNotPretendHostGcCanBeDisabled() {
        assertDoesNotThrow(() -> ActorHeapAdmission.requireSupportedJvmMode("hybrid"));
        assertDoesNotThrow(() -> ActorHeapAdmission.requireSupportedJvmMode("OWNERship-FIRST"));
        for (String mode : new String[] {"strict-no-gc", "zero-gc", "manual-only"}) {
            var denial = assertThrows(IllegalStateException.class,
                    () -> ActorHeapAdmission.requireSupportedJvmMode(mode));
            assertTrue(denial.getMessage().contains("remain managed"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ActorHeapAdmission.requireSupportedJvmMode("typo"));
    }

    @Test
    void strictJVMModeIsRejectedBeforeVmControlCarrierStartup() {
        final String key = "ores.runtime.gc.mode";
        String old = System.getProperty(key);
        try {
            System.setProperty(key, "strict-no-gc");
            assertThrows(IllegalStateException.class, () ->
                    OresVM.create(Runnable::run));
        } finally {
            if (old == null) System.clearProperty(key);
            else System.setProperty(key, old);
        }
    }
}
