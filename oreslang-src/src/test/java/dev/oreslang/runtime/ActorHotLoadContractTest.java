package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorHotLoadContractTest {

    private static final ActorHotLoadContract ISO_INT_TO_INT =
            ActorHotLoadContract.isoactorFnc(
                    "worker",
                    List.of(Ast.TypeRef.simple("int")),
                    Ast.TypeRef.simple("int"));

    @Test
    void exactActorAbiIgnoresPrivateImplementationStructure() {
        String first = """
                fnc private_helper(int value) => int {
                  return value + 1;
                }

                pub isoactor fnc worker(int value) => int {
                  return private_helper(value);
                }
                """;

        String second = """
                fnc private_helper(int value) => int {
                  return value * 4;
                }

                fnc another_private_helper(int value) => int {
                  return value - 3;
                }

                pub isoactor fnc worker(int value) => int {
                  return another_private_helper(private_helper(value));
                }
                """;

        ActorHotLoadContract.Verification v1 =
                ISO_INT_TO_INT.verify(OresCompiler.parseAndTypeCheck(first));
        ActorHotLoadContract.Verification v2 =
                ISO_INT_TO_INT.verify(OresCompiler.parseAndTypeCheck(second));

        assertEquals(ISO_INT_TO_INT.abiDigest(), v1.abiDigest());
        assertEquals(v1.abiDigest(), v2.abiDigest(),
                "private implementation changes must not perturb the actor hot-load ABI");
    }

    @Test
    void contractRejectsWrongActorIsolationEvenWhenSignatureOtherwiseMatches() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ISO_INT_TO_INT.verify(OresCompiler.parseAndTypeCheck("""
                        pub actor fnc worker(int value) => int {
                          return value;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("does not match required ABI"));
    }

    @Test
    void contractRejectsParameterAndReturnDrift() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ISO_INT_TO_INT.verify(OresCompiler.parseAndTypeCheck("""
                        pub isoactor fnc worker(String value) => int {
                          return 1;
                        }
                        """)));

        assertThrows(
                IllegalArgumentException.class,
                () -> ISO_INT_TO_INT.verify(OresCompiler.parseAndTypeCheck("""
                        pub isoactor fnc worker(int value) => String {
                          return "changed";
                        }
                        """)));
    }

    @Test
    void contractMismatchIsRejectedBeforeIsolatedContextAllocation() {
        OresVM vm = OresVM.dedicated(ActorRuntime.DispatcherConfig.defaults());
        try (HotReloadManager hot = vm.newHotReloadManager(
                IsolatePolicy.developer(),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.ISOLATED_JIT)) {

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.loadActor(
                            "worker.ores",
                            """
                            pub isoactor fnc worker(String value) => int {
                              return 1;
                            }
                            """,
                            ISO_INT_TO_INT));

            assertTrue(failure.getMessage().contains("does not match required ABI"));
            assertEquals(0, hot.liveGenerations(),
                    "ABI rejection must occur before allocating a spawned-isolate context");
        } finally {
            vm.shutdownNow();
        }
    }

    @Test
    void isoactorContractCannotRunInSharedTrustedJitDomain() {
        OresVM vm = OresVM.dedicated(ActorRuntime.DispatcherConfig.defaults());
        try (HotReloadManager hot = vm.newHotReloadManager(
                IsolatePolicy.developer(),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.TRUSTED_JIT)) {

            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> hot.loadActor(
                            "worker.ores",
                            """
                            pub isoactor fnc worker(int value) => int {
                              return value;
                            }
                            """,
                            ISO_INT_TO_INT));

            assertTrue(failure.getMessage().contains("ISOLATED_JIT"));
            assertEquals(0, hot.liveGenerations(),
                    "domain rejection must occur before a generation is staged");
        } finally {
            vm.shutdownNow();
        }
    }

    @Test
    void untrustedActorRequiresUntrustedExecutionDomainBeforeStaging() {
        ActorHotLoadContract contract = ActorHotLoadContract.untrustedFnc(
                "sandbox",
                List.of(Ast.TypeRef.simple("int")),
                Ast.TypeRef.simple("int"));

        OresVM vm = OresVM.dedicated(ActorRuntime.DispatcherConfig.defaults());
        try (HotReloadManager hot = vm.newHotReloadManager(
                IsolatePolicy.developer(),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.TRUSTED_JIT)) {

            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> hot.loadActor(
                            "sandbox.ores",
                            """
                            pub untrusted actor fnc sandbox(int value) => int {
                              return value;
                            }
                            """,
                            contract));

            assertTrue(failure.getMessage().contains("UNTRUSTED_JIT"));
            assertEquals(0, hot.liveGenerations(),
                    "domain rejection must occur before any generation is staged");
        } finally {
            vm.shutdownNow();
        }
    }

    @Test
    void missingOrAmbiguousPublicEntrypointsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ISO_INT_TO_INT.verify(OresCompiler.parseAndTypeCheck("""
                        isoactor fnc worker(int value) => int {
                          return value;
                        }
                        """)));

        assertThrows(
                IllegalArgumentException.class,
                () -> ISO_INT_TO_INT.verify(OresCompiler.parseAndTypeCheck("""
                        pub isoactor fnc worker(int value) => int {
                          return value;
                        }

                        pub isoactor fnc worker(String value) => String {
                          return value;
                        }
                        """)));
    }
}
