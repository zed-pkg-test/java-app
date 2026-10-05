package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Logical guest-language async stack metadata.
 *
 * <p>This is deliberately independent from the physical carrier stack. An
 * Oreslang task may suspend, fully unwind the Java/Truffle carrier stack, and
 * later resume on another carrier while retaining the same logical trace.</p>
 *
 * <p>The trace is bounded. Proper tail-await transfer therefore stays
 * constant-space even when a recursive computation performs millions of
 * logical calls. Repeated identical tail transfers are run-length compressed.</p>
 */
public final class OresAsyncTrace {
    private static final int DEFAULT_MAX_EVENTS = 96;
    private static final int MAX_ATTACHED_TRACES = 32;
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();

    private OresAsyncTrace() { }

    public enum BoundaryKind {
        CALL,
        AWAIT,
        TAIL_AWAIT,
        ACTOR_MESSAGE,
        CALLBACK_ADAPTER,
        IO_COMPLETION
    }

    public record SourceSite(
            String sourceId,
            int line,
            int column,
            String codeGenerationId) {
        public SourceSite {
            sourceId = sourceId == null || sourceId.isBlank() ? "<unknown>" : sourceId;
        }

        public static SourceSite unknown(String sourceId, String codeGenerationId) {
            return new SourceSite(sourceId, -1, -1, codeGenerationId);
        }

        public boolean known() {
            return line > 0 && column > 0;
        }
    }

    public record Frame(String symbol, SourceSite site) {
        public Frame {
            if (symbol == null || symbol.isBlank()) symbol = "<anonymous>";
            site = Objects.requireNonNull(site, "site");
        }
    }

    public sealed interface Event permits CallEvent, AwaitEvent, TailEvent, BoundaryEvent {
        SourceSite site();
    }

    public record CallEvent(Frame caller, Frame callee, SourceSite site)
            implements Event {
        public CallEvent {
            Objects.requireNonNull(caller, "caller");
            Objects.requireNonNull(callee, "callee");
            Objects.requireNonNull(site, "site");
        }
    }

    public record AwaitEvent(Frame frame, SourceSite site)
            implements Event {
        public AwaitEvent {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(site, "site");
        }
    }

    public record BoundaryEvent(
            BoundaryKind kind,
            Frame frame,
            SourceSite site) implements Event {
        public BoundaryEvent {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(site, "site");
        }
    }

    public record TailEvent(
            Frame from,
            Frame to,
            SourceSite site,
            long repetitions) implements Event {
        public TailEvent {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(site, "site");
            if (repetitions <= 0) {
                throw new IllegalArgumentException("tail repetition count must be positive");
            }
        }

        private boolean sameTransition(Frame from, Frame to, SourceSite site) {
            return this.from.equals(from) && this.to.equals(to) && this.site.equals(site);
        }

        private TailEvent incremented() {
            if (repetitions == Long.MAX_VALUE) return this;
            return new TailEvent(from, to, site, repetitions + 1);
        }
    }

    /**
     * Mutable task-local trace. One Ores task/actor turn owns a Trace at a time,
     * so mutation follows the same single-executor lease as guest execution.
     */
    public static final class Trace {
        private final int maxEvents;
        private final ArrayDeque<Event> history;
        private Frame current;
        private long elidedEvents;

        private Trace(Frame current, int maxEvents) {
            this.current = Objects.requireNonNull(current, "current");
            if (maxEvents <= 0) throw new IllegalArgumentException("maxEvents must be positive");
            this.maxEvents = maxEvents;
            this.history = new ArrayDeque<>(Math.min(maxEvents, 16));
        }

        private Trace(Trace parent, Frame child, SourceSite callSite) {
            this.current = Objects.requireNonNull(child, "child");
            this.maxEvents = parent.maxEvents;
            this.history = new ArrayDeque<>(parent.history);
            this.elidedEvents = parent.elidedEvents;
            addBounded(new CallEvent(parent.current, child, callSite));
        }

        public Frame currentFrame() {
            return current;
        }

        public long elidedEvents() {
            return elidedEvents;
        }

        public int retainedEventCount() {
            return history.size();
        }

