package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCapabilityIsolationTest {

    @Test
    void privateActorStaticallyDeniesReadonlySharingEvenUnderDeveloperPolicy() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                isoactor PrivateWorker {
                  pub fnc attempt_share() => void {
                    val shared = process.share_readonly(arr[1, 2, 3]);
                    stdio.println(shared);
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("ACTOR_SHARE_READONLY"));
    }

    @Test
    void privateActorCannotHideSharedMutexBehindTypeAlias() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                type SharedInt = SharedMutex<int>;

                isoactor PrivateWorker {
                  let SharedInt hidden;
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotHideSharedMutexInsideOrdinaryStoredClass() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class SharedBox as
                  let SharedMutex<int> value;
                end

                isoactor PrivateWorker {
                  let SharedBox hidden;
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotLaunderSharedMemoryThroughOrdinaryHelperFunction() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc build_shared() => void {
                  val shared = SharedMutex.new(1);
                  stdio.println(shared);
                  return;
                }

                isoactor PrivateWorker {
                  pub fnc run() => void {
                    build_shared();
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotLaunderSharedMemoryThroughStaticClassHelper() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class Helpers as
                  pub static fnc build_shared() => void {
                    val shared = SharedMutex.new(1);
                    stdio.println(shared);
                    return;
                  }
                end

                isoactor PrivateWorker {
                  pub fnc run() => void {
                    Helpers.build_shared();
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotCarryObjectWhoseInstanceMethodUsesSharedAuthority() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class Helper as
                  pub use_shared() => void {
                    val shared = process.share_readonly(arr[1, 2, 3]);
                    stdio.println(shared);
                    return;
                  }
                end

                isoactor PrivateWorker {
                  let Helper helper;
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("ACTOR_SHARE_READONLY"));
    }

    @Test
    void sharedActorMayUseTransitiveSharedStateWhenParentPolicyAllowsIt() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                type SharedInt = SharedMutex<int>;

                define class SharedBox as
                  let SharedInt value;
                end

                shared actor SharedWorker {
                  let SharedBox state;
                }
                """));

        assertDoesNotThrow(() ->
                CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void transitiveCapabilityScanHandlesSelfReferentialStoredTypes() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class Node as
                  let Node next;

                  pub identity(Node other) => Node {
                    return other;
                  }
                end

                isoactor PrivateWorker {
                  let Node root;
                }
                """));

        assertDoesNotThrow(() ->
                CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void actorLocalRuntimePolicyCannotBeBypassedByParentContextCapability() throws Exception {
        IsolatePolicy developer = IsolatePolicy.developer();

        try (ActorRuntime runtime = new ActorRuntime(developer)) {
            var privateSharedMemory = runtime.<String>spawnPrivate(
                    factoryContext -> (message, context) ->
                            OresContext.requireEffectiveCapability(
                                    IsolatePolicy.developer(),
                                    IsolatePolicy.Capability.SHARED_MEMORY,
                                    "indirect-helper-shared-memory"));

            var privateReadonlyShare = runtime.<String>spawnPrivate(
                    factoryContext -> (message, context) ->
                            OresContext.requireEffectiveCapability(
                                    IsolatePolicy.developer(),
                                    IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                                    "indirect-helper-readonly-share"));

            var shared = runtime.<String>spawnShared(factoryContext -> (message, context) -> {
                OresContext.requireEffectiveCapability(
                        IsolatePolicy.developer(),
                        IsolatePolicy.Capability.SHARED_MEMORY,
                        "shared-actor-shared-memory");
                OresContext.requireEffectiveCapability(
                        IsolatePolicy.developer(),
                        IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                        "shared-actor-readonly-share");
                context.self().stop();
            });

            privateSharedMemory.send("check");
            privateReadonlyShare.send("check");
            shared.send("check");

            assertTrue(privateSharedMemory.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(privateReadonlyShare.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(shared.awaitTermination(2, TimeUnit.SECONDS));

            assertInstanceOf(SecurityException.class, privateSharedMemory.failure().orElseThrow());
            assertInstanceOf(SecurityException.class, privateReadonlyShare.failure().orElseThrow());
            assertTrue(shared.failure().isEmpty());
        }
    }
    @Test
    void privateActorCannotLaunderSharedMemoryThroughFunctionValue() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc build_shared() => void {
                  val shared = SharedMutex.new(1);
                  stdio.println(shared);
                  return;
                }

                isoactor PrivateWorker {
                  pub fnc run() => void {
                    val callback = build_shared;
                    callback();
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotLaunderReadonlyShareThroughQualifiedFunctionValue() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module helpers
                  pub fnc expose() => void {
                    val shared = process.share_readonly(arr[1, 2, 3]);
                    stdio.println(shared);
                    return;
                  }
                end

                isoactor PrivateWorker {
                  pub fnc run() => void {
                    val callback = helpers.expose;
                    callback();
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("ACTOR_SHARE_READONLY"));
    }


    @Test
    void privateActorCannotLaunderSharedMemoryThroughStaticMethodValue() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class Helpers as
                  pub static fnc build_shared() => void {
                    val shared = SharedMutex.new(1);
                    stdio.println(shared);
                    return;
                  }
                end

                isoactor PrivateWorker {
                  pub fnc run() => void {
                    val callback = Helpers.build_shared;
                    callback();
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }


    @Test
    void invalidOreslangPrivateActorSharingFixtureIsRejected() throws Exception {
        String source = Files.readString(Path.of("examples/private-actor-sharing-invalid.ores"));
        Ast.Program program = TypeChecker.check(Parser.parse(source));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(
                error.getMessage().contains("SHARED_MEMORY")
                        || error.getMessage().contains("ACTOR_SHARE_READONLY"));
    }

    @Test
    void privateActorRuntimeStripsHostEscapeHatchesEvenWhenSupervisorGrantsThem() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy privileged = base.withCapabilities(
                IsolatePolicy.Capability.FFI,
                IsolatePolicy.Capability.NATIVE,
                IsolatePolicy.Capability.REFLECTION,
                IsolatePolicy.Capability.THREAD_CREATE,
                IsolatePolicy.Capability.POLYGLOT);

        AtomicReference<IsolatePolicy> observed = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime(privileged)) {
            var ref = runtime.<String>spawnPrivateTrusted(
                    privileged,
                    factoryContext -> {
                        observed.set(factoryContext.policy());
                        return (message, context) -> {
                            ran.countDown();
                            context.self().stop();
                        };
                    });

            ref.send("check");
            assertTrue(ran.await(2, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }

        IsolatePolicy actorPolicy = observed.get();
        assertNotNull(actorPolicy);
        assertFalse(actorPolicy.allows(IsolatePolicy.Capability.SHARED_MEMORY));
        assertFalse(actorPolicy.allows(IsolatePolicy.Capability.ACTOR_SHARE_READONLY));
        assertFalse(actorPolicy.allows(IsolatePolicy.Capability.GC_CONTROL));
        assertFalse(actorPolicy.allows(IsolatePolicy.Capability.FFI));
        assertFalse(actorPolicy.allows(IsolatePolicy.Capability.NATIVE));
        assertFalse(actorPolicy.allows(IsolatePolicy.Capability.REFLECTION));
        assertFalse(actorPolicy.allows(IsolatePolicy.Capability.THREAD_CREATE));
        assertFalse(actorPolicy.allows(IsolatePolicy.Capability.POLYGLOT));
    }

    @Test
    void privateActorStaticPolicyDeniesFfiPolyglotAndThreadEscapeHatches() {
        IsolatePolicy privileged = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FFI,
                IsolatePolicy.Capability.POLYGLOT,
                IsolatePolicy.Capability.THREAD_CREATE);

        for (String source : java.util.List.of(
                """
                isoactor PrivateWorker {
                  pub fnc run() => void {
                    ffi.call();
                    return;
                  }
                }
                """,
                """
                isoactor PrivateWorker {
                  pub fnc run() => void {
                    polyglot.eval();
                    return;
                  }
                }
                """,
                """
                isoactor PrivateWorker {
                  pub fnc run() => void {
                    thread.spawn();
                    return;
                  }
                }
                """)) {
            Ast.Program program = Parser.parse(source);
            assertThrows(
                    SecurityException.class,
                    () -> CapabilityChecker.check(program, privileged));
        }
    }


    @Test
    void actorRuntimeThreadLocalRuntimeAccessorsAreKernelOnly() throws Exception {
        var actorAccessor = ActorRuntime.class.getDeclaredMethod("currentActorRuntime");
        var rootAccessor = ActorRuntime.class.getDeclaredMethod("currentRootRuntime");

        assertFalse(java.lang.reflect.Modifier.isPublic(actorAccessor.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(rootAccessor.getModifiers()));
        assertEquals(ActorRuntime.class, actorAccessor.getReturnType());
        assertEquals(ActorRuntime.class, rootAccessor.getReturnType());
    }


    @Test
    void sharedReadonlyBytePayloadIsCopiedAndCannotBeMutatedThroughItsView() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            byte[] source = new byte[]{1, 2, 3};
            ActorRuntime.Shared<Object> shared = runtime.shareReadonly((Object) source);

            source[0] = 99;

            Object frozen = shared.value();
            assertEquals(java.util.List.of((byte) 1, (byte) 2, (byte) 3), frozen);
            assertInstanceOf(java.util.List.class, frozen);
            @SuppressWarnings("unchecked")
            java.util.List<Object> readonly = (java.util.List<Object>) frozen;
            assertThrows(UnsupportedOperationException.class, () -> readonly.set(0, (byte) 7));
        }
    }



}
