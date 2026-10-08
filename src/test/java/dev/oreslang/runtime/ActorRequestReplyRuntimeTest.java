package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorRequestReplyRuntimeTest {
    private static final class Executor implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        private final CountDownLatch started;
        private final CountDownLatch release;
        private final AtomicInteger executed = new AtomicInteger();

        private Executor(SharedCodeImageStore.CodeImage image,
                         CountDownLatch started, CountDownLatch release) {
            this.image = image;
            this.started = started;
            this.release = release;
        }

        @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

        @Override public ActorRuntime.ActorProtocol actorProtocol(String actorTypeName) {
            assertEquals("Worker", actorTypeName);
            return ActorRuntime.ActorProtocol.UNARY;
        }

        @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                String name, ActorRuntime.ActorContext<Object> context) {
            return context.self()::id;
        }

        @Override public void receiveActor(String name,
                ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            fail("request actor must not execute the event receive handler");
        }

        @Override public Object requestActor(String name,
                ActorRuntime.ActorOwnedGuestState state,
                Object request,
                ActorRuntime.ActorContext<Object> context) throws Exception {
            assertEquals(context.self().id(), state.ownerActorId());
            assertEquals(request, context.currentMail().orElseThrow().value());
            executed.incrementAndGet();
            switch ((String) request) {
                case "block" -> {
                    if (started != null) started.countDown();
                    if (release != null && !release.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("request test release expired");
                    }
                    return "first";
                }
                case "ordinary" -> throw new IllegalStateException("recoverable");
                case "panic" -> throw new ActorRuntime.ActorPanicException("actor failed", null);
                case "unsendable" -> { return new StringBuilder("unsafe"); }
                default -> { return "ok:" + request; }
            }
        }
    }

    @Test
    void requestReplyIsRuntimeOwnedAcrossActorKinds() throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("actor-request-proof.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            for (var kind : ActorRuntime.ActorKind.values()) {
                var actor = runtime.spawnCodeActor(kind,
                        new Executor(image, null, null), "Worker");
                assertNull(actor.ready().get(2, TimeUnit.SECONDS));
                assertEquals("ok:hello", actor.request("hello").get(2, TimeUnit.SECONDS));
                assertEquals("ok:again", actor.request("again").get(2, TimeUnit.SECONDS));
                actor.stop();
                assertNull(actor.done().get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void unaryActorsRejectOneWaySendWithoutDispatchingGuestCode() throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("unary-abi-guard.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var executor = new Executor(image, null, null);
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                    executor, "Worker");
            actor.ready().get(2, TimeUnit.SECONDS);

            var error = assertThrows(IllegalArgumentException.class,
                    () -> actor.send("wrong-protocol"));
            assertTrue(error.getMessage().contains("stream actor"), error.getMessage());
            assertEquals(0, executor.executed.get());
            assertEquals("ok:valid", actor.request("valid").get(2, TimeUnit.SECONDS));
            assertEquals(1, executor.executed.get());
            actor.stop();
            actor.done().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void lifecycleOnlyActorRejectsBothProtocolsBeforeMessageValidation() throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("lifecycle-only-abi-guard.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var invoked = new AtomicInteger();
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() {
                    return image;
                }

                @Override public ActorRuntime.ActorProtocol actorProtocol(String actorTypeName) {
                    return ActorRuntime.ActorProtocol.LIFECYCLE_ONLY;
                }

                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String name, ActorRuntime.ActorContext<Object> context) {
                    return context.self()::id;
                }

                @Override public void receiveActor(
                        String name, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    invoked.incrementAndGet();
                    fail("lifecycle-only actors have no receive entrypoint");
                }

                @Override public Object requestActor(
                        String name, ActorRuntime.ActorOwnedGuestState state,
                        Object request, ActorRuntime.ActorContext<Object> context) {
                    invoked.incrementAndGet();
                    fail("lifecycle-only actors have no run entrypoint");
                    return null;
                }
            };

            for (var kind : ActorRuntime.ActorKind.values()) {
                var actor = runtime.spawnCodeActor(kind, executor, "Worker");
                actor.ready().get(2, TimeUnit.SECONDS);
                // Invalid transport graphs are rejected at the protocol gate,
                // before graph validation, serialization or mailbox reservation.
                var sendError = assertThrows(IllegalArgumentException.class,
                        () -> actor.send(new Object()));
                assertTrue(sendError.getMessage().contains("stream actor"),
                        sendError.getMessage());
                var requestError = assertThrows(IllegalArgumentException.class,
                        () -> actor.request(new Object()));
                assertTrue(requestError.getMessage().contains("unary actor"),
                        requestError.getMessage());
                assertEquals(0, invoked.get());
                assertTrue(actor.isAlive());
                actor.stop();
                actor.done().get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void ordinaryRequestFailureDoesNotDestroyActor() throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("actor-fail-local.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                    new Executor(image, null, null), "Worker");
            actor.ready().get(2, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> actor.request("ordinary").get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("recoverable"));
            assertTrue(actor.isAlive());
            assertEquals("ok:recovered", actor.request("recovered").get(2, TimeUnit.SECONDS));
            actor.stop();
        }
    }

    @Test
    void cancellationBeforeDispatchSkipsGuestHandlerWithoutRewindingTurn()
            throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("actor-cancel-queued.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var executor = new Executor(image, started, release);
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                    executor, "Worker");
            try {
                actor.ready().get(2, TimeUnit.SECONDS);
                var running = actor.request("block");
                assertTrue(started.await(2, TimeUnit.SECONDS));
                var cancelled = actor.request("skipped");
                assertTrue(cancelled.cancel(false));
                release.countDown();
                assertEquals("first", running.get(2, TimeUnit.SECONDS));
                assertEquals("ok:later", actor.request("later").get(2, TimeUnit.SECONDS));
                assertEquals(2, executor.executed.get(),
                        "cancelled queued request may not invoke guest code");
            } finally {
                release.countDown();
                actor.cancel();
            }
        }
    }

    @Test
    void actorPanicRejectsReplyAndStopsOnlyThatActor() throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("actor-panic.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var failing = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                    new Executor(image, null, null), "Worker");
            var healthy = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                    new Executor(image, null, null), "Worker");
            failing.ready().get(2, TimeUnit.SECONDS);
            healthy.ready().get(2, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class,
                    () -> failing.request("panic").get(2, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorPanicException.class, failure.getCause());
            assertTrue(failing.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse(failing.isAlive());
            assertTrue(failing.failure().isPresent());
            assertEquals("ok:alive", healthy.request("alive").get(2, TimeUnit.SECONDS));
            healthy.stop();
        }
    }
}
