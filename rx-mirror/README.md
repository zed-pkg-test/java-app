# rx-oreslang-channels

A userland reactive stream library written in **Oreslang**. It uses bounded/rendezvous channels, `readch`, `nb writech`, `try` probes, dynamic `select`/`nb select`, and `await`. `src/rx.ores` is a plain file/code unit, not a synthetic package or module wrapper; callers import the file with `import * as ...` and/or select declarations by relative path. It has no dependency on `std/rx` and adds no Java reactive implementation.

This library lives outside core/std-lib. The core rx-ores implementation can evolve independently while this version exercises Oreslang's channel and source-frame suspension APIs.

## Run

Install JDK 25, Maven, Python 3, and Git, and authenticate Git for the private ores-truffle-oreslang repositories, then:

```sh
python3 scripts/setup.py
source .work/env.sh
python3 scripts/test.py
```

`compiler.lock` pins the exact reference compiler revision. The initial release requires the source-method suspension fix in [oreslang-source.java PR #308](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/308). Setup fetches the pinned commit; it does not modify your compiler checkout or `std/rx`.

You can also set `ORES_CLASSPATH` to an existing compatible compiler's `target/classes` plus its Maven dependency classpath. Set `JAVA` to the Java executable if necessary.

## Channel source with async/await

```ores
import * as rx_channels from './src/rx.ores';
import class Observable, Subscription from './src/rx.ores';

pub async fnc main(): void {
  val Channel<Option<int>> input = Channel.new<Option<int>>(0);
  // Registers a rendezvous write and returns immediately.
  val Future<void> producing = rx_channels.send(input, 42);
  val Observable<int> source = rx_channels.from_channel(input);
  val Subscription<int> sub = source.subscribe();

  // Suspends internally on await (nb select ...), releasing the carrier.
  val Option<int> value = sub.next();
  stdio.println(value.unwrap());
  await producing;

  val Future<void> finishing = rx_channels.complete(input);
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
| `from_values(Array<T>)` | Cold finite replay, a separate cursor per subscription |
| `from_channel(Channel<Option<T>>)` | Hot input with one consumer; no background pump |
| `from_future(Future<T>)` | One shared Future result, then completion |
| `map(Observable<A>, Fnc<A, B>)` | One transform per demanded item |
| `filter(Observable<T>, Fnc<&T, bool>)` | Iterative pulls until a predicate accepts |
| `take(Observable<T>, int)` | At most N items, then cancels upstream |
| `merge_channels(left, right)` | Fair deterministic merging of two channel inputs |
| `first(Observable<T>)` | One pull, then cancels the subscription |
| `send(channel, value)` | Nonblocking registration with bounded backpressure |
| `complete(channel)` | Registers an ordered end-of-stream write |
| `observable.subscribe()` | Creates an independent subscription state |
| `subscription.next()` | Suspends until data/completion/failure; one outstanding pull |
| `subscription.cancel()` | Idempotent cancellation; returns true only once |

A filter borrows its argument so rejection never moves away a retained payload. For scalar predicates the current compiler needs an explicit conversion from the borrowed value, for example:

```ores
val Fnc<&int, bool> even = |x| -> {
  val int value = x as int;
  return value % 2 == 0;
};
```

## Stream contract

- A channel transports `Some(value)` followed by exactly one `None`. Completion shares the data FIFO, so it cannot overtake queued items. A producer must stop after its terminal frame.
- Capacity zero is rendezvous; positive capacity bounds accepted writes. `send()` does not spin or drop when full. Retain and await every returned write Future to bound pending producer registrations as well as the channel buffer.
- Pull operators have no prefetch queue. `take(0)` consumes nothing. `take(N)` cancels upstream immediately after its Nth value. Filter uses a loop rather than recursive callbacks.
- Each channel input has one subscriber. Multiple subscriptions compete for frames; this is not multicast, and one completion frame cannot terminate multiple consumers. Use distinct channels for fan-out. The two merge inputs must be distinct channels.
- Merge keeps reusable FAIR select sets, removes completed inputs from selection, and finishes only when both complete. Cancellation uses a select control arm, allowing the runtime to remove losing read registrations.
- Dynamic `select from` returns `Option<SelectResult>`; `nb select from` returns native `Future<Option<SelectResult>>`; `try select from` remains an immediate `Option<SelectResult>` probe.
- Cancellation leaves the producer's channel open and preserves values not claimed by a winning selection. Later pulls return `None`. A read that already won a race may have consumed its value before cancellation.
- Future sources retain the producer's lifetime. Cancelling an adapter does not cancel its shared Future; an already pending Future pull waits for settlement and then discards its value if cancelled. Channel sources are the adapter for prompt cancellation of a pending read.
- Operator/Future failures propagate through source suspension; they are not emitted as data or mistaken for normal completion. Ordinary guest failures use `Result.expect` because this compiler has no guest `throw`/rethrow statement. `finally` clears demand and disposes upstream even when a language panic bypasses `catch`.
- Replay with `from_values` is intended for immutable/copyable payloads. Use a single-consumer channel for owned mutable payloads; replay does not deep-copy objects.
- Channels and subscriptions stay in their execution domain. They cannot cross actor mailboxes; use ActorRef/mailbox protocols for actor-to-actor transport. Function/closure values are deliberately not channel payloads: callbacks remain local and channels carry data. Structured actor cancellation remains runtime-owned.

This is an initial pull-oriented implementation, not complete Rx operator parity. Timed operators, multicast subjects, variadic merge, and shared-Future cancellation need additional work.

## Validation

The test runner type/ownership-checks the library, examples, and every test program, then executes each with a timeout and checks exact output or an expected failure. Coverage includes replay, completion ordering, operator composition, cancellation/idempotence, no prefetch, fair merge with unequal input lengths, rendezvous backpressure, Future adaptation, upstream failure, and invalid take counts. The [CI template](ci/test.workflow.yml) builds the pinned compiler and runs the same suite. Copy it to `.github/workflows/test.yml` using credentials with workflow write permission to activate it. The connected GitHub OAuth token lacks that scope, so the template is preserved outside the workflow directory. Because the compiler repository is private, CI requires an `ORES_COMPILER_READ_TOKEN` repository secret with read-only access to `oreslang-source.java`; the default workflow token cannot read a different private repository. The workflow fails explicitly when this secret is absent. Local validation uses your existing Git authentication.
