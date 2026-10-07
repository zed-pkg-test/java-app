package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

final class SourceActorSpawnTest {
    @Test void rootAndNestedSpawnsDeriveKindAndParentageAndCascadeCancellation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), 32)) {
            for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                ActorRuntime.SourceActor root = runtime.spawnSource(kind, List.of(), (initial, context) -> {
                    assertEquals(kind, context.kind());
                    assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                    return (method, arguments) -> 42L;
                });
                assertEquals(kind, root.kind());
                assertTrue(root.parentId().isEmpty());
                assertEquals(42L, root.request("value", List.of()).get(2, TimeUnit.SECONDS));
                root.stop();
            }
            AtomicReference<ActorRuntime.SourceActor> child = new AtomicReference<>();
            ActorRuntime.SourceActor parent = runtime.spawnSource(ActorRuntime.ActorKind.SHARED, List.of(),
                    (initial, context) -> (method, arguments) -> {
                        ActorRuntime.SourceActor spawned = runtime.spawnSource(ActorRuntime.ActorKind.PRIVATE,
                                List.of(), (state, turn) -> (name, args) -> 42L);
                        child.set(spawned);
                        assertEquals(context.self().id(), spawned.parentId().orElseThrow());
                        return 1L;
                    });
            assertEquals(1L, parent.request("spawn", List.of()).get(2, TimeUnit.SECONDS));
            assertThrows(SecurityException.class, () -> child.get().stop());
            parent.cancel();
            assertFalse(child.get().isAlive());
        }
    }

    @Test void initializationFailureAndCancellationSettleEveryPendingRequest() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), 32)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ActorRuntime.SourceActor failed = runtime.spawnSource(ActorRuntime.ActorKind.PRIVATE, List.of(),
                    (initial, context) -> {
                        entered.countDown();
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        throw new IllegalStateException("initialization failed");
                    });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            OresFuture<Object> request = failed.request("value", List.of());
            release.countDown();
            assertThrows(Exception.class, () -> request.get(2, TimeUnit.SECONDS));
            assertTrue(request.isDone());

            OresFuture<Object> pendingResult = new OresFuture<>();
            ActorRuntime.SourceActor suspended = runtime.spawnSource(ActorRuntime.ActorKind.SHARED, List.of(),
                    (initial, context) -> (method, arguments) -> pendingResult);
            OresFuture<Object> pendingRequest = suspended.request("value", List.of());
            suspended.cancel();
            assertThrows(Exception.class, () -> pendingRequest.get(2, TimeUnit.SECONDS));
            assertTrue(pendingRequest.isDone());
        }
    }

    @Test void outstandingRequestsAreBoundedAndCancellationReleasesTheirSlot() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy policy = new IsolatePolicy(base.capabilities(), base.maxHeapBytes(), 1,
                base.maxWallTime(), false);
        try (ActorRuntime runtime = new ActorRuntime(policy, 4)) {
            CountDownLatch initialized = new CountDownLatch(1);
            CountDownLatch invoked = new CountDownLatch(1);
            OresFuture<Object> result = new OresFuture<>();
            ActorRuntime.SourceActor actor = runtime.spawnSource(ActorRuntime.ActorKind.SHARED, List.of(),
                    (initial, context) -> {
                        initialized.countDown();
                        return (method, arguments) -> { invoked.countDown(); return result; };
                    });
            assertTrue(initialized.await(2, TimeUnit.SECONDS));
            OresFuture<Object> request = actor.request("value", List.of());
            assertTrue(invoked.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> actor.request("value", List.of()));
            request.cancel(false);
            // The producer is cancelled even if request cancellation raced the return from invoke.
            assertThrows(Exception.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(result.isCancelled());
            actor.stop();
        }
    }

    @Test void persistentSourceStateAndResultsAreSubjectToHeapLimits() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy policy = new IsolatePolicy(base.capabilities(), 16L * 1024 * 1024, 8,
                base.maxWallTime(), false);
        try (ActorRuntime runtime = new ActorRuntime(policy, 4)) {
            for (ActorRuntime.ActorKind kind : List.of(ActorRuntime.ActorKind.PRIVATE, ActorRuntime.ActorKind.SHARED)) {
                ActorRuntime.SourceActor actor = runtime.spawnSource(kind, List.of(), (initial, context) ->
                        new ActorRuntime.SourceBehavior() {
                            private String state = "";
                            @Override public Object retainedState() { return state; }
                            @Override public Object invoke(String method, List<Object> args) {
                                state = "x".repeat(9 * 1024 * 1024);
                                return 1L;
                            }
                        });
                OresFuture<Object> request = actor.request("grow", List.of());
                assertThrows(Exception.class, () -> request.get(2, TimeUnit.SECONDS));
                assertTrue(request.isDone());
                actor.stop();
            }
            ActorRuntime.SourceActor actor = runtime.spawnSource(ActorRuntime.ActorKind.PRIVATE, List.of(),
                    (initial, context) -> (method, args) -> "x".repeat(9 * 1024 * 1024));
            OresFuture<Object> oversized = actor.request("large_result", List.of());
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> oversized.get(2, TimeUnit.SECONDS));
            assertTrue(oversized.isDone());
            actor.stop();
        }
    }

    @Test void initialStateAndRequestArgumentsCannotSmuggleLiveCapabilities() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), 32)) {
            ActorRuntime.SourceActor actor = runtime.spawnSource(ActorRuntime.ActorKind.UNTRUSTED, List.of(),
                    (initial, context) -> (method, arguments) -> 1L);
            OresMutex.Local<Long> local = OresMutex.local(1L);
            assertThrows(RuntimeException.class, () -> actor.request("value", List.of(local)));
            assertThrows(RuntimeException.class, () -> runtime.spawnSource(ActorRuntime.ActorKind.PRIVATE,
                    List.of(local), (initial, context) -> (method, arguments) -> 1L));
            actor.stop();
        }
    }
}