        public List<Event> events() {
            return List.copyOf(history);
        }

        public Trace child(Frame child, SourceSite callSite) {
            return new Trace(this, child, Objects.requireNonNull(callSite, "callSite"));
        }

        public void awaitAt(SourceSite site) {
            addBounded(new AwaitEvent(current, Objects.requireNonNull(site, "site")));
        }

        public void boundary(BoundaryKind kind, SourceSite site) {
            addBounded(new BoundaryEvent(
                    Objects.requireNonNull(kind, "kind"),
                    current,
                    Objects.requireNonNull(site, "site")));
        }

        public void tailAwait(Frame target, SourceSite site) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(site, "site");

            Event last = history.peekLast();
            if (last instanceof TailEvent tail && tail.sameTransition(current, target, site)) {
                history.removeLast();
                history.addLast(tail.incremented());
            } else {
                addBounded(new TailEvent(current, target, site, 1));
            }
            current = target;
        }

        private void addBounded(Event event) {
            while (history.size() >= maxEvents) {
                history.removeFirst();
                elidedEvents++;
            }
            history.addLast(event);
        }

        public String render() {
            StringBuilder out = new StringBuilder();
            appendFrame(out, "at", current);
            List<Event> events = new ArrayList<>(history);
            for (int i = events.size() - 1; i >= 0; i--) {
                Event event = events.get(i);
                if (event instanceof AwaitEvent awaited) {
                    appendBoundary(out, "await", awaited.site(), null);
                } else if (event instanceof CallEvent call) {
                    appendFrame(out, "at", call.caller());
                } else if (event instanceof TailEvent tail) {
                    appendBoundary(
                            out,
                            "tail-await",
                            tail.site(),
                            tail.from().symbol() + " -> " + tail.to().symbol()
                                    + (tail.repetitions() == 1
                                            ? ""
                                            : " repeated " + tail.repetitions() + " times"));
                } else if (event instanceof BoundaryEvent boundary) {
                    appendBoundary(
                            out,
                            boundary.kind().name().toLowerCase().replace('_', '-'),
                            boundary.site(),
                            boundary.frame().symbol());
                }
            }
            if (elidedEvents > 0) {
                out.append("\n    ... ")
                        .append(elidedEvents)
                        .append(" older async trace events elided");
            }
            return out.toString();
        }

