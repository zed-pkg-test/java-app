package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OresAsyncTraceTest {

    private static OresAsyncTrace.SourceSite site(int line) {
        return new OresAsyncTrace.SourceSite(
                "trace-test.ores",
                line,
                7,
                "19");
    }

    @Test
    void repeatedSelfTailAwaitIsRunLengthCompressed() {
        OresAsyncTrace.Frame walk =
                new OresAsyncTrace.Frame("walk", site(10));
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(walk);

        for (int i = 0; i < 100_000; i++) {
            trace.tailAwait(walk, site(14));
        }

        assertEquals(1, trace.retainedEventCount(),
                "tail recursion must not rebuild an O(n) diagnostic stack");
        assertEquals(0, trace.elidedEvents());
        assertTrue(trace.render().contains("repeated 100000 times"));
        assertTrue(trace.render().contains("@gen=19"));
        assertTrue(trace.render().contains("trace-test.ores:14:7"));
    }

    @Test
    void nonRepeatingHistoryIsBoundedAndReportsElision() {
        OresAsyncTrace.Frame root =
                new OresAsyncTrace.Frame("root", site(1));
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(root);

        for (int i = 0; i < 400; i++) {
            trace.awaitAt(new OresAsyncTrace.SourceSite(
                    "trace-test.ores",
                    i + 2,
                    3,
                    "19"));
        }

        assertTrue(trace.retainedEventCount() <= 96);
        assertTrue(trace.elidedEvents() > 0);
        assertTrue(trace.render().contains("older async trace events elided"));
    }

    @Test
    void logicalTraceAttachmentPreservesOriginalFailureAndStructuredFrames() {
        OresAsyncTrace.Frame caller =
                new OresAsyncTrace.Frame("caller", site(20));
        OresAsyncTrace.Frame callee =
                new OresAsyncTrace.Frame("callee", site(30));
        OresAsyncTrace.Trace trace =
                OresAsyncTrace.root(caller).child(callee, site(25));
        trace.awaitAt(site(31));

        IllegalStateException failure = new IllegalStateException("boom");
        assertSame(failure, OresAsyncTrace.attach(failure, trace));
        assertEquals(1, failure.getSuppressed().length);

        OresAsyncTrace.LogicalAsyncStackTrace logical =
                assertInstanceOf(
                        OresAsyncTrace.LogicalAsyncStackTrace.class,
                        failure.getSuppressed()[0]);
        assertTrue(logical.logicalTrace().contains("callee"));
        assertTrue(logical.logicalTrace().contains("caller"));
        assertTrue(logical.logicalTrace().contains("--- await"));
        assertTrue(logical.getStackTrace().length >= 3);

        OresAsyncTrace.attach(failure, trace);
        assertEquals(1, failure.getSuppressed().length,
                "the same logical trace must not be attached twice");
    }

    @Test
    void failureKeepsChildAndParentLogicalTracesAcrossSchedulerAwait() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresAsyncTrace.Trace childTrace = OresAsyncTrace.root(
                    new OresAsyncTrace.Frame("child_async", site(60)));
            OresAsyncTrace.Trace parentTrace = OresAsyncTrace.root(
                    new OresAsyncTrace.Frame("parent_async", site(70)));
            OresFuture<Integer> child = new OresFuture<>();

            OresFuture<Integer> parent = scheduler.start(new OresScheduler.Task<>() {
                private int pc;

                @Override
                public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(parentTrace)) {
                        if (pc++ == 0) {
                            parentTrace.awaitAt(site(71));
                            return OresScheduler.await(child);
                        }

                        assertNotNull(resume.failure());
                        Throwable failure = OresFuture.unwrap(resume.failure());
                        OresAsyncTrace.attach(failure, parentTrace);
                        if (failure instanceof RuntimeException runtime) throw runtime;
                        if (failure instanceof Error error) throw error;
                        throw new RuntimeException(failure);
                    }
                }
            });

            OresFuture<Void> producer = scheduler.startSync(() -> {
                try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(childTrace)) {
                    childTrace.awaitAt(site(61));
                    IllegalStateException failure =
                            new IllegalStateException("async child failed");
                    OresAsyncTrace.attach(failure, childTrace);
                    child.failFromRuntime(failure);
                    return null;
                }
            });

            producer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            java.util.concurrent.ExecutionException thrown = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> parent.get(5, java.util.concurrent.TimeUnit.SECONDS));

            Throwable failure = thrown.getCause();
            assertInstanceOf(IllegalStateException.class, failure);

            java.util.List<OresAsyncTrace.LogicalAsyncStackTrace> logical =
                    java.util.Arrays.stream(failure.getSuppressed())
                            .filter(OresAsyncTrace.LogicalAsyncStackTrace.class::isInstance)
                            .map(OresAsyncTrace.LogicalAsyncStackTrace.class::cast)
                            .toList();

            assertEquals(2, logical.size(),
                    "child failure and awaiting parent must retain distinct causal traces");
            assertTrue(logical.stream()
                    .anyMatch(trace -> trace.logicalTrace().contains("child_async")));
            assertTrue(logical.stream()
                    .anyMatch(trace -> trace.logicalTrace().contains("parent_async")
                            && trace.logicalTrace().contains("--- await")));
        }
    }

    @Test
    void actorBoundaryIsRetainedAsLogicalCausalMetadata() {
        OresAsyncTrace.Frame worker =
                new OresAsyncTrace.Frame("Worker.receive_message", site(50));
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(worker);
        trace.boundary(OresAsyncTrace.BoundaryKind.ACTOR_MESSAGE, site(49));

        assertTrue(trace.render().contains("actor-message"));
        assertTrue(trace.render().contains("Worker.receive_message"));
    }
    @Test
    void attachedLogicalCausalTracesAreBoundedPerFailure() {
        IllegalStateException failure = new IllegalStateException("fanout");
        for (int i = 0; i < 100; i++) {
            OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                    new OresAsyncTrace.Frame("waiter_" + i, site(100 + i)));
            OresAsyncTrace.attach(failure, trace);
        }

        long logical = java.util.Arrays.stream(failure.getSuppressed())
                .filter(OresAsyncTrace.LogicalAsyncStackTrace.class::isInstance)
                .count();
        java.util.List<OresAsyncTrace.LogicalAsyncTraceElision> markers =
                java.util.Arrays.stream(failure.getSuppressed())
                        .filter(OresAsyncTrace.LogicalAsyncTraceElision.class::isInstance)
                        .map(OresAsyncTrace.LogicalAsyncTraceElision.class::cast)
                        .toList();

        assertEquals(32, logical);
        assertEquals(1, markers.size());
        assertEquals(68, markers.getFirst().elidedTraces());
        assertEquals(33, failure.getSuppressed().length);
    }


}
