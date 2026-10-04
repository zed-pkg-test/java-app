package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class StatefulModuleInjectionTest {

    @Test
    void parserAndTypecheckerDistinguishGlobalAndSlotSingletonModules() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define global module GlobalCounter
                  let int value = 0;

                  pub fnc increment(int by) : int {
                    value = value + by;
                    return value;
                  }
                end

                define singleton module SlotCounter
                  let int value = 10;

                  pub fnc increment(int by) : int {
                    value = value + by;
                    return value;
                  }
                end

                define class Handler as
                  @InjectGlobal(GlobalCounter)
                  private val GlobalRef<GlobalCounter> global_counter;

                  @InjectSingleton(SlotCounter)
                  private val SingletonRef<SlotCounter> slot_counter;

                  pub run() : int {
                    val int process_value = await self.global_counter.increment(2);
                    return self.slot_counter.increment(process_value);
                  }
                end
                """));

        Ast.ModuleDecl global = program.modules().stream()
                .filter(module -> module.name().equals("GlobalCounter"))
                .findFirst().orElseThrow();
        Ast.ModuleDecl singleton = program.modules().stream()
                .filter(module -> module.name().equals("SlotCounter"))
                .findFirst().orElseThrow();

        assertEquals(Ast.ModuleStorage.GLOBAL, global.storage());
        assertEquals(Ast.ModuleStorage.SINGLETON, singleton.storage());
        assertDoesNotThrow(() -> OwnershipChecker.check(program));
        assertDoesNotThrow(
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void injectedRefsExecuteThroughTheirAuthorities() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, """
                define global module GlobalCounter
                  let int value = 0;

                  pub fnc increment(int by) : int {
                    value = value + by;
                    return value;
                  }
                end

                define singleton module SlotCounter
                  let int value = 10;

                  pub fnc increment(int by) : int {
                    value = value + by;
                    return value;
                  }
                end

                define class Handler as
                  @InjectGlobal(GlobalCounter)
                  private val GlobalRef<GlobalCounter> global_counter;

                  @InjectSingleton(SlotCounter)
                  private val SingletonRef<SlotCounter> slot_counter;

                  pub run() : int {
                    val int process_value = await self.global_counter.increment(2);
                    return self.slot_counter.increment(process_value);
                  }
                end

                pub fnc main() : void {
                  val Handler handler = new Handler();
                  stdio.println(handler.run());
                  stdio.println(handler.run());
                  return;
                }
                """, "stateful-modules.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertEquals(
                "12\n16",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r\n", "\n"));
    }

    @Test
    void injectedCapabilityCannotBeMutablePublicOrConstructorSupplied() {
        IllegalArgumentException mutable = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define global module Config
                          pub const Symbol ready = :ready;
                        end

                        define class Bad as
                          @InjectGlobal(Config)
                          private let GlobalRef<Config> config;
                        end
                        """)));
        assertTrue(mutable.getMessage().contains("immutable"));

        IllegalArgumentException publicField = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module Cache
                          pub const Symbol ready = :ready;
                        end

                        define class Bad as
                          @InjectSingleton(Cache)
                          pub val SingletonRef<Cache> cache;
                        end
                        """)));
        assertTrue(publicField.getMessage().contains("private"));
    }

    @Test
    void declarativeSymbolExportRequiresGlobalConstLiteral() {
        Ast.Program valid = TypeChecker.check(Parser.parse("""
                define global module ErrorCodes
                  @ExportToUntrusted
                  pub const Symbol timeout = :timeout;
                end
                """));
        assertDoesNotThrow(
                () -> CapabilityChecker.check(valid, IsolatePolicy.developer()));

        IllegalArgumentException invalid = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module ErrorCodes
                          @ExportToUntrusted
                          pub const Symbol timeout = :timeout;
                        end
                        """)));
        assertTrue(invalid.getMessage().contains("global module"));
    }

    @Test
    void untrustedPolicyCannotLoadGlobalModulesOrGlobalRefs() {
        Ast.Program global = TypeChecker.check(Parser.parse("""
                define global module Secret
                  pub const Symbol state = :state;
                end
                """));

        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(global, IsolatePolicy.strictFaas()));

        Ast.Program injected = TypeChecker.check(Parser.parse("""
                define global module Secret
                  pub const Symbol state = :state;
                end

                define class Holder as
                  @InjectGlobal(Secret)
                  private val GlobalRef<Secret> secret;
                end
                """));

        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(injected, IsolatePolicy.strictFaas()));
    }
    @Test
    void untrustedPolicyCannotLoadSingletonModulesOrSingletonRefs() {
        Ast.Program singleton = TypeChecker.check(Parser.parse("""
                define singleton module LocalSecret
                  pub const Symbol state = :state;
                end
                """));

        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(singleton, IsolatePolicy.strictFaas()));

        Ast.Program injected = TypeChecker.check(Parser.parse("""
                define singleton module LocalSecret
                  pub const Symbol state = :state;
                end

                define class Holder as
                  @InjectSingleton(LocalSecret)
                  private val SingletonRef<LocalSecret> secret;
                end
                """));

        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(injected, IsolatePolicy.strictFaas()));

        assertThrows(
                IllegalArgumentException.class,
                () -> IsolatePolicy.strictFaas().withCapabilities(
                        IsolatePolicy.Capability.SINGLETON_STATE));
    }

    @Test
    void persistentStateRequiresExplicitSchemaType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define global module Counter
                          let value = 0;
                        end
                        """)));
        assertTrue(failure.getMessage().contains("explicit type"));
        assertTrue(failure.getMessage().contains("schema"));
    }

    @Test
    void globalValReadsAreFutureTypedButConstsRemainImmediate() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define global module Config
                  pub val int revision = 1;
                  pub const Symbol ready = :ready;
                end

                fnc revision() : int {
                  return await Config.revision;
                }

                fnc ready() : Symbol {
                  return Config.ready;
                }
                """)));
    }

    @Test
    void privateInjectedCapabilityCannotBeReadThroughAnotherObject() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define global module Secret
                          pub const Symbol ready = :ready;
                        end

                        define class Holder as
                          @InjectGlobal(Secret)
                          private val GlobalRef<Secret> secret;
                        end

                        fnc leak(Holder holder) : GlobalRef<Secret> {
                          return holder.secret;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("private field"));
    }

    @Test
    void failedGlobalTurnDoesNotPublishPartialBindingReplacement() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, """
                define global module Counter
                  let int value = 0;

                  pub fnc fail() : int {
                    value = 99;
                    switch :miss {
                      :other -> { return value; }
                    }
                    return value;
                  }

                  pub fnc get() : int {
                    return value;
                  }
                end

                pub fnc main() : void {
                  try {
                    val int ignored = await Counter.fail();
                  } catch (err) {
                    stdio.println(await Counter.get());
                  }
                  return;
                }
                """, "global-failed-turn.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertEquals("0", output.toString(StandardCharsets.UTF_8).trim());
    }


}
