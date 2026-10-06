package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class SharedPrivateActorIsolationProofTest {

    @Test
    void sharedActorReadsPublishedStateWhileAllMutationRemainsActorOwned() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.SyncCell<Integer> sharedCell = runtime.syncCell(10);
            var frozen = runtime.shareReadonly(List.of("alpha", "beta"));

            CountDownLatch sharedTurn = new CountDownLatch(1);
            AtomicReference<Throwable> sharedFailure = new AtomicReference<>();

            var shared = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    assertEquals(ActorRuntime.ActorKind.SHARED, context.kind());
                    assertTrue(context.privateMemory().isEmpty());

                    // Read-only shared access is allowed.
                    assertEquals(10, sharedCell.snapshot());
                    assertEquals(List.of("alpha", "beta"), frozen.value());

                    // Direct mutation of runtime-shared state is not actor authority.
                    assertThrows(
                            SecurityException.class,
                            () -> sharedCell.update(value -> value + 1));
                    assertThrows(SecurityException.class, sharedCell::close);
                } catch (Throwable failure) {
                    sharedFailure.set(failure);
                } finally {
                    sharedTurn.countDown();
                    context.self().stop();
                }
            });

            shared.send("observe");
            assertTrue(sharedTurn.await(2, TimeUnit.SECONDS));
            assertTrue(shared.awaitTermination(2, TimeUnit.SECONDS));
            assertNull(sharedFailure.get());
            assertTrue(shared.failure().isEmpty());
            assertEquals(10, sharedCell.snapshot());

            // Supervisor/runtime code may publish the next immutable version.
            sharedCell.update(value -> value + 1);
            assertEquals(11, sharedCell.snapshot());

            // An isolated actor may not directly dereference the live shared cell.
            var isolated = runtime.<Object>spawnPrivate(factoryContext -> {
                assertEquals(ActorRuntime.ActorKind.PRIVATE, factoryContext.kind());
                assertTrue(factoryContext.privateMemory().isPresent());
                return (message, context) -> context.self().stop();
            });

            assertThrows(IllegalArgumentException.class, () -> isolated.send(sharedCell));
            isolated.send("ordinary-value");
            assertTrue(isolated.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(isolated.failure().isEmpty());

            // Compiler-facing private construction is capture-free.
            ArrayList<String> mutableHostState = new ArrayList<>(List.of("host"));
            SecurityException capturedState = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnPrivate(factoryContext -> {
                        mutableHostState.add("should-never-run");
                        return (message, context) -> context.self().stop();
                    }));
            assertTrue(capturedState.getMessage().contains("stateless"));
            assertEquals(List.of("host"), mutableHostState);

            // Private-memory blocks remain actor-confined even through trusted host setup.
            AtomicReference<ActorRuntime.PrivateMemoryBlock> leaked = new AtomicReference<>();
            CountDownLatch allocated = new CountDownLatch(1);
            CountDownLatch ownerVerified = new CountDownLatch(1);

            var memoryOwner = runtime.<String>spawnPrivateTrusted(factoryContext -> {
                ActorRuntime.PrivateMemoryBlock block =
                        factoryContext.privateMemory().orElseThrow().allocatePrivateBytes(32);
                block.writeByte(0, (byte) 42);
                leaked.set(block);
                allocated.countDown();

                return (message, context) -> {
                    assertEquals(42, block.readByte(0));
                    block.writeByte(1, (byte) 7);
                    assertEquals(7, block.readByte(1));
                    ownerVerified.countDown();
                };
            });

            memoryOwner.send("verify-owner-access");
            assertTrue(allocated.await(2, TimeUnit.SECONDS));
            assertNotNull(leaked.get());
            assertThrows(IllegalStateException.class, () -> leaked.get().readByte(0));
            assertTrue(ownerVerified.await(2, TimeUnit.SECONDS));

            memoryOwner.stop();
            assertTrue(memoryOwner.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(memoryOwner.failure().isEmpty());
            assertTrue(leaked.get().closed());
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }
}
