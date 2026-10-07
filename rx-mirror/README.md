# rx-oreslang-channels

A userland reactive stream library written in **Oreslang**. It uses bounded/rendezvous channels, `readch`, `nb writech`, `try` probes, dynamic `select`/`nb select`, and `await`. It follows the pointerless ownership model throughout. `src/rx.ores` is a plain file/code unit, not a synthetic package or module wrapper; callers import the file with `import * as ...` and/or select declarations by relative path. It has no dependency on `std/rx` and adds no Java reactive implementation.

This library lives outside core/std-lib. The core rx-ores implementation can evolve independently while this version exercises Oreslang's channel and source-frame suspension APIs.

## Run

Install JDK 25, Maven, Python 3, and Git, and authenticate Git for the private ores-truffle-oreslang repositories, then:

```sh
python3 scripts/setup.py
source .work/env.sh
python3 scripts/test.py
```

`compiler.lock` pins the exact reference compiler revision. This branch requires the merged Future/select work plus the pointerless first-class `Fnc<T,...>` ownership convergence and the data-only Channel payload boundary: callback calls temporarily read-borrow ordinary arguments, while explicit ownership operations use the reserved `rt copy`, `rt share`, `rt borrow`, and `rt take` surface. Setup fetches the pinned commit; it does not modify your compiler checkout or `std/rx`.

You can also set `ORES_CLASSPATH` to an existing compatible compiler's `target/classes` plus its Maven dependency classpath. Set `JAVA` to the Java executable if necessary.

## Channel source with async/await

```ores
import * as rx_channels from './src/rx.ores';
import class Observable, Subscription, FramedSender from './src/rx.ores';

pub async fnc main(): void {
  val Channel<Option<int>> input = Channel.new<Option<int>>(0);
  // Registers a rendezvous write and returns immediately.
  val FramedSender<int> producer = rx_channels.framed_sender(input);
  val Future<void> producing = producer.send(42);
  val Observable<int> source = rx_channels.from_channel(input);
  val Subscription<int> sub = source.subscribe();

  // Suspends internally on await (nb select ...), releasing the carrier.
  val Option<int> value = sub.next();
  stdio.println(value.unwrap());
  await producing;

  val Future<void> finishing = producer.complete();
  val Option<int> terminal = sub.next();
  stdio.println(terminal.is_none());
  await finishing;
  return;
}
```

The reference compiler currently rejects generic `async fnc` declarations and async instance methods. The generic library therefore uses **ordinary methods whose source bodies suspend**. `Subscription.next()` returns `Option<T>` at the source level; call it directly inside a resumable source workflow. Internally, channel subscriptions use `nb select`, whose value form is `Future<Option<SelectResult>>`; awaiting it yields the current Ores continuation rather than parking an actor carrier. `send()` and `complete()` return real `Future<void>` registrations that callers explicitly `await`. `from_future()` adapts an existing Future. See [examples/channel_async.ores](examples/channel_async.ores) and [examples/pipeline.ores](examples/pipeline.ores).

Imports of generic functions currently use the module-qualified direct-call form. Explicit types on imported results retain useful checking across the current import boundary.

## API

| Operation | Behavior |
| --- | --- |
| `from_values(Array<T>)` | Takes ownership of its move-only array; separate cursor per subscription |
| `from_channel(Channel<Option<T>>)` | Hot input with one consumer; no background pump |
| `from_future(Future<T>)` | One shared Future result, then completion |
| `map(Observable<A>, Fnc<A, B>)` | One transform per demanded item |
| `filter(Observable<T>, Fnc<T, bool>)` | Iterative pulls until a predicate accepts; the callback receives temporary read access |
| `take(Observable<T>, int)` | At most N items, then cancels upstream |
| `merge_channels(left, right)` | Fair deterministic merging of two channel inputs |
| `first(Observable<T>)` | Compatibility helper returning `Option<T>`; cancellation in `finally` |
| `first_required(Observable<T>)` | Returns `T`, fails on empty rather than returning `None` (native Future-valued `first()` is a future integration target) |
| `framed_pipe<T>(capacity)` | **Preferred:** privately owns one writer/channel and admits one subscriber |
| `pipe.send(value)` / `pipe.complete()` | Bounded writes, rejects duplicate completion and writes after completion |
| `pipe.subscribe()` | Claims the single subscriber; duplicate calls fail |
| `framed_sender(Channel<Option<T>>)` | Creates checked `FramedSender<T>` for ordered terminal writes |
| `writer.send(value)` | Rejects sends after completion; returns bounded write `Future<void>` |
| `writer.complete()` | Claims termination exactly once and writes `None`; repeats fail |
| `subscription.next_event()` | Returns `StreamEvent<T>` with `next`, `complete`, or `cancelled` kind |
| `subscription.is_complete()/is_cancelled()` | Query terminal cause without conflating cancellation with normal completion |
| `send(channel, value)` | Legacy **unchecked** raw helper; caller must uphold terminal protocol |
| `complete(channel)` | Legacy **unchecked** raw helper; does not close the channel |
| `observable.subscribe()` | Creates an independent subscription state |
| `subscription.next()` | Suspends until data/completion/failure; one outstanding pull |
| `subscription.cancel()` | Idempotent cancellation; returns true only once |

Oreslang's source ownership model is pointerless. Callback types stay ordinary, for example `Fnc<int, bool>` or `Fnc<T, bool>`; a first-class callback call receives temporary read access without encoding a C/Rust-style pointer type. If code needs a named borrow beyond one call expression it uses `rt borrow value`, not `&value`.

