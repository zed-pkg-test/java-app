package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * All ownership boundaries must be checked at the source operation, not only
 * at the OS scheduler/carrier boundary. A nonblocking registration may return
 * immediately, while its callback/continuation can outlive the caller.
 */
final class AsyncSuspensionOwnershipMatrixTest {
    private static final String BAR = """
            define class Bar as
              pub let String value = "hello";
            end
            """;

    private static void reject(String snippet, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BAR + snippet)));
        assertTrue(error.getMessage().contains(diagnostic), error::getMessage);
    }

    private static void accept(String snippet) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BAR + snippet)));
    }

    @Test
    void awaitingPendingChannelReadCannotCrossSharedLoan() {
        reject("""
                async fnc bad(): int {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Future<int> pending = nb readch ch;
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  val int received = await pending;
                  stdio.println(view.value);
                  return received;
                }
                """, "cannot await while an ordinary borrow is live");
    }

    @Test
    void awaitingInlineNonblockingReadCannotCrossMutableLoan() {
        reject("""
                async fnc bad(): int {
                  val Channel<int> ch = Channel.new<int>(1);
                  let mut Bar owner = new Bar();
                  val view = rt borrow mut owner;
                  val int received = await (nb readch ch);
                  stdio.println(view.value);
                  return received;
                }
                """, "cannot await while an ordinary borrow is live");
    }

    @Test
    void awaitAfterBorrowScopeEndsIsAllowed() {
        accept("""
                async fnc good(): int {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Bar owner = new Bar();
                  if true then
                    val view = rt borrow owner;
                    stdio.println(view.value);
                  fi
                  return await (nb readch ch);
                }
                """);
    }

    @Test
    void implicitCooperationRequiresNoActiveBorrow() {
        reject("""
                fnc bad(): void {
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  rt cooperate;
                  stdio.println(view.value);
                  return;
                }
                """, "cannot cooperate while an ordinary borrow is live");
    }

    @Test
    void ordinaryReadAndWriteChannelsAreSuspensionPoints() {
        for (String operation : new String[]{"val int got = readch ch;", "writech ch, 1;"}) {
            reject("""
                    fnc bad(): void {
                      val Channel<int> ch = Channel.new<int>(1);
                      val Bar owner = new Bar();
                      val view = rt borrow owner;
                      %s
                      stdio.println(view.value);
                      return;
                    }
                    """.formatted(operation), "cannot readch/writech while an ordinary borrow is live");
        }
    }

    @Test
    void blockingDoSelectAlsoRejectsLiveBorrow() {
        reject("""
                fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  do select first {
                    when readch ch: val value { stdio.println(value); }
                  }
                  stdio.println(view.value);
                  return;
                }
                """, "cannot select while an ordinary borrow is live");
    }

    @Test
    void blockingSelectWithDefaultCannotParkAndMayReadBorrow() {
        accept("""
                fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  do select first {
                    when readch ch: val value { stdio.println(view.value); }
                    default: { stdio.println(view.value); }
                  }
                  return;
                }
                """);
    }

    @Test
    void immediateProbesAndNonblockingReadsCanRegisterWithUncapturedBorrow() {
        accept("""
                fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  val Option<int> immediate = try readch ch;
                  val Future<int> pending = nb readch ch;
                  val bool sent = try writech ch, 1;
                  val Future<void> futureSend = nb writech ch, 2;
                  stdio.println(view.value);
                  return;
                }
                """);
    }

    @Test
    void NonblockingReadFutureMustNotBeAwaitedUnderBorrow() {
        reject("""
                async fnc bad(): int {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  val Future<int> future = nb readch ch;
                  stdio.println(view.value);
                  return await future;
                }
                """, "cannot await while an ordinary borrow is live");
    }

    @Test
    void DoNbSelectCannotCaptureLexicalBorrow() {
        reject("""
                actor fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  do nb select {
                    when readch ch: val received {
                      stdio.println(view.value);
                    }
                  }
                  return;
                }
                """, "nb select continuation cannot capture borrowed value 'view'");
    }

    @Test
    void DoNbSelectTransfersOwnedCaptureAtRegistration() {
        reject("""
                actor fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Array<int> data = [1, 2];
                  do nb select {
                    when readch ch: val received {
                      stdio.println(data[0]);
                    }
                  }
                  stdio.println(data[0]);
                  return;
                }
                """, "moved value");
    }

    @Test
    void DoNbSelectMayRunWhileAnUncapturedBorrowLives() {
        accept("""
                actor fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  do nb select {
                    when readch ch: val received { stdio.println(received); }
                  }
                  stdio.println(view.value);
                  return;
                }
                """);
    }

    @Test
    void NbSelectAndDoNbSelectCanCopyScalarIntoLaterTurn() {
        accept("""
                actor fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val int copied = 2;
                  nb select {
                    when readch ch: val received { stdio.println(copied + received); }
                  }
                  do nb select {
                    when readch ch: val other { stdio.println(copied + other); }
                  }
                  stdio.println(copied);
                  return;
                }
                """);
    }

    @Test
    void NonblockingWriteCallbackCannotCaptureBorrow() {
        reject("""
                actor fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  nb cb writech ch, 7 || -> {
                    stdio.println(view.value);
                  };
                  return;
                }
                """, "cannot capture borrowed value 'view'");
    }

    @Test
    void NonblockingWriteCallbackCanLeaveUncapturedBorrowUntouched() {
        accept("""
                actor fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  nb cb writech ch, 7 || -> {
                    stdio.println("sent");
                  };
                  stdio.println(view.value);
                  return;
                }
                """);
    }

    @Test
    void DynamicSelectMustRejectSuspendWithBorrow() {
        reject("""
                fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Array<SelectCase> cases = [SelectCase.read(ch)];
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  val Option<SelectResult> got = select from cases;
                  stdio.println(view.value);
                  return;
                }
                """, "cannot select from cases while an ordinary borrow is live");
    }

    @Test
    void ChannelCannotTransportBorrowedPayload() {
        reject("""
                fnc bad(): void {
                  val Channel<Bar> ch = Channel.new<Bar>(1);
                  val Bar owner = new Bar();
                  writech ch, rt borrow owner;
                  return;
                }
                """, "borrow");
    }

    @Test
    void DiscardOnlyDoReadchFormsStayRejectedUntilSpecified() {
        // The frontend currently supports do select / do nb select only.
        // A discarded nb readch Future needs an explicit cancel/drop contract
        // before any new 'do' spelling can safely register such a read.
        for (String statement : new String[]{
                "do readch ch;", "do nb readch ch;",
                "do writech ch, 1;", "do nb writech ch, 2;"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("""
                            fnc unsupported(): void {
                              val Channel<int> ch = Channel.new<int>(1);
                              %s
                              return;
                            }
                            """.formatted(statement)), statement);
        }
    }
    @Test
    void GeneratorYieldCannotSuspendWithOrdinaryBorrow() {
        reject("""
                generator fnc bad(): int {
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  yield 7;
                  stdio.println(view.value);
                  return;
                }
                """, "cannot yield while a borrow is live");
    }

    @Test
    void AsyncForAwaitCannotKeepBorrowAlive() {
        reject("""
                async generator fnc stream(): int { yield 7; return; }
                async fnc bad(): void {
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  for await const entry of stream() {
                    stdio.println(entry);
                  }
                  stdio.println(view.value);
                  return;
                }
                """, "cannot suspend for async iteration while a borrow or MutexGuard is live");
    }

    @Test
    void DynamicImmediateAndNonblockingSelectDoNotSuspendCaller() {
        accept("""
                fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Array<SelectCase> cases = [SelectCase.read(ch)];
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  val Option<SelectResult> immediate = try select from cases;
                  val Future<SelectResult> pending = nb select from cases;
                  stdio.println(view.value);
                  return;
                }
                """);
    }

    @Test
    void StaticDefaultDoesNotExemptNestedAwaitInsideSelectedArm() {
        reject("""
                async fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Bar owner = new Bar();
                  val view = rt borrow owner;
                  select {
                    when readch ch: val received {
                      val int next = await (nb readch ch);
                      stdio.println(next);
                    }
                    default: { stdio.println(view.value); }
                  }
                  return;
                }
                """, "cannot await while an ordinary borrow is live");
    }

    @Test
    void NbSelectWriteArmCannotTransportBorrowedPayload() {
        reject("""
                actor fnc bad(): void {
                  val Channel<Bar> output = Channel.new<Bar>(1);
                  val Bar owner = new Bar();
                  nb select {
                    when writech output, rt borrow owner: { }
                  }
                  return;
                }
                """, "writech cannot transport a borrowed reference");
    }

    @Test
    void BlockingChannelWriteCannotTransportBorrowedPayload() {
        reject("""
                fnc bad(): void {
                  val Channel<Bar> output = Channel.new<Bar>(1);
                  val Bar owner = new Bar();
                  writech output, rt borrow owner;
                  return;
                }
                """, "writech cannot transport a borrowed reference");
    }

}
