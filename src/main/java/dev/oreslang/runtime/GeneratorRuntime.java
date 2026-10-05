package dev.oreslang.runtime;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime substrate for Oreslang generator activations.
 *
 * <p>A generator owns exactly one suspended activation. Pulls are serialized:
 * the consumer grants one resume permit, the producer runs until the next
 * yield/completion/failure, and then control returns to the consumer. The
 * producer uses {@link AsyncRuntime}'s context-owned virtual carrier so a
 * suspended generator does not pin an OS thread and is cancelled with its
 * OresContext.</p>
 *
 * <p>This is an interpreter implementation detail. A native/AOT lowering may
 * represent the same contract as an explicit program-counter/state-machine
 * frame without changing language semantics.</p>
 */
public final class GeneratorRuntime {
    private GeneratorRuntime() { }

    @FunctionalInterface
    public interface Emitter<T> {
        void emit(T value);
    }

    @FunctionalInterface
    public interface Producer<T> {
        void run(Emitter<T> emitter) throws Exception;
    }

    public record Step<T>(boolean done, T value) {
        public static <T> Step<T> yielded(T value) { return new Step<>(false, value); }
        public static <T> Step<T> doneStep() { return new Step<>(true, null); }
    }

    public static <T> Generator<T> generator(AsyncRuntime runtime, Producer<T> producer) {
        return new Generator<>(new Engine<>(runtime, producer));
    }

    public static <T> AsyncGenerator<T> asyncGenerator(AsyncRuntime runtime, Producer<T> producer) {
        return new AsyncGenerator<>(runtime, new Engine<>(runtime, producer));
    }

    public static final class Generator<T> implements Iterable<T>, AutoCloseable {
        private final Engine<T> engine;

        private Generator(Engine<T> engine) {
            this.engine = engine;
        }

        public Step<T> nextStep() {
            if (ActorRuntime.inActorExecution()) {
                throw new IllegalStateException(
                        "synchronous generator iteration cannot park an actor carrier; "
                                + "use an async generator and 'for await'");
            }
            return engine.next();
        }

        @Override
        public Iterator<T> iterator() {
            return new Iterator<>() {
                private Step<T> buffered;

                @Override
                public boolean hasNext() {
                    if (buffered == null) buffered = nextStep();
                    return !buffered.done();
                }

                @Override
                public T next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    T value = buffered.value();
                    buffered = null;
                    return value;
                }
            };
        }

        @Override
        public void close() {
            engine.close();
        }
    }

    public static final class AsyncGenerator<T> implements AutoCloseable {
        private final AsyncRuntime runtime;
        private final Engine<T> engine;

        private AsyncGenerator(AsyncRuntime runtime, Engine<T> engine) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.engine = engine;
        }

        /**
         * One asynchronous pull. Calls are serialized by the activation even
         * if callers issue more than one pull before an earlier pull completes.
         */
        public CompletionStage<Step<T>> nextStep() {
            if (engine.isClosed()) {
                return CompletableFuture.completedFuture(Step.doneStep());
            }
            return runtime.submit(engine::next);
        }

        @Override
        public void close() {
            engine.close();
        }
    }

    private sealed interface Event<T> permits YieldEvent, DoneEvent, FailedEvent { }
    private record YieldEvent<T>(T value) implements Event<T> { }
    private record DoneEvent<T>() implements Event<T> { }
    private record FailedEvent<T>(Throwable failure) implements Event<T> { }

    private static final Object RESUME = new Object();

    private static final class Engine<T> implements AutoCloseable {
        private final AsyncRuntime runtime;
        private final Producer<T> producer;
        private final ArrayBlockingQueue<Object> resumes = new ArrayBlockingQueue<>(1);
        private final ArrayBlockingQueue<Event<T>> events = new ArrayBlockingQueue<>(1);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private CompletableFuture<Void> producerTask;
        private boolean done;

        private Engine(AsyncRuntime runtime, Producer<T> producer) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.producer = Objects.requireNonNull(producer, "producer");
        }

        private boolean isClosed() {
            return closed.get();
        }

        private synchronized Step<T> next() {
            if (done || closed.get()) return Step.doneStep();
            startIfNeeded();
            putResume();
            Event<T> event = takeEvent();
            if (event instanceof YieldEvent<?> yielded) {
                @SuppressWarnings("unchecked")
                T value = (T) yielded.value();
                return Step.yielded(value);
            }
            done = true;
            if (event instanceof FailedEvent<?> failed) {
                Throwable failure = failed.failure();
                if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                if (failure instanceof Error error) throw error;
                throw new RuntimeException(failure);
            }
            return Step.doneStep();
        }

        private void startIfNeeded() {
            if (!started.compareAndSet(false, true)) return;
            producerTask = runtime.submit(() -> {
                try {
                    takeResume();
                    if (!closed.get()) {
                        producer.run(this::emit);
                    }
                    publish(new DoneEvent<>());
                } catch (CancellationException cancelled) {
                    if (!closed.get()) publish(new FailedEvent<>(cancelled));
                } catch (Throwable failure) {
                    if (!closed.get()) publish(new FailedEvent<>(failure));
                }
                return null;
            });
        }

        private void emit(T value) {
            if (closed.get()) throw new CancellationException("generator is closed");
            publish(new YieldEvent<>(value));
            takeResume();
            if (closed.get()) throw new CancellationException("generator is closed");
        }

        private void publish(Event<T> event) {
            try {
                events.put(event);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator producer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        private void putResume() {
            try {
                resumes.put(RESUME);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator consumer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        private void takeResume() {
            try {
                resumes.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator producer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        private Event<T> takeEvent() {
            try {
                return events.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator consumer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            done = true;
            if (producerTask != null) producerTask.cancel(true);
            resumes.offer(RESUME);
            events.offer(new DoneEvent<>());
        }
    }
}
