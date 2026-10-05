package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorEntryContract;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class IsolationHotReloadTest {
    @Test
    void executionProfilesCoverJitAotAndHybrid() {
        assertTrue(ExecutionProfile.serverJit().guestJitAllowed());
        assertFalse(ExecutionProfile.serverJit().hostAheadOfTime());

        ExecutionProfile hybrid = ExecutionProfile.serverHybrid();
        assertTrue(hybrid.guestJitAllowed());
        assertTrue(hybrid.hostAheadOfTime());

        ExecutionProfile ios = ExecutionProfile.mobileAot(ExecutionProfile.Platform.IOS);
        assertTrue(ios.hostAheadOfTime());
        assertFalse(ios.guestJitAllowed());
        assertTrue(ios.supportsSourceHotReload());

        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionProfile(ExecutionProfile.Mode.JIT, ExecutionProfile.Platform.IOS));
    }

    @Test
    void capabilityAdmissionRejectsForbiddenApiBeforeGuestExecution() {
        var program = TypeChecker.check(Parser.parse("""
                pub routine main() => void {
                  stdio.stdout.write(process.context_id);
                }
                """));

        SecurityException denied = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertTrue(denied.getMessage().contains("PROCESS_INFO"));

        assertDoesNotThrow(() -> CapabilityChecker.check(program,
                IsolatePolicy.strictFaas().withCapabilities(IsolatePolicy.Capability.PROCESS_INFO)));
    }

    @Test
    void runtimeCapabilityCheckCannotBeBypassedByFacadeDispatch() throws Exception {
        IsolatePolicy noOutput = new IsolatePolicy(Set.of(), 64L * 1024 * 1024, 32, Duration.ofSeconds(5));
        Source source = Source.newBuilder(OresLanguage.ID, """
                pub routine main() => void {
                  stdio.stdout.write("forbidden");
                }
                """, "denied.ores").mimeType(OresLanguage.MIME_TYPE).buildLiteral();

        RuntimeException error = assertThrows(RuntimeException.class, () -> {
            try (Context context = noOutput.restrictedContextBuilder(ExecutionProfile.serverJit()).build()) {
                context.eval(source);
            }
        });
        assertTrue(error.getMessage().contains("STDOUT"));
    }

    @Test
    void hotReloadCreatesDistinctVersionedContextsWithoutFfi() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var first = hot.load("service.ores", """
                    pub routine main() => void { return; }
                    """);
            var second = hot.load("service.ores", """
                    pub routine main() => void {
                      val version = 2;
                      return;
                    }
                    """);

            assertNotEquals(first.id(), second.id());
            assertNotEquals(first.sha256(), second.sha256());
            assertNull(hot.active(), "staged code must not become active before successful startup");
            assertEquals(2, hot.liveGenerations());

            first.start();
            first.activate();
            assertEquals(first.id(), hot.active().id());

            second.start();
            second.activate();
            assertEquals(second.id(), hot.active().id());
            assertTrue(first.closed(), "unpinned old generations should be reclaimed after the switch");
            assertEquals(1, hot.liveGenerations());
        }
    }

    @Test
    void hotLoadStagesWithoutRunningMainUntilExplicitStart() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.load("staged.ores", """
                    pub routine main() => void {
                      val values = arr[1];
                      val boom = values[99];
                      return;
                    }
                    """);
            assertFalse(generation.started());
            assertThrows(RuntimeException.class, generation::start);
            assertTrue(generation.closed());
        }
    }

    @Test
    void rootInitNameIsOrdinaryAndNeverRunsImplicitly() throws Exception {
        String program = """
                fnc init(value: int) -> void {
                  val values = arr[1];
                  val boom = values[99];
                  return;
                }

                pub routine main() -> void {
                  return;
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "no-implicit-init.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .build()) {
            assertDoesNotThrow(() -> context.eval(source),
                    "a function merely named init must not execute during link/start");
        }
    }

    @Test
    void hotReloadRequiresExplicitCapability() {
        assertThrows(SecurityException.class,
                () -> new HotReloadManager(IsolatePolicy.strictFaas(), ExecutionProfile.serverJit()));
    }

    @Test
    void allExplicitStructuralParameterSpellingsWork() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Bar {
                  marker: 'brand'
                }

                pub interface Foo extends Bar {
                  markerBrand: 'marking/branding'
                }

                fnc first(y structural Foo) => void {
                  return;
                }

                @AllowStructural(y)
                fnc second(y Foo) => void {
                  return;
                }

                fnc third(@Structural Foo y) => void {
                  return;
                }

                pub routine main() => void {
                  val branded = obj{marker: "brand", markerBrand: "marking/branding"};
                  first(branded);
                  second(branded);
                  third(branded);
                  return;
                }
                """)));
    }

    @Test
    void structuralPermissionIsNotImplicit() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub interface Foo {
                  marker: 'brand'
                }

                fnc nominal(Foo y) => void { return; }

                pub routine main() => void {
                  val branded = obj{marker: "brand"};
                  nominal(branded);
                  return;
                }
                """)));
    }

    @Test
    void extractedMethodValueKeepsReceiverAndSelfCannotBeRebound() throws Exception {
        String output = run("""
                define class Box as
                  val int value;

                  pub get() => int {
                    return self.value;
                  }
                end

                pub routine main() => void {
                  val box = new Box(17);
                  val Fnc<int> callback = box.get;
                  stdio.stdout.write(callback())
                }
                """);
        assertEquals("17", output);

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub bad() => void {
                    self = new Box();
                    return;
                  }
                end
                """)));
    }


    @Test
    void persistentActorEntryIsAbiPinnedBeforeGenerationAllocation() {
        IsolatePolicy policy = IsolatePolicy.developer();
        String source = """
                define class Worker extends Actor<int, String, String> as
                  private helper(): int { return 1; }
                  pub receive(message: int): void {
                    val observed = self.helper();
                    return;
                  }
                end
                export entry Worker;
                """;
        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.SHARED,
                List.of(),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        try (HotReloadManager hot =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.loadActorEntry("worker.ores", source, contract);
            assertFalse(generation.started(), "ABI admission must not execute guest code");
            assertEquals("Worker", generation.entryExport().orElseThrow().name());
            assertEquals(contract.abiDigest(), generation.actorEntryAbiDigest().orElseThrow());
            assertEquals(1, hot.liveGenerations());
        }
    }

    @Test
    void genericHotLoadCannotBypassPersistentActorContract() {
        IsolatePolicy policy = IsolatePolicy.developer();
        String source = """
                define class Worker extends Actor<int, String, String> as
                  pub receive(message: int): void { return; }
                end
                export entry Worker;
                """;

        try (HotReloadManager hot =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            IllegalArgumentException generic = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.load("worker.ores", source));
            assertTrue(generic.getMessage().contains("loadActorEntry"));

            IllegalArgumentException explicit = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.loadEntry("worker.ores", source));
            assertTrue(explicit.getMessage().contains("loadActorEntry"));
            assertEquals(0, hot.liveGenerations(),
                    "rejected actor entry must not allocate a generation");
        }
    }

    @Test
    void activeActorGenerationRejectsAbiDriftButAllowsPrivateImplementationChange() {
        IsolatePolicy policy = IsolatePolicy.developer();
        ActorEntryContract intContract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.SHARED,
                List.of(),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));
        String v1 = """
                define class Worker extends Actor<int, String, String> as
                  private helper(): int { return 1; }
                  pub receive(message: int): void { val x = self.helper(); return; }
                end
                export entry Worker;
                """;
        String v2 = """
                define class Worker extends Actor<int, String, String> as
                  private helper(): int { return 2; }
                  pub receive(message: int): void { val x = self.helper(); return; }
                end
                export entry Worker;
                """;

        try (HotReloadManager hot =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var first = hot.loadActorEntry("worker.ores", v1, intContract);
            first.start();
            first.activate();

            var compatible = hot.loadActorEntry("worker.ores", v2, intContract);
            assertNotEquals(first.sha256(), compatible.sha256());
            assertEquals(
                    first.actorEntryAbiDigest().orElseThrow(),
                    compatible.actorEntryAbiDigest().orElseThrow());
            compatible.start();
            compatible.activate();
            assertSame(compatible, hot.active("worker.ores"));

            ActorEntryContract stringContract = ActorEntryContract.of(
                    "Worker",
                    Ast.ActorKind.SHARED,
                    List.of(),
                    Ast.TypeRef.simple("String"),
                    Ast.TypeRef.simple("String"),
                    Ast.TypeRef.simple("String"));
            String incompatibleSource = """
                    define class Worker extends Actor<String, String, String> as
                      pub receive(message: String): void { return; }
                    end
                    export entry Worker;
                    """;
            int liveBeforeDrift = hot.liveGenerations();
            IllegalStateException drift = assertThrows(
                    IllegalStateException.class,
                    () -> hot.loadActorEntry(
                            "worker.ores",
                            incompatibleSource,
                            stringContract));
            assertTrue(drift.getMessage().contains("ABI drift"));
            assertEquals(liveBeforeDrift, hot.liveGenerations(),
                    "ABI drift must fail before generation/context allocation");
            assertSame(compatible, hot.active("worker.ores"),
                    "rejected admission must leave the old generation active");

            IllegalStateException roleChange = assertThrows(
                    IllegalStateException.class,
                    () -> hot.loadEntry("worker.ores", """
                            pub fnc run() -> void { return; }
                            export entry run;
                            """));
            assertTrue(roleChange.getMessage().contains("persistent-actor"));
            assertEquals(liveBeforeDrift, hot.liveGenerations(),
                    "actor/non-actor role drift must fail before allocation");
        }
    }

    @Test
    void drainingActorGenerationStillPinsItsAbiUntilFinalLeaseRelease() throws Exception {
        IsolatePolicy policy = IsolatePolicy.developer();
        String source = """
                define class Worker extends Actor<int, String, String> as
                  pub receive(message: int): void { return; }
                end
                export entry Worker;
                """;
        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.SHARED,
                List.of(),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        try (HotReloadManager hot =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.loadActorEntry("worker.ores", source, contract);
            generation.start();
            generation.activate();

            HotReloadManager.GenerationLease lease = hot.pinActive("worker.ores");
            hot.retire(generation.id());
            assertEquals(HotReloadManager.GenerationState.DRAINING, generation.state());
            assertNull(hot.active("worker.ores"));

            IllegalStateException roleChange = assertThrows(
                    IllegalStateException.class,
                    () -> hot.loadEntry("worker.ores", """
                            pub fnc run() -> void { return; }
                            export entry run;
                            """));
            assertTrue(roleChange.getMessage().contains("persistent-actor"));
            assertEquals(1, hot.liveGenerations(),
                    "draining actor generation must continue to pin its ABI");

            lease.close();
            long deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while ((!generation.closed() || hot.liveGenerations() != 0)
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(generation.closed());
            assertEquals(0, hot.liveGenerations());
        }
    }

    @Test
    void actorEntryIsolationMustMatchRuntimeExecutionDomain() {
        IsolatePolicy policy = IsolatePolicy.developer();
        String source = """
                define class Worker extends IsoActor<int, String, String> as
                  pub receive(message: int): void { return; }
                end
                export entry Worker;
                """;
        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.PRIVATE,
                List.of(),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        try (HotReloadManager wrong =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> wrong.loadActorEntry("worker.ores", source, contract));
            assertTrue(failure.getMessage().contains("TRUSTED_ISOACTOR_JIT"));
            assertEquals(0, wrong.liveGenerations());
        }

        try (HotReloadManager correct = new HotReloadManager(
                policy,
                policy,
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.TRUSTED_ISOACTOR_JIT)) {
            var generation = correct.loadActorEntry("worker.ores", source, contract);
            assertEquals(
                    HotReloadManager.ExecutionDomain.TRUSTED_ISOACTOR_JIT,
                    generation.executionDomain());
            assertFalse(generation.executionDomain().spawnedIsolate(),
                    "trusted isoactors stay inside the primary Graal isolate");
        }
    }


    @Test
    void actorEntryContractRejectsConstructorAndProtocolDriftBeforeAllocation() {
        IsolatePolicy policy = IsolatePolicy.developer();
        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.SHARED,
                List.of(Ast.TypeRef.simple("int")),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        try (HotReloadManager hot =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            IllegalArgumentException constructorDrift = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.loadActorEntry("worker.ores", """
                            define class Worker extends Actor<int, String, String> as
                              constructor(seed: String) { return; }
                              pub receive(message: int): void { return; }
                            end
                            export entry Worker;
                            """, contract));
            assertTrue(constructorDrift.getMessage().contains("constructor"));

            IllegalArgumentException protocolDrift = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.loadActorEntry("worker.ores", """
                            define class Worker extends Actor<int, int, String> as
                              constructor(seed: int) { return; }
                              pub receive(message: int): void { return; }
                            end
                            export entry Worker;
                            """, contract));
            assertTrue(protocolDrift.getMessage().contains("Actor<Message, Reply, Error>"));
            assertEquals(0, hot.liveGenerations(),
                    "contract mismatch must never allocate a generation");
        }
    }

    @Test
    void untrustedActorEntryRequiresSpawnedUntrustedDomain() {
        IsolatePolicy supervisor = IsolatePolicy.developer();
        String source = """
                define class Worker extends UntrustedActor<int, String, String> as
                  pub receive(message: int): void { return; }
                end
                export entry Worker;
                """;
        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.UNTRUSTED,
                List.of(),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        try (HotReloadManager wrong =
                     new HotReloadManager(supervisor, ExecutionProfile.serverJit())) {
            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> wrong.loadActorEntry("worker.ores", source, contract));
            assertTrue(failure.getMessage().contains("UNTRUSTED_JIT"));
            assertEquals(0, wrong.liveGenerations());
        }

        try (HotReloadManager correct = new HotReloadManager(
                supervisor,
                IsolatePolicy.untrustedActor(),
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.UNTRUSTED_JIT)) {
            assertEquals(
                    HotReloadManager.ExecutionDomain.UNTRUSTED_JIT,
                    correct.executionDomain());
            assertTrue(correct.executionDomain().spawnedIsolate());
            assertTrue(correct.guestPolicy().adversarial());
        }
    }

    @Test
    void untrustedActorEntryLoadsWhenNativeIsolateLibraryIsAvailable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("polyglot.engine.IsolateLibrary") != null,
                "positive spawned-isolate admission requires the native isolate library");

        IsolatePolicy supervisor = IsolatePolicy.developer();
        String source = """
                define class Worker extends UntrustedActor<int, String, String> as
                  pub receive(message: int): void { return; }
                end
                export entry Worker;
                """;
        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.UNTRUSTED,
                List.of(),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        try (HotReloadManager hot = new HotReloadManager(
                supervisor,
                IsolatePolicy.untrustedActor(),
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.UNTRUSTED_JIT)) {
            var generation = hot.loadActorEntry("worker.ores", source, contract);
            assertEquals(
                    HotReloadManager.ExecutionDomain.UNTRUSTED_JIT,
                    generation.executionDomain());
            assertTrue(generation.executionDomain().spawnedIsolate());
            assertTrue(generation.guestPolicy().adversarial());
            assertFalse(generation.started(),
                    "ABI admission must not execute the untrusted actor");
        }
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "receiver.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
