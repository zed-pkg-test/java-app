package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.gpu.GpuRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class GpuKeywordTest {
    @AfterEach
    void clearGpuBackend() {
        GpuRuntime.clearBackend();
    }

    @Test
    void lexerAndParserPreserveGpuModifier() {
        var tokens = new Lexer("pub gpu fnc kernel(int x) => int { return x; }").scan();
        assertTrue(tokens.stream().anyMatch(token -> token.type() == Token.Type.GPU));

        Ast.Program program = Parser.parse("pub gpu fnc kernel(int x) => int { return x; }");
        Ast.FunctionDecl kernel = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.CallableKind.FNC, kernel.kind());
        assertTrue(kernel.gpu());
        assertEquals(Ast.Visibility.PUBLIC, kernel.visibility());
    }

    @Test
    void gpuModifierIsRestrictedToNamedTopLevelOrModuleCallables() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                gpu define class Bad
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module m
                  define class Bad
                    gpu work() => int {
                      return 1;
                    }
                  end
                end
                """));
    }

    @Test
    void gpuCallablesMayComposeGpuCallables() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                gpu fnc square(int x) => int {
                  return x * x;
                }

                gpu routine kernel(int x) => int {
                  return square(x);
                }
                """)));
    }

    @Test
    void gpuSignaturesAcceptTransferValuesAndRejectClassObjects() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                gpu fnc transform(Array<complex> values, Option<int> maybe) => Array<complex> {
                  return values;
                }
                """)));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module m
                          define class Box
                          end

                          gpu fnc bad(Box value) => int {
                            return 1;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("non-transferable named type 'Box'"));
    }

    @Test
    void gpuArrayAndStreamAreCoreDeviceTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc upload(Array<int> values) => GpuArray<int> {
                  return GpuArray.from_cpu(values);
                }

                fnc as_stream(GpuArray<int> values) => GpuStream<int> {
                  return values.stream();
                }

                fnc download(GpuArray<int> values) => Array<int> {
                  return values.copy_to_cpu();
                }

                gpu fnc sum_array(GpuArray<int> values) => int {
                  let int total = 0;
                  for (val value of values) {
                    total = total + value;
                  }
                  return total;
                }

                gpu fnc sum_stream(GpuStream<int> values) => int {
                  let int total = 0;
                  for (val value of values) {
                    total = total + value;
                  }
                  return total;
                }
                """)));
    }

    @Test
    void cpuCannotIndexOrConsumeGpuResidentData() {
        IllegalArgumentException arrayFailure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(GpuArray<int> values) => int {
                          return values[0];
                        }
                        """)));
        assertTrue(arrayFailure.getMessage().contains("GpuArray indexing is GPU-only"));

        IllegalArgumentException streamFailure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(GpuStream<int> values) => int {
                          for (val value of values) {
                            return value;
                          }
                          return 0;
                        }
                        """)));
        assertTrue(streamFailure.getMessage().contains("GpuStream consumption is GPU-only"));
    }

    @Test
    void gpuArrayAndStreamRoundTripThroughBackendWithoutCpuProcessing() throws Exception {
        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "gpu-memory-test"; }

            @Override
            public Object invoke(GpuRuntime.Invocation invocation) {
                throw new AssertionError("no gpu callable should execute in this transfer-only test");
            }

            @Override
            public Object uploadArray(java.util.List<?> values) {
                return values;
            }

            @Override
            public java.util.List<?> downloadArray(Object backendToken, long length) {
                assertTrue(backendToken instanceof java.util.List<?>);
                assertEquals(length, ((java.util.List<?>) backendToken).size());
                return (java.util.List<?>) backendToken;
            }
        });

        String output = run("""
                pub routine main() => void {
                  val Array<int> host = arr[1, 2, 3];
                  val GpuArray<int> device = GpuArray.from_cpu(host);
                  val GpuStream<int> stream = device.stream();
                  val GpuArray<int> collected = stream.collect();
                  let Array<int> copied = collected.copy_to_cpu();
                  copied[0] = 42;
                  stdio.stdout.write(copied[0]);
                }
                """);

        assertEquals("42", output);
    }

    @Test
    void coreGpuResourcesRequireGpuCapabilityAtAdmission() {
        String source = """
                pub routine main() => void {
                  val Array<int> host = arr[1, 2, 3];
                  val GpuArray<int> device = GpuArray.from_cpu(host);
                  return;
                }
                """;

        assertThrows(SecurityException.class,
                () -> OresCompiler.validateForIsolate(source, IsolatePolicy.strictFaas()));

        assertDoesNotThrow(() -> OresCompiler.validateForIsolate(
                source,
                IsolatePolicy.strictFaas().withCapabilities(IsolatePolicy.Capability.GPU)));
    }

    @Test
    void gpuCoreNamesCannotBeShadowedByDeclarations() {
        IllegalArgumentException classFailure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class GpuArray
                        end
                        """)));
        assertTrue(classFailure.getMessage().contains("reserved by the Oreslang GPU core prelude"));

        IllegalArgumentException callableFailure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc GpuStream() => int {
                          return 1;
                        }
                        """)));
        assertTrue(callableFailure.getMessage().contains("reserved by the Oreslang GPU core prelude"));
    }

    @Test
    void gpuCallableCannotCallCpuCallable() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc cpu_helper(int x) => int {
                          return x + 1;
                        }

                        gpu fnc kernel(int x) => int {
                          return cpu_helper(x);
                        }
                        """)));
        assertTrue(failure.getMessage().contains("cannot call CPU callable"));
    }

    @Test
    void gpuCallableRejectsHostEffectsAndUnsupportedControlEffects() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                gpu routine kernel() => void {
                  stdio.println("no");
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                gpu routine kernel() => void {
                  try {
                    return;
                  } catch (err) {
                    return;
                  }
                }
                """)));
    }

    @Test
    void cpuToGpuCallsUseInstalledBackendInsteadOfEvaluatingBodyOnCpu() throws Exception {
        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "test-gpu"; }

            @Override
            public Object invoke(GpuRuntime.Invocation invocation) {
                assertEquals("__root__.add", invocation.callable());
                assertEquals(GpuRuntime.CallableKind.FNC, invocation.callableKind());
                assertEquals(2L, ((Number) invocation.arguments().get(0)).longValue());
                assertEquals(3L, ((Number) invocation.arguments().get(1)).longValue());
                return 41L;
            }
        });

        String output = run("""
                gpu fnc add(int a, int b) => int {
                  return 999;
                }

                pub routine main() => void {
                  stdio.stdout.write(add(2, 3));
                }
                """);

        assertEquals("41", output);
    }

    @Test
    void gpuBackendCannotMutateGuestCollectionsOrReturnUnknownHostObjects() {
        java.util.ArrayList<Object> nested = new java.util.ArrayList<>();
        nested.add(7L);
        java.util.ArrayList<Object> original = new java.util.ArrayList<>();
        original.add(nested);

        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "immutability-test"; }

            @Override
            public Object invoke(GpuRuntime.Invocation invocation) {
                assertNotSame(original, invocation.arguments().getFirst());
                @SuppressWarnings("unchecked")
                java.util.List<Object> outer = (java.util.List<Object>) invocation.arguments().getFirst();
                @SuppressWarnings("unchecked")
                java.util.List<Object> inner = (java.util.List<Object>) outer.getFirst();
                assertNotSame(nested, inner);
                assertThrows(UnsupportedOperationException.class, () -> outer.add(9L));
                assertThrows(UnsupportedOperationException.class, () -> inner.add(9L));
                return outer;
            }
        });

        Object result = new GpuRuntime().dispatch(
                "test.kernel",
                GpuRuntime.CallableKind.FNC,
                java.util.List.of(original));

        assertTrue(result instanceof java.util.List<?>);
        @SuppressWarnings("unchecked")
        java.util.List<Object> resultList = (java.util.List<Object>) result;
        assertThrows(UnsupportedOperationException.class, () -> resultList.add(10L));

        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "bad-result-test"; }
            @Override public Object invoke(GpuRuntime.Invocation invocation) { return new Object(); }
        });

        assertThrows(GpuRuntime.GpuTransferException.class,
                () -> new GpuRuntime().dispatch(
                        "test.kernel",
                        GpuRuntime.CallableKind.FNC,
                        java.util.List.of(1L)));
    }

    @Test
    void gpuResultsReenterGuestAsOwnedMutableArrays() throws Exception {
        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "array-result-test"; }
            @Override public Object invoke(GpuRuntime.Invocation invocation) {
                return java.util.List.of(1L);
            }
        });

        String output = run("""
                gpu fnc make_values() => Array<int> {
                  return arr[0];
                }

                pub routine main() => void {
                  let Array<int> values = make_values();
                  values[0] = 42;
                  stdio.stdout.write(values[0]);
                }
                """);

        assertEquals("42", output);
    }

    @Test
    void gpuWireFormatRejectsNullEscapes() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuRuntime.OptionValue(true, null));

        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "null-result-test"; }
            @Override public Object invoke(GpuRuntime.Invocation invocation) {
                return java.util.Arrays.asList((Object) null);
            }
        });

        assertThrows(GpuRuntime.GpuTransferException.class,
                () -> new GpuRuntime().dispatch(
                        "test.kernel",
                        GpuRuntime.CallableKind.FNC,
                        java.util.List.of(1L)));
    }

    @Test
    void gpuNeverSilentlyFallsBackToCpu() {
        GpuRuntime.GpuUnavailableException unavailable = assertThrows(
                GpuRuntime.GpuUnavailableException.class,
                () -> new GpuRuntime().dispatch(
                        "test.kernel",
                        GpuRuntime.CallableKind.ROUTINE,
                        java.util.List.of()));
        assertTrue(unavailable.getMessage().contains("CPU fallback is forbidden"));

        PolyglotException failure = assertThrows(PolyglotException.class, () -> run("""
                pub gpu routine main() => void {
                  return;
                }
                """));

        assertTrue(failure.isGuestException());
    }

    @Test
    void isolateAdmissionRequiresExplicitGpuCapability() {
        String source = """
                gpu fnc kernel(int x) => int {
                  return x;
                }
                """;

        assertThrows(SecurityException.class,
                () -> OresCompiler.validateForIsolate(source, IsolatePolicy.strictFaas()));

        IsolatePolicy gpuPolicy = IsolatePolicy.strictFaas()
                .withCapabilities(IsolatePolicy.Capability.GPU);
        assertDoesNotThrow(() -> OresCompiler.validateForIsolate(source, gpuPolicy));
    }

    @Test
    void gpuEffectParticipatesInExportedAbiDigest() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        var cpu = compiler.compile(Map.of("kernel.ores", """
                pub fnc kernel(int x) => int {
                  return x;
                }
                """));
        String cpuAbi = cpu.units().get("kernel.ores").abiDigest();

        var gpu = compiler.compile(Map.of("kernel.ores", """
                pub gpu fnc kernel(int x) => int {
                  return x;
                }
                """));
        String gpuAbi = gpu.units().get("kernel.ores").abiDigest();

        assertNotEquals(cpuAbi, gpuAbi);
        assertTrue(gpu.rebuilt("kernel.ores"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "gpu-test.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .arguments(OresLanguage.ID,
                        new String[]{"--ores-capabilities=STDOUT,GPU"})
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
