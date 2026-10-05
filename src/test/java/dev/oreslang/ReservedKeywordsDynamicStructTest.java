package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ReservedKeywordsDynamicStructTest {
    @Test
    void reservedWordsRemainCallableNamesAndMapKeysOnly() {
        var tokens = new Lexer("stop do done").scan();
        assertEquals(Token.Type.STOP, tokens.get(0).type());
        assertEquals(Token.Type.DO, tokens.get(1).type());
        assertEquals(Token.Type.DONE, tokens.get(2).type());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc stop(): int { return 1; }
                  routine do(): int { return 2; }
                  fnc done(): int { return 3; }

                  pub fnc main(): int {
                    let Map<string, int> values = new Map<string, int>();
                    values["stop"] = 4;
                    values["do"] = 5;
                    values["done"] = 6;
                    return stop() + do() + done()
                        + values["stop"] + values["do"] + values["done"];
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc stop(): int { return 1; }
                  fnc main(): void {
                    val callback = stop;
                    return;
                  }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc main(): void {
                    val stop = 1;
                    return;
                  }
                end
                """));
    }

    @Test
    void objAndDynamicStructAreShelved() {
        IllegalArgumentException obj = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(): void {
                          val value = obj{foo: 1};
                          return;
                        }
                        """));
        assertTrue(obj.getMessage().contains("obj{} is shelved"));

        IllegalArgumentException dynamic = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          let DynamicStruct<int> bag = new DynamicStruct<int>();
                          return;
                        }
                        """)));
        assertTrue(dynamic.getMessage().contains("DynamicStruct is shelved"));
    }

    @Test
    void mapAllowsDynamicInsertionWithTypedKeysAndValues() throws Exception {
        String program = """
                pub fnc main(): void {
                    let Map<string, int> bag = new Map<string, int>();
                    bag["stop"] = 1;
                    bag["done"] = 2;
                    const key = "do";
                    bag[key] = 3;
                    stdio.println(bag["stop"] + bag["done"] + bag[key]);
                    return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "map.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("6"));
    }

    @Test
    void mapChecksKeyAndValueTypes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                    let Map<string, int> bag = new Map<string, int>();
                    bag["answer"] = "forty-two";
                    return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                    let Map<string, int> bag = new Map<string, int>();
                    bag[1] = 42;
                    return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): void {
                    let Map<int, string> bag = new Map<int, string>();
                    bag[1] = "one";
                    val value = bag[1];
                    return;
                }
                """)));
    }
}
