package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

final class ChannelCallableTransportTest {
    @Test
    void channelPayloadsRejectFirstClassFunctionsTransitively() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Fnc<int, int>> handlers): void {
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Option<Fnc<int, int>>> handlers): void {
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class HandlerBox as
                  val Fnc<int, int> callback;
                end
                fnc bad(Channel<HandlerBox> handlers): void {
                  return;
                }
                """)));
    }

    @Test
    void channelNewRejectsCallablePayloadsBeforeAnyWriteOccurs() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Channel<Fnc<int, int>> handlers = Channel.new<Fnc<int, int>>(1);
                  return;
                }
                """)));
    }

    @Test
    void localCallbacksAndOrdinaryDataChannelsRemainLegal() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc apply(Fnc<int, int> callback, int value): int {
                  return callback(value);
                }

                fnc ok(): void {
                  val Fnc<int, int> twice = |value| -> { return value * 2; };
                  val int result = apply(twice, 21);
                  val Channel<int> values = Channel.new<int>(1);
                  writech values, result;
                  return;
                }
                """)));
    }
}