```ores
val Fnc<int, bool> even = |value| -> {
  return value % 2 == 0;
};
```

The library and test runner reject pointer-style callback types, unary address-of borrows, and pointer declarations.

## Stream contract

- The preferred `FramedSender<T>` transports `Some(value)` then exactly one `None`, claiming termination before submitting the terminal write. Duplicate completion and sender writes after that claim fail. Completion remains in the data FIFO and cannot overtake previously registered writes.
- **Important ownership limit:** `FramedSender` is single-writer, task/actor-local state. It cannot enforce the protocol against code that retains and writes directly to the underlying raw Channel. The older raw `send(channel)` and `complete(channel)` functions remain for compatibility but are unchecked. A native channel-close adapter is the eventual stronger boundary.
- A submitted completion `Future<void>` must be awaited. When capacity is zero or the queue is full, it can remain pending until a consumer receives the terminal frame.
- Capacity zero is rendezvous; positive capacity bounds accepted writes. `send()` does not spin or drop when full. Retain and await every returned write Future to bound pending producer registrations as well as the channel buffer.
- Pull operators have no prefetch queue. `take(0)` consumes nothing. `take(N)` cancels upstream immediately after its Nth value. Filter uses a loop rather than recursive callbacks.
- Each channel input has one subscriber. Multiple subscriptions compete for frames; this is not multicast, and one completion frame cannot terminate multiple consumers. Use distinct channels for fan-out. The two merge inputs must be distinct channels.
- Merge keeps reusable FAIR select sets, removes completed inputs from selection, and finishes only when both complete. Cancellation uses a select control arm, allowing the runtime to remove losing read registrations.
- Dynamic `select from` returns `Option<SelectResult>`; `nb select from` returns native `Future<Option<SelectResult>>`; `try select from` remains an immediate `Option<SelectResult>` probe.
- Cancellation leaves the producer's channel open and preserves values not claimed by a winning selection. A read that already won a race may have consumed its value before cancellation.
- For backward compatibility, `next(): Option<T>` returns `None` on both cancellation and natural completion. Prefer `next_event()` for explicit `NEXT`/`COMPLETE`/`CANCELLED` states. Failures are propagated, never encoded as `COMPLETE`. This does **not** yet replicate the native `OresFuture` cancellation state in `next()`.
- Termination cleanup is claimed exactly once across completion, failure, and explicit cancellation. `cancel()` after natural completion returns false, and does not re-dispose a completed subscription.
- Future sources retain the producer's lifetime. Cancelling an adapter does not cancel its shared Future; an already pending Future pull waits for settlement and then discards its value if cancelled. Channel sources are the adapter for prompt cancellation of a pending read.
- Operator/Future failures propagate through source suspension; they are not emitted as data or mistaken for normal completion. Ordinary guest failures use `Result.expect` because this compiler has no guest `throw`/rethrow statement. `finally` clears demand and disposes upstream even when a language panic bypasses `catch`.
- `Array<T>` is move-only on the pinned compiler. `from_values` accepts ownership of that array, so the caller must not access or mutate it after transfer. `rt copy` on move-only arrays is **not yet supported** without `Symbol.rtCopy` lowering, and must not be silently treated as a shallow alias. Replay shares the captured collection across subscriptions; nested mutable objects still require explicit safe ownership or immutability. Use channels for owned mutable payloads.
- Channels and subscriptions stay in their execution domain. They cannot cross actor mailboxes; use ActorRef/mailbox protocols for actor-to-actor transport. Function/closure values are deliberately not channel payloads: callbacks remain local and channels carry data. Structured actor cancellation remains runtime-owned.

This is an initial pull-oriented implementation, not complete Rx operator parity. Timed operators, multicast subjects, variadic merge, and shared-Future cancellation need additional work.

## Validation

The test runner type/ownership-checks the library, examples, and every test program, then executes each with a timeout and checks exact output or an expected failure. Coverage includes replay and array ownership rejection, completion ordering, checked sender double-close/send-after-close rejection, natural completion vs cancellation events, cleanup-at-most-once, `first_required` on present/empty sources, operator composition, no prefetch, fair merge, rendezvous backpressure, Future adaptation, upstream failure, and invalid take counts. The existing `.github/workflows/test.yml` builds the pinned compiler and runs the suite when its `ORES_COMPILER_READ_TOKEN` read-only secret is present. Since a default `GITHUB_TOKEN` cannot check out private cross-org sources, funded-org regression runs instead mirror the *exact compiler Git tree* and this library's source/tests into the same test repository, verify compiler blob identity, and run there with no cross-org checkout secret. Local validation uses existing Git authentication.

## Ownership and lifecycle audit (October 2026)

`framed_pipe<T>(capacity)` is the recommended entry point when the stream owns its channel. Its transport and sender are private and it accepts only one subscriber; this eliminates external raw-write bypass of the terminal sentinel for this API. Existing `framed_sender(rawChannel)` and `from_channel(rawChannel)` remain compatibility adapters without this enforcement. All methods assume task/actor-local sequential access; the source `marked()` read/restore probe is not an atomic cross-thread peek. `Subscription.dispose_once()` is private to prevent external callers from triggering cleanup independently of terminal state. `pull()` and `dispose()` are still public override hooks pending support for protected class methods, and must not be called directly. Native `std/rx` remains the eventual authority on structured cancellation.
