package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ActorEventBusTest {
    @Test
    void cousinsCanJoinOneGroupThroughAForwardedCapability() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            CountDownLatch cousinsJoined = new CountDownLatch(2);

            ActorRuntime.ActorRef<Object> root = runtime.spawnShared(() -> (message, context) -> {
                if ("joined".equals(message)) {
                    cousinsJoined.countDown();
                    return;
                }

                List<?> start = (List<?>) message;
                ActorRuntime.ActorGroupJoinCapability joinCapability =
                        (ActorRuntime.ActorGroupJoinCapability) start.get(1);

                ActorRuntime.ActorRef<Object> leftParent =
                        context.runtime().spawnShared(factoryContext -> (parentMessage, parentContext) -> {
                            ActorRuntime.ActorRef<Object> grandchild =
                                    parentContext.runtime().spawnShared(
                                            childFactoryContext -> (childMessage, childContext) -> {
                                                List<?> args = (List<?>) childMessage;
                                                ActorRuntime.ActorGroupJoinCapability childCapability =
                                                        (ActorRuntime.ActorGroupJoinCapability) args.get(0);
                                                @SuppressWarnings("unchecked")
                                                ActorRuntime.ActorRef<Object> replyTo =
                                                        (ActorRuntime.ActorRef<Object>) args.get(1);
                                                childCapability.join();
                                                replyTo.send("joined");
                                            });
                            grandchild.send(parentMessage);
                        });

                ActorRuntime.ActorRef<Object> rightParent =
                        context.runtime().spawnShared(factoryContext -> (parentMessage, parentContext) -> {
                            ActorRuntime.ActorRef<Object> grandchild =
                                    parentContext.runtime().spawnShared(
                                            childFactoryContext -> (childMessage, childContext) -> {
                                                List<?> args = (List<?>) childMessage;
                                                ActorRuntime.ActorGroupJoinCapability childCapability =
                                                        (ActorRuntime.ActorGroupJoinCapability) args.get(0);
                                                @SuppressWarnings("unchecked")
                                                ActorRuntime.ActorRef<Object> replyTo =
                                                        (ActorRuntime.ActorRef<Object>) args.get(1);
                                                childCapability.join();
                                                replyTo.send("joined");
                                            });
                            grandchild.send(parentMessage);
                        });

                List<Object> forwarded = List.of(joinCapability, context.self());
                leftParent.send(forwarded);
                rightParent.send(forwarded);
            });

            root.send(List.of("start", capability));

            assertTrue(cousinsJoined.await(2, TimeUnit.SECONDS));
            assertEquals(2, group.memberCount());
        }
    }

    @Test
    void rotatedJoinCapabilityRevokesOldAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability stale = group.joinCapability();
            ActorRuntime.ActorGroupJoinCapability current = group.rotateJoinCapability();

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> staleFailure = new AtomicReference<>();
            AtomicReference<Boolean> joined = new AtomicReference<>(false);

            ActorRuntime.ActorRef<List<ActorRuntime.ActorGroupJoinCapability>> actor =
                    runtime.spawnShared(() -> (caps, context) -> {
                        try {
                            caps.get(0).join();
                        } catch (Throwable failure) {
                            staleFailure.set(failure);
                        }
                        caps.get(1).join();
                        joined.set(true);
                        done.countDown();
                    });

            actor.send(List.of(stale, current));

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertTrue(staleFailure.get() instanceof SecurityException);
            assertTrue(joined.get());
            assertEquals(1, group.memberCount());
        }
    }

    @Test
    void closedSubscriptionCanBeReplacedBySameActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            group.events().defineTopic("bounds", ActorEventBus.DeliveryPolicy.LATEST, 1);

            CountDownLatch cycled = new CountDownLatch(1);
            AtomicReference<Boolean> replacementOpen = new AtomicReference<>(false);

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                capability.join();
                ActorEventBus.Subscription<Integer> first =
                        group.events().subscribe("bounds");
                first.close();

                ActorEventBus.Subscription<Integer> second =
                        group.events().subscribe("bounds");
                replacementOpen.set(!second.closed());
                cycled.countDown();
            });

            actor.send("cycle");

            assertTrue(cycled.await(2, TimeUnit.SECONDS));
            assertTrue(replacementOpen.get());
            assertEquals(1, group.events().topics().getFirst().subscribers());
        }
    }

    @Test
    void latestTopicCoalescesUnreadEvents() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            group.events().defineTopic("incumbent", ActorEventBus.DeliveryPolicy.LATEST, 1);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch read = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<Integer>> subscription = new AtomicReference<>();
            AtomicReference<Integer> observed = new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber = runtime.spawnShared(() -> (message, context) -> {
                if ("subscribe".equals(message)) {
                    capability.join();
                    subscription.set(group.events().subscribe("incumbent"));
                    subscribed.countDown();
                    return;
                }
                observed.set(subscription.get().tryRead().orElseThrow().value());
                read.countDown();
            });

            subscriber.send("subscribe");
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));

            assertEquals(0, group.events().publishSystem("incumbent", 100).coalesced());
            assertEquals(1, group.events().publishSystem("incumbent", 90).coalesced());
            assertEquals(1, group.events().publishSystem("incumbent", 80).coalesced());

            subscriber.send("read");
            assertTrue(read.await(2, TimeUnit.SECONDS));
            assertEquals(80, observed.get());
            assertEquals(2, group.events().topics().getFirst().coalesced());
        }
    }

    @Test
    void reliableTopicUsesFutureBackpressureWithoutBlockingPublisher() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            group.events().defineTopic("cuts", ActorEventBus.DeliveryPolicy.RELIABLE, 1);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch reads = new CountDownLatch(2);
            AtomicReference<ActorEventBus.Subscription<Integer>> subscription = new AtomicReference<>();
            List<Integer> observed = java.util.Collections.synchronizedList(new ArrayList<>());

            ActorRuntime.ActorRef<String> subscriber = runtime.spawnShared(() -> (message, context) -> {
                if ("subscribe".equals(message)) {
                    capability.join();
                    subscription.set(group.events().subscribe("cuts"));
                    subscribed.countDown();
                    return;
                }
                observed.add(subscription.get().tryRead().orElseThrow().value());
                reads.countDown();
            });

            subscriber.send("subscribe");
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));

            ActorEventBus.PublishReceipt first = group.events().publishSystem("cuts", 1);
            assertTrue(first.completion().isDone());

            ActorEventBus.PublishReceipt second = group.events().publishSystem("cuts", 2);
            assertFalse(second.completion().isDone(), "full reliable subscription must apply async backpressure");
            assertThrows(
                    ActorEventBus.EventBackpressureException.class,
                    () -> group.events().publishSystem("cuts", 3),
                    "ignoring reliable futures must not create an unbounded pending-writer queue");

            subscriber.send("read");
            second.completion().get(2, TimeUnit.SECONDS);
            subscriber.send("read");

            assertTrue(reads.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), observed);
        }
    }

    @Test
    void lossyTopicDropsWhenSubscriberQueueIsFull() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            group.events().defineTopic("stats", ActorEventBus.DeliveryPolicy.LOSSY, 1);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch read = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<Integer>> subscription = new AtomicReference<>();
            AtomicReference<Integer> observed = new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber = runtime.spawnShared(() -> (message, context) -> {
                if ("subscribe".equals(message)) {
                    capability.join();
                    subscription.set(group.events().subscribe("stats"));
                    subscribed.countDown();
                    return;
                }
                observed.set(subscription.get().tryRead().orElseThrow().value());
                read.countDown();
            });

            subscriber.send("subscribe");
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));

            ActorEventBus.PublishReceipt first = group.events().publishSystem("stats", 1);
            ActorEventBus.PublishReceipt second = group.events().publishSystem("stats", 2);
            assertEquals(1, first.accepted());
            assertEquals(1, second.dropped());

            subscriber.send("read");
            assertTrue(read.await(2, TimeUnit.SECONDS));
            assertEquals(1, observed.get());
        }
    }

    @Test
    void minReductionOnlyPublishesImprovementsAndLatestSubscriberSeesBestBound() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            group.events().defineDoubleReduction(
                    "upper_bound",
                    ActorEventBus.DoubleReductionKind.MIN,
                    Double.POSITIVE_INFINITY);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch read = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<Double>> subscription = new AtomicReference<>();
            AtomicReference<Double> observed = new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber = runtime.spawnShared(() -> (message, context) -> {
                if ("subscribe".equals(message)) {
                    capability.join();
                    subscription.set(group.events().subscribe("upper_bound"));
                    subscribed.countDown();
                    return;
                }
                observed.set(subscription.get().tryRead().orElseThrow().value());
                read.countDown();
            });

            subscriber.send("subscribe");
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));

            assertTrue(group.events().reduceDouble("upper_bound", 100.0).changed());
            assertTrue(group.events().reduceDouble("upper_bound", 80.0).changed());
            assertFalse(group.events().reduceDouble("upper_bound", 90.0).changed());
            assertEquals(80.0, group.events().readDoubleReduction("upper_bound"));
            assertDoesNotThrow(() -> group.events().defineDoubleReduction(
                    "upper_bound",
                    ActorEventBus.DoubleReductionKind.MIN,
                    Double.POSITIVE_INFINITY));

            subscriber.send("read");
            assertTrue(read.await(2, TimeUnit.SECONDS));
            assertEquals(80.0, observed.get().doubleValue());
        }
    }

    @Test
    void privateActorsCannotSubscribeToZeroCopyGroupEvents() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            group.events().defineTopic("bounds", ActorEventBus.DeliveryPolicy.LATEST, 1);

            ActorRuntime.ActorRef<ActorRuntime.ActorGroupJoinCapability> privateActor =
                    runtime.spawnPrivate(factoryContext -> (joinCapability, context) -> {
                        ActorRuntime.ActorGroup joined = joinCapability.join();
                        joined.events().subscribe("bounds");
                    });

            privateActor.send(capability);

            assertTrue(privateActor.awaitTermination(2, TimeUnit.SECONDS));
            Throwable failure = privateActor.failure().orElseThrow();
            assertTrue(failure instanceof SecurityException, failure.toString());
            assertEquals(0, group.memberCount(),
                    "failed private subscriber must be removed when the actor terminates");
        }
    }

    @Test
    void eventPayloadIsFrozenOnceBeforeFanout() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            group.events().defineTopic("candidate", ActorEventBus.DeliveryPolicy.LATEST, 1);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch read = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<List<Integer>>> subscription = new AtomicReference<>();
            AtomicReference<List<Integer>> observed = new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber = runtime.spawnShared(() -> (message, context) -> {
                if ("subscribe".equals(message)) {
                    capability.join();
                    subscription.set(group.events().subscribe("candidate"));
                    subscribed.countDown();
                    return;
                }
                observed.set(subscription.get().tryRead().orElseThrow().value());
                read.countDown();
                context.self().stop();
            });

            subscriber.send("subscribe");
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));

            ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
            group.events().publishSystem("candidate", mutable);
            assertTrue(runtime.sharedMemoryBytes() > 0,
                    "queued event payload must consume the OresVM aggregate memory budget");
            mutable.add(3);

            subscriber.send("read");
            assertTrue(read.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), observed.get());
            assertThrows(UnsupportedOperationException.class, () -> observed.get().add(4));
            assertTrue(subscriber.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "event payload reservation must be released after the final delivery is consumed");
        }
    }

    @Test
    void closingLatestSubscriptionDrainsUnreadPayloadReservation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            group.events().defineTopic("latest-close", ActorEventBus.DeliveryPolicy.LATEST, 1);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<List<Integer>>> subscription =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber = runtime.spawnShared(() -> (message, context) -> {
                if ("subscribe".equals(message)) {
                    capability.join();
                    subscription.set(group.events().subscribe("latest-close"));
                    subscribed.countDown();
                    return;
                }
                if ("close".equals(message)) {
                    subscription.get().close();
                    closed.countDown();
                    context.self().stop();
                }
            });

            subscriber.send("subscribe");
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));

            group.events().publishSystem(
                    "latest-close",
                    new ArrayList<>(List.of(1, 2, 3)));
            assertTrue(runtime.sharedMemoryBytes() > 0,
                    "unread queued event must retain its frozen payload reservation");

            subscriber.send("close");
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertTrue(subscriber.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "closing a subscription must drain unread event reservations");
        }
    }

    @Test
    void closingReliableSubscriptionReleasesBufferedAndBackpressuredDeliveries()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            group.events().defineTopic("reliable-close", ActorEventBus.DeliveryPolicy.RELIABLE, 1);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<List<Integer>>> subscription =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber = runtime.spawnShared(() -> (message, context) -> {
                if ("subscribe".equals(message)) {
                    capability.join();
                    subscription.set(group.events().subscribe("reliable-close"));
                    subscribed.countDown();
                    return;
                }
                if ("close".equals(message)) {
                    subscription.get().close();
                    closed.countDown();
                    context.self().stop();
                }
            });

            subscriber.send("subscribe");
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));

            ActorEventBus.PublishReceipt buffered = group.events().publishSystem(
                    "reliable-close",
                    new ArrayList<>(List.of(1)));
            assertTrue(buffered.completion().isDone());

            ActorEventBus.PublishReceipt backpressured = group.events().publishSystem(
                    "reliable-close",
                    new ArrayList<>(List.of(2)));
            assertFalse(backpressured.completion().isDone(),
                    "second reliable delivery should wait behind the full capacity-one channel");
            assertTrue(runtime.sharedMemoryBytes() > 0);

            subscriber.send("close");
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertTrue(subscriber.awaitTermination(2, TimeUnit.SECONDS));

            assertTrue(backpressured.completion().isCompletedExceptionally(),
                    "closing the channel must fail the pending reliable write");
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "both buffered and pending reliable deliveries must release reservations");
        }
    }

    @Test
    void actorCreatedGroupClosesWithItsCreator() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.ActorGroupId> groupId = new AtomicReference<>();
            CountDownLatch created = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> creator = runtime.spawnShared(() -> (message, context) -> {
                ActorRuntime.ActorGroup group = context.runtime().createActorGroup();
                groupId.set(group.id());
                created.countDown();
                context.self().stop();
            });

            creator.send("create");
            assertTrue(created.await(2, TimeUnit.SECONDS));
            assertTrue(creator.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorGroupCount());
            assertThrows(IllegalArgumentException.class, () -> runtime.actorGroup(groupId.get()));
        }
    }

    @Test
    void joinCapabilityCannotCrossRuntimeBoundary() {
        try (ActorRuntime source = new ActorRuntime();
             ActorRuntime destination = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = source.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            ActorRuntime.ActorRef<Object> target =
                    destination.spawnShared(() -> (message, context) -> { });

            assertThrows(IllegalArgumentException.class, () -> target.send(capability));
        }
    }
}
