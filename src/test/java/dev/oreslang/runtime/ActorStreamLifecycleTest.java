package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorStreamLifecycleTest {
    private ActorRuntime runtime() {
        return new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16, 64));
    }
    private static <T> T get(OresFuture<T> future) throws Exception {
        return future.get(3, TimeUnit.SECONDS);
    }
    private static final class Pending extends OresObservable<Integer> {
        final OresFuture<OresNotification<Integer>> item = new OresFuture<>();
        final CountDownLatch pulled = new CountDownLatch(1);
        final AtomicReference<Object> domain = new AtomicReference<>();
        final AtomicReference<Object> cancelDomain = new AtomicReference<>();
        final AtomicInteger cancellations = new AtomicInteger();
        @Override public OresSubscription<Integer> subscribe() {
            domain.set(ActorRuntime.currentActorExecutionDomain());
            return new OresSubscription<>() {
                @Override protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                    assertSame(domain.get(), ActorRuntime.currentActorExecutionDomain());
                    pulled.countDown();
                    return item;
                }
                @Override protected void cancelFromRuntime() {
                    cancelDomain.set(ActorRuntime.currentActorExecutionDomain());
                    cancellations.incrementAndGet();
                }
            };
        }
    }
    @Test void pendingPullRetainsIdentityAndYieldsTheOnlySharedCarrier() throws Exception {
        try (ActorRuntime runtime = runtime()) {
            Pending source = new Pending();
            AtomicReference<Object> invocationDomain = new AtomicReference<>();
            var launched = runtime.spawnInvocation(ActorRuntime.ActorKind.SHARED, 0, (m, context) -> {
                invocationDomain.set(ActorRuntime.currentActorExecutionDomain());
                return source;
            });
            OresObservable<Integer> stream = get(launched.result());
            var ref = get(launched.ready());
            assertTrue(ref.isAlive());
            assertFalse(launched.done().isDone());
            OresSubscription<Integer> sub = stream.subscribe();
            var pending = sub.next();
            assertTrue(source.pulled.await(3, TimeUnit.SECONDS));
            assertFalse(pending.isDone());
            assertSame(invocationDomain.get(), source.domain.get());
            var peer = runtime.spawnInvocation(ActorRuntime.ActorKind.SHARED, 21, (m, context) -> m * 2);
            assertEquals(42, get(peer.result()));
            Thread producer = new Thread(() -> source.item.completeFromRuntime(OresNotification.complete()), "external-producer");
            producer.start(); producer.join();
            assertTrue(get(pending).isComplete());
            assertTrue(get(launched.done()));
            assertFalse(ref.isAlive());
            assertSame(invocationDomain.get(), source.cancelDomain.get());
            assertEquals(1, source.cancellations.get());
            assertEquals(0, runtime.actorCount());
        }
    }
    @Test void cancellationStopsProducerAndLateCompletionCannotReviveIt() throws Exception {
        try (ActorRuntime runtime = runtime()) {
            Pending source = new Pending();
            var launched = runtime.spawnInvocation(ActorRuntime.ActorKind.PRIVATE, 0, (m, c) -> source);
            OresObservable<Integer> stream = get(launched.result());
            var sub = stream.subscribe(); var item = sub.next();
            assertTrue(source.pulled.await(3, TimeUnit.SECONDS));
            assertTrue(sub.cancel());
            assertTrue(get(launched.done()));
            assertTrue(item.isCancelled());
            assertSame(source.domain.get(), source.cancelDomain.get());
            source.item.completeFromRuntime(OresNotification.next(99));
            assertTrue(get(sub.next()).isComplete());
            assertThrows(IllegalStateException.class, stream::subscribe);
            assertEquals(0, runtime.actorCount());
        }
    }
    @Test void onlyOnePullCanBeOutstanding() throws Exception {
        try (ActorRuntime runtime = runtime()) {
            Pending source = new Pending();
            var launched = runtime.spawnInvocation(ActorRuntime.ActorKind.SHARED, 0, (m, c) -> source);
            OresObservable<Integer> stream = get(launched.result());
            var sub = stream.subscribe(); sub.next();
            assertTrue(source.pulled.await(3, TimeUnit.SECONDS));
            var denied = assertThrows(ExecutionException.class, () -> get(sub.next()));
            assertInstanceOf(IllegalStateException.class, denied.getCause());
            sub.cancel(); assertTrue(get(launched.done()));
        }
    }
    @Test void observableKeepsProducerUntilLastSubscriptionEnds() throws Exception {
        try (ActorRuntime runtime = runtime()) {
            var launched = runtime.spawnInvocation(ActorRuntime.ActorKind.SHARED, 0,
                    (m, c) -> OresObservable.fromValues(List.of(1)));
            OresObservable<Integer> stream = get(launched.result());
            var first = stream.subscribe(); var second = stream.subscribe();
            assertEquals(1, get(first.next()).value());
            assertTrue(get(first.next()).isComplete());
            assertFalse(launched.done().isDone());
            assertTrue(get(launched.ready()).isAlive());
            assertEquals(1, get(second.next()).value());
            assertTrue(get(second.next()).isComplete());
            assertTrue(get(launched.done()));
        }
    }
    @Test void streamAllowsExactlyOneSubscription() throws Exception {
        try (ActorRuntime runtime = runtime()) {
            var launched = runtime.spawnInvocation(ActorRuntime.ActorKind.PRIVATE, 0,
                    (m, c) -> OresStream.fromValues(List.of(7)));
            OresStream<Integer> stream = get(launched.result());
            var sub = stream.subscribe();
            assertThrows(IllegalStateException.class, stream::subscribe);
            assertEquals(7, get(sub.next()).value());
            assertTrue(get(sub.next()).isComplete());
            assertTrue(get(launched.done()));
        }
    }
    @Test void closeReleasesProducerThatNeverHadASubscriber() throws Exception {
        try (ActorRuntime runtime = runtime()) {
            var launched = runtime.spawnInvocation(ActorRuntime.ActorKind.SHARED, 0,
                    (m, c) -> OresObservable.empty());
            var stream = get(launched.result());
            assertFalse(launched.done().isDone());
            ((AutoCloseable) stream).close();
            assertTrue(get(launched.done()));
            assertEquals(0, runtime.actorCount());
        }
    }
    @Test void unsafeItemFailsPullAndReleasesActor() throws Exception {
        try (ActorRuntime runtime = runtime()) {
            var launched = runtime.spawnInvocation(ActorRuntime.ActorKind.PRIVATE, 0,
                    (m, c) -> OresObservable.just(new Object()));
            OresObservable<Object> stream = get(launched.result());
            var failure = assertThrows(ExecutionException.class, () -> get(stream.subscribe().next()));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            var ended = assertThrows(ExecutionException.class, () -> get(launched.done()));
            assertInstanceOf(IllegalArgumentException.class, ended.getCause());
            assertEquals(0, runtime.actorCount());
        }
    }
    @Test void stopSettlesPendingPullInsteadOfLeavingWaitersHung() throws Exception {
        try (ActorRuntime runtime = runtime()) {
            Pending source = new Pending();
            var launched = runtime.spawnInvocation(ActorRuntime.ActorKind.SHARED, 0, (m, c) -> source);
            OresObservable<Integer> stream = get(launched.result());
            var item = stream.subscribe().next();
            assertTrue(source.pulled.await(3, TimeUnit.SECONDS));
            get(launched.ready()).stop();
            assertTrue(item.isDone());
            assertTrue(get(launched.done()));
            assertEquals(0, runtime.actorCount());
        }
    }
    @Test void producerFailureFailsPullAndDone() throws Exception {
        try (ActorRuntime runtime = runtime()) {
            Pending source = new Pending();
            var launched = runtime.spawnInvocation(ActorRuntime.ActorKind.SHARED, 0, (m, c) -> source);
            OresObservable<Integer> stream = get(launched.result());
            var item = stream.subscribe().next();
            assertTrue(source.pulled.await(3, TimeUnit.SECONDS));
            source.item.failFromRuntime(new IllegalStateException("producer failed"));
            assertEquals("producer failed", assertThrows(ExecutionException.class, () -> get(item)).getCause().getMessage());
            assertEquals("producer failed", assertThrows(ExecutionException.class, () -> get(launched.done())).getCause().getMessage());
            assertEquals(0, runtime.actorCount());
        }
    }
    @Test void throwingCleanupCannotStrandTerminalWaiter() throws Exception {
        try (ActorRuntime runtime = runtime()) {
            OresObservable<Integer> source = new OresObservable<>() {
                @Override public OresSubscription<Integer> subscribe() {
                    return new OresSubscription<>() {
                        @Override protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                            return OresFuture.completed(OresNotification.complete());
                        }
                        @Override protected void cancelFromRuntime() { throw new IllegalStateException("cleanup failed"); }
                    };
                }
            };
            var launched = runtime.spawnInvocation(ActorRuntime.ActorKind.SHARED, 0, (m, c) -> source);
            OresObservable<Integer> stream = get(launched.result());
            get(stream.subscribe().next());
            // Source terminal state is authoritative even when its best-effort hook throws.
            assertTrue(get(launched.done()));
            assertEquals(0, runtime.actorCount());
        }
    }
    @Test void runtimeShutdownCancelsPendingWaitAndReclaimsProducer() throws Exception {
        ActorRuntime runtime = runtime();
        try {
            Pending source = new Pending();
            var launched = runtime.spawnInvocation(ActorRuntime.ActorKind.SHARED, 0, (m, c) -> source);
            OresObservable<Integer> stream = get(launched.result());
            var item = stream.subscribe().next();
            assertTrue(source.pulled.await(3, TimeUnit.SECONDS));
            runtime.close();
            assertTrue(item.isDone());
            assertTrue(get(launched.done()));
            assertEquals(0, runtime.actorCount());
        } finally { runtime.close(); }
    }

}