        public StackTraceElement[] toJavaStackTrace() {
            List<StackTraceElement> result = new ArrayList<>();
            result.add(toStackTraceElement(current.symbol(), current.site()));

            List<Event> events = new ArrayList<>(history);
            for (int i = events.size() - 1; i >= 0; i--) {
                Event event = events.get(i);
                if (event instanceof AwaitEvent awaited) {
                    result.add(toStackTraceElement("<await>", awaited.site()));
                } else if (event instanceof CallEvent call) {
                    result.add(toStackTraceElement(call.caller().symbol(), call.caller().site()));
                } else if (event instanceof TailEvent tail) {
                    String label = "<tail-await "
                            + tail.from().symbol()
                            + " -> "
                            + tail.to().symbol()
                            + (tail.repetitions() == 1 ? "" : " x" + tail.repetitions())
                            + ">";
                    result.add(toStackTraceElement(label, tail.site()));
                } else if (event instanceof BoundaryEvent boundary) {
                    result.add(toStackTraceElement(
                            "<" + boundary.kind().name().toLowerCase().replace('_', '-') + ">",
                            boundary.site()));
                }
            }

            if (elidedEvents > 0) {
                result.add(new StackTraceElement(
                        "oreslang.async",
                        "<" + elidedEvents + " older async events elided>",
                        null,
                        -1));
            }
            return result.toArray(StackTraceElement[]::new);
        }
    }

    public static Trace root(Frame frame) {
        return new Trace(Objects.requireNonNull(frame, "frame"), DEFAULT_MAX_EVENTS);
    }

    public static Trace current() {
        return CURRENT.get();
    }

    public static Trace childOfCurrent(Frame frame, SourceSite callSite) {
        Trace parent = CURRENT.get();
        if (parent == null) return root(frame);
        return parent.child(frame, callSite);
    }

    public static Scope install(Trace trace) {
        Trace prior = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(trace, "trace"));
        return () -> {
            if (prior == null) CURRENT.remove();
            else CURRENT.set(prior);
        };
    }

    public static Throwable attach(Throwable failure, Trace trace) {
        Objects.requireNonNull(failure, "failure");
        if (trace == null) return failure;

        /*
         * A failure may cross arbitrarily many awaiters. Bounding each Trace is
         * not enough if every boundary adds another suppressed exception, so
         * bound the number of retained causal traces as well. Synchronizing on
         * the Throwable also makes shared-Future fan-out deterministic when
         * several waiters observe the same failure concurrently.
         */
        synchronized (failure) {
            int attached = 0;
            LogicalAsyncTraceElision elision = null;
            for (Throwable suppressed : failure.getSuppressed()) {
                if (suppressed instanceof LogicalAsyncStackTrace logical) {
                    if (logical.trace == trace) return failure;
                    attached++;
                } else if (suppressed instanceof LogicalAsyncTraceElision marker) {
                    elision = marker;
                }
            }

            if (attached < MAX_ATTACHED_TRACES) {
                failure.addSuppressed(new LogicalAsyncStackTrace(trace));
            } else if (elision == null) {
                failure.addSuppressed(new LogicalAsyncTraceElision());
            } else {
                elision.increment();
            }
        }
        return failure;
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * A suppressed diagnostic exception preserves the original failure type
     * while making the logical Oreslang async stack visible to ordinary Java,
     * Polyglot, Native Image, logs, and test tooling.
     */
    public static final class LogicalAsyncStackTrace extends RuntimeException {
        private final Trace trace;
        private final String logicalTrace;
        private final long elidedEvents;

        private LogicalAsyncStackTrace(Trace trace) {
            super("Oreslang logical async stack\n" + trace.render(), null, false, true);
            this.trace = trace;
            this.logicalTrace = trace.render();
            this.elidedEvents = trace.elidedEvents();
            setStackTrace(trace.toJavaStackTrace());
        }

        public String logicalTrace() {
            return logicalTrace;
        }

        public long elidedEvents() {
            return elidedEvents;
        }
    }

    /**
     * Compact marker used once the per-failure causal-trace budget is full.
     * The counter is deliberately saturating so diagnostics cannot wrap after
     * pathological runtimes.
     */
    public static final class LogicalAsyncTraceElision extends RuntimeException {
        private long elidedTraces = 1;

        private LogicalAsyncTraceElision() {
            super(null, null, false, false);
        }

        private synchronized void increment() {
            if (elidedTraces != Long.MAX_VALUE) elidedTraces++;
        }

        public synchronized long elidedTraces() {
            return elidedTraces;
        }

        @Override
        public synchronized String getMessage() {
            return elidedTraces + " additional Oreslang logical async trace(s) elided";
        }
    }

    private static StackTraceElement toStackTraceElement(String symbol, SourceSite site) {
        String generation = site.codeGenerationId();
        String className = generation == null || generation.isBlank()
                ? "oreslang.async"
                : "oreslang.async[" + generation + "]";
        return new StackTraceElement(
                className,
                symbol,
                site.sourceId(),
                site.line() > 0 ? site.line() : -1);
    }

    private static void appendFrame(StringBuilder out, String prefix, Frame frame) {
        out.append("\n    ").append(prefix).append(' ').append(frame.symbol());
        appendSite(out, frame.site());
    }

    private static void appendBoundary(
            StringBuilder out,
            String kind,
            SourceSite site,
            String detail) {
        out.append("\n    --- ").append(kind);
        if (detail != null && !detail.isBlank()) out.append(' ').append(detail);
        appendSite(out, site);
        out.append(" ---");
    }

    private static void appendSite(StringBuilder out, SourceSite site) {
        out.append(" (").append(site.sourceId());
        if (site.line() > 0) {
            out.append(':').append(site.line());
            if (site.column() > 0) out.append(':').append(site.column());
        }
        if (site.codeGenerationId() != null && !site.codeGenerationId().isBlank()) {
            out.append(" @gen=").append(site.codeGenerationId());
        }
        out.append(')');
    }
}
