package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TemplateLiteralTest {
    @Test
    void desugarsInterpolationIntoGroupedStringExpressions() {
        List<Token> tokens = new Lexer("`prefix ${name} suffix`".replace("`", "\u0060")).scan();
        assertEquals(List.of(
                Token.Type.LPAREN, Token.Type.STRING, Token.Type.PLUS,
                Token.Type.LPAREN, Token.Type.IDENT, Token.Type.RPAREN,
                Token.Type.PLUS, Token.Type.STRING, Token.Type.RPAREN,
                Token.Type.EOF), tokens.stream().map(Token::type).toList());
        assertEquals("prefix ", tokens.get(1).lexeme());
        assertEquals("name", tokens.get(4).lexeme());
        assertEquals(" suffix", tokens.get(7).lexeme());
    }

    @Test
    void supportsNestedInterpolationAndJsonDelimiters() {
        assertDoesNotThrow(() -> Parser.parse("""
                define module demo as
                  pub fnc sample(String name): String {
                    return `{"schema":"v1","value":${name},"nested":${`hello ${name}`}}`;
                  }
                end
                """.replace("`", "\u0060")));
    }

    @Test
    void handlesQuotesBracesAndExpressionsInsideInterpolations() {
        assertDoesNotThrow(() -> Parser.parse("""
                define module demo as
                  pub fnc sample(): String {
                    return `field: ${infer struct{value: "}", ready: true}.value} / ${"a" + ("b" + "c")}`;
                  }
                end
                """.replace("`", "\u0060")));
    }

    @Test
    void allowsEscapedInterpolationMarkersBackticksAndNewlines() {
        String source = "\u0060" + "literal \\${ignored} escaped \\` and \\n" + "\u0060";
        List<Token> tokens = new Lexer(source).scan();
        assertEquals(List.of(Token.Type.LPAREN, Token.Type.STRING, Token.Type.RPAREN, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
        assertEquals("literal ${ignored} escaped ` and \n".replace("`", "\u0060"),
                tokens.get(1).lexeme());
    }

    @Test
    void rejectsIncompleteOrEmptyInterpolations() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new Lexer("\u0060" + "missing closing template").scan()).getMessage()
                .contains("unterminated template literal"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new Lexer("\u0060" + "x ${identifier").scan()).getMessage()
                .contains("unterminated template interpolation"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new Lexer("\u0060" + "x ${} y" + "\u0060").scan()).getMessage()
                .contains("empty template interpolation"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new Lexer("\u0060" + "x ${/* only comment */} y" + "\u0060").scan()).getMessage()
                .contains("empty template interpolation"));
    }
}
