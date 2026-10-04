package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.MemorySlotSingletonRegistry;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.runtime.ProcessGlobalRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ProcessIdentityIsolationTest {

    @Test
    void untrustedMailboxAcceptsOnlyExplicitlyExportedSymbols() throws Exception {
        IsolatePolicy trusted = IsolatePolicy.developer();
        IsolatePolicy untrusted = IsolatePolicy.strictFaas();
        String suffix = UUID.randomUUID().toString();
        OresSymbol hidden = OresSymbol.process("hidden-" + suffix, trusted);
        OresSymbol visible = OresSymbol.process("visible-" + suffix, trusted);

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Object> observed = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(trusted)) {
            ActorRuntime.ActorRef<Object> target = runtime.spawnPrivate(
                    untrusted,
                    factoryContext -> (message, turn) -> {
                        observed.set(message);
                        received.countDown();
                        turn.self().stop();
                    });

            assertThrows(SecurityException.class, () -> target.send(hidden));

            OresSymbol.exportToUntrusted(visible, trusted);
            try {
                target.send(List.of("status", visible));
                assertTrue(received.await(2, TimeUnit.SECONDS));
                List<?> delivered = assertInstanceOf(List.class, observed.get());
                assertEquals(visible, delivered.get(1));
            } finally {
                OresSymbol.revokeFromUntrusted(visible, trusted);
            }
        }
    }

    @Test
    void untrustedCodeCanResolveExportedSymbolButCannotInternNewOne() {
        IsolatePolicy trusted = IsolatePolicy.developer();
        IsolatePolicy untrusted = IsolatePolicy.strictFaas();
        String suffix = UUID.randomUUID().toString();
        OresSymbol exported = OresSymbol.process("error-" + suffix, trusted);
        OresSymbol.exportToUntrusted(exported, trusted);

        try {
            assertSame(exported, OresSymbol.process(exported.key(), untrusted));
            assertThrows(
                    SecurityException.class,
                    () -> OresSymbol.process("attacker-created-" + suffix, untrusted));
            assertThrows(
                    SecurityException.class,
                    () -> OresSymbol.exportToUntrusted(exported, untrusted));
        } finally {
            OresSymbol.revokeFromUntrusted(exported, trusted);
        }
    }

    @Test
    void untrustedMailboxRejectsProcessGlobalReferencesEvenWhenNested() throws Exception {
        IsolatePolicy trusted = IsolatePolicy.developer();
        String key = "global-" + UUID.randomUUID();
        ProcessGlobalRegistry.Handle<ArrayList<Integer>> global =
                ProcessGlobalRegistry.getOrCreate(
                        key,
                        trusted,
                        ArrayList::new);

        try (ActorRuntime runtime = new ActorRuntime(trusted)) {
            ActorRuntime.ActorRef<Object> target = runtime.spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, turn) -> { });

            SecurityException direct =
                    assertThrows(SecurityException.class, () -> target.send(global));
            assertTrue(direct.getMessage().contains("global"));

            SecurityException nested = assertThrows(
                    SecurityException.class,
                    () -> target.send(List.of("data", List.of(global))));
            assertTrue(nested.getMessage().contains("global"));
        } finally {
            assertTrue(ProcessGlobalRegistry.collect(key, trusted)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void adversarialActorsCanNeverUseSharedActorMemoryDomain() {
        IsolatePolicy adversarialShared = IsolatePolicy.strictFaas()
                .withCapabilities(IsolatePolicy.Capability.SHARED_MEMORY);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnShared(
                            adversarialShared,
                            factoryContext -> (message, turn) -> { }));
            assertTrue(denied.getMessage().contains("private isolated"));
        }
    }

    @Test
    void singletonIsUniquePerMemorySlotAndSharedActorsUseMainSlot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> main =
                    runtime.singletons().getOrCreate("counter", ArrayList::new);
            UUID mainId = main.instanceId();

            CountDownLatch sharedDone = new CountDownLatch(1);
            AtomicReference<UUID> sharedId = new AtomicReference<>();
            ActorRuntime.ActorRef<String> shared = runtime.spawnShared(
                    factoryContext -> (message, turn) -> {
                        MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> local =
                                turn.runtime().singletons().getOrCreate(
                                        "counter",
                                        ArrayList::new);
                        sharedId.set(local.instanceId());
                        local.call(List.of(7), (state, args) -> {
                            state.add((Integer) args.getFirst());
                            return state.size();
                        });
                        sharedDone.countDown();
                        turn.self().stop();
                    });
            shared.send("go");
            assertTrue(sharedDone.await(2, TimeUnit.SECONDS));
            assertEquals(mainId, sharedId.get());

            assertEquals(
                    1,
                    main.call(List.of(), (state, args) -> state.size()));

            AtomicReference<UUID> firstPrivateId = new AtomicReference<>();
            CountDownLatch privateDone = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> firstPrivate = runtime.spawnPrivate(
                    factoryContext -> (message, turn) -> {
                        firstPrivateId.set(
                                turn.runtime().singletons()
                                        .getOrCreate("counter", ArrayList::new)
                                        .instanceId());
                        privateDone.countDown();
                        turn.self().stop();
                    });
            firstPrivate.send("go");
            assertTrue(privateDone.await(2, TimeUnit.SECONDS));
            assertNotEquals(mainId, firstPrivateId.get());
            assertTrue(firstPrivate.awaitTermination(2, TimeUnit.SECONDS));

            AtomicReference<UUID> secondPrivateId = new AtomicReference<>();
            CountDownLatch secondDone = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> secondPrivate = runtime.spawnPrivate(
                    factoryContext -> (message, turn) -> {
                        secondPrivateId.set(
                                turn.runtime().singletons()
                                        .getOrCreate("counter", ArrayList::new)
                                        .instanceId());
                        secondDone.countDown();
                        turn.self().stop();
                    });
            secondPrivate.send("go");
            assertTrue(secondDone.await(2, TimeUnit.SECONDS));
            assertNotEquals(firstPrivateId.get(), secondPrivateId.get());
        }
    }

    @Test
    void untrustedActorCannotCreateMemorySlotSingletonEvenThroughRuntimeApi() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            ActorRuntime.ActorRef<String> sandbox = runtime.spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, turn) -> {
                        try {
                            turn.runtime().singletons().getOrCreate(
                                    "forbidden",
                                    ArrayList::new);
                        } catch (Throwable failure) {
                            observed.set(failure);
                        } finally {
                            done.countDown();
                            turn.self().stop();
                        }
                    });

            sandbox.send("go");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, observed.get());
        }
    }

    @Test
    void singletonHandleCannotBeUsedFromAnotherMemorySlot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> main =
                    runtime.singletons().getOrCreate("slot-bound", ArrayList::new);

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();
            ActorRuntime.ActorRef<String> privateActor = runtime.spawnPrivateTrusted(
                    factoryContext -> (message, turn) -> {
                        try {
                            main.call(List.of(), (state, args) -> state.size());
                        } catch (Throwable failure) {
                            observed.set(failure);
                        } finally {
                            done.countDown();
                            turn.self().stop();
                        }
                    });

            privateActor.send("go");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, observed.get());
        }
    }
    @Test
    void untrustedMailboxRejectsActorRefsAsLiveAuthority() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<String> trustedTarget = runtime.spawnPrivateTrusted(
                    factoryContext -> (message, turn) -> { });
            ActorRuntime.ActorRef<Object> sandbox = runtime.spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, turn) -> { });

            SecurityException direct = assertThrows(
                    SecurityException.class,
                    () -> sandbox.send(trustedTarget));
            assertTrue(direct.getMessage().contains("actor"));

            assertThrows(
                    SecurityException.class,
                    () -> sandbox.send(List.of("nested", List.of(trustedTarget))));
        }
    }

    @Test
    void wellKnownSymbolsStillRequireExplicitSandboxExport() {
        IsolatePolicy trusted = IsolatePolicy.developer();
        IsolatePolicy untrusted = IsolatePolicy.strictFaas();
        OresSymbol iterator = OresSymbol.wellKnown("iterator", trusted);

        if (iterator.visibleToUntrusted()) {
            OresSymbol.revokeFromUntrusted(iterator, trusted);
        }
        assertThrows(
                SecurityException.class,
                () -> OresSymbol.wellKnown("iterator", untrusted));

        OresSymbol.exportToUntrusted(iterator, trusted);
        try {
            assertEquals(iterator, OresSymbol.wellKnown("iterator", untrusted));
        } finally {
            OresSymbol.revokeFromUntrusted(iterator, trusted);
        }
    }

    @Test
    void singletonRejectsReentrantCallIntoSameCell() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> singleton =
                    runtime.singletons().getOrCreate("reentrant", ArrayList::new);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> singleton.call(
                            List.of(),
                            (state, args) -> singleton.call(
                                    List.of(),
                                    (sameState, sameArgs) -> sameState.size())));
            assertTrue(failure.getMessage().contains("reentrant"));
        }
    }

    @Test
    void singletonWaitGraphRejectsCrossCellDeadlockCycle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> left =
                    runtime.singletons().getOrCreate("cycle-left", ArrayList::new);
            MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> right =
                    runtime.singletons().getOrCreate("cycle-right", ArrayList::new);
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Object> a = executor.submit(() -> left.call(
                        List.of(),
                        (state, args) -> {
                            barrier.await(2, TimeUnit.SECONDS);
                            return right.call(
                                    List.of(),
                                    (rightState, rightArgs) -> rightState.size());
                        }));
                Future<Object> b = executor.submit(() -> right.call(
                        List.of(),
                        (state, args) -> {
                            barrier.await(2, TimeUnit.SECONDS);
                            return left.call(
                                    List.of(),
                                    (leftState, leftArgs) -> leftState.size());
                        }));

                Throwable aFailure = futureFailure(a);
                Throwable bFailure = futureFailure(b);
                assertTrue(
                        containsMessage(aFailure, "cycle")
                                || containsMessage(bFailure, "cycle"),
                        "at least one side must reject the lock cycle");
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    private static Throwable futureFailure(Future<?> future) throws Exception {
        try {
            future.get(3, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
    }

    private static boolean containsMessage(Throwable failure, String fragment) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null
                    && current.getMessage().toLowerCase().contains(fragment.toLowerCase())) {
                return true;
            }
        }
        return false;
    }


}
