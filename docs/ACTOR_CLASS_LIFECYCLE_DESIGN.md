# Actor class lifecycle and parent-facing output streams (design + implementation status)

**Status (2026-10-06): parser/lexical-boundary groundwork plus host-side actor ready/done Futures; NOT an executable source-level actor-class API.**

The existing `ActorRuntime` already has `ActorRef.send/stop/cancel/forceCancel`,
bounded channel-backed mailboxes, actor identity, child supervision, and
`rt cooperate` continuation support. The Java `ActorMail<Out>` record is
**already used for ActorGroup Mailman outbox messages**. Do not silently
repurpose it as a class actor's inbound message.

## Target `main.ores` (proposed source API, not runnable yet)

```ores
// The compiler must eventually lower this class and its lifecycle hooks.
// This is a contract example and must NOT be treated as an executable fixture.
define actor Worker as
  pub constructor() {
    // Runs in the worker's own execution/isolation domain.
  }

  pub receive(ActorMail<String> mail): void {
    if mail.value != "start"; do
      return;
    fi

    self.send("hello");
    rt cooperate;
    self.send("world");
    rt cooperate;
    self.send("!");
    self.end();
    return;
  }
end

pub async routine main(): void {
  val workers = [spawn Worker(), spawn Worker(), spawn Worker()];
  await Future.all([workers[0].ready, workers[1].ready, workers[2].ready]);

  // Creating an actor does not automatically send a receive() message.
  for const worker of workers do
    worker.send("start");
  done

  // A parent may subscribe/merge all three bounded output streams. Each
  // worker's per-stream order is hello, world, !; cross-worker order is free.
  val streams = [workers[0].outputs, workers[1].outputs, workers[2].outputs];
  for await const output of merge(streams) do
    stdio.println(output.actor_id, output.sequence, output.value);
  done

  await Future.all([workers[0].done, workers[1].done, workers[2].done]);
  return;
}
```

This illustration uses proposed `spawn`, `ActorMail`, `outputs`, `ready`,
`done`, `merge`, and `for await` integrations. Some unrelated
syntax/runtime pieces may also need work before it can compile. In particular,
`return await w.ready` returns a readiness result, **not** `w` or a list
of output strings.

## Canonical API split

| Surface | Signature (conceptual) | Contract |
| --- | --- | --- |
| `spawn Worker(args)` | `ActorRef<Worker>` | Allocates actor identity; constructor runs in actor execution context, not the caller |
| `w.id`, `w.kind` | `ActorId`, `ActorKind` | Immutable identity/diagnostics |
| `w.ready` | `Future<void>` | Completes once constructor and initialization succeed, fails if initialization fails/stops |
| `w.send(mail)` | `void` or explicit nonblocking variant | **Inbound**, serialized, bounded actor mailbox; reject capability/pointer/function payloads |
| `w.outputs` | `AsyncIterator<ActorOutput<T>>` or read-only `Channel<ActorOutput<T>>` | **Outbound**, bounded, async/backpressure-capable, parent-observable stream |
| `w.done` | `Future<void>` | Settles after actor finalization and isolate/carrier cleanup |
| `w.stop()`, `w.cancel()` | `void`, `bool` | Graceful stop vs structured cancellation (supervisor authority checks apply) |
| `w.kill()` | `bool` | Force-isolated cancellation only if revoker is configured; never thread interruption masquerading as isolation |
| `self.send(value)` | `Future<void>` or nonblocking admission acknowledgement | **Outbound** from actor to its output stream; NOT recursive delivery to own inbox |
| `self.end()` | `void` | Actor-side graceful completion: reject new outputs; close stream after admitted outputs drain |
| `receive(ActorMail<T>)` | `void` | Lifecycle handler invoked once per incoming envelope, serialized and actor-local |
| `rt cooperate` | `void` scheduling effect | Suspension/safepoint; no implicit publication or message delivery |

Prefer a **non-overridable base transport implementation** for `self.send`.
If source-level overriding is allowed, `super.send(value)` must resolve to
that base implementation without letting a subclass bypass quotas, transport
validation, or termination fences. An actor `send()` with **zero args** cannot
forward the string payload emitted by the example; an explicit payload
parameter is required.

## Message identities and transport

- Introduce a new source-level `ActorMail<T>` inbox envelope with
  `actor_id`/optional sender identity, monotonic mailbox sequence,
  immutable `value`, and optional reply capability. It is not the Java
  `dev.oreslang.runtime.ActorMail` Group Mailman record.
- Introduce `ActorOutput<T>(actor_id, sequence, value)` for output. The
  output sender is the *emitting* actor, not its parent.
- Send values through the same strict graph walker/serializer/copy rules
  used by mailbox admission. Function closures, raw object pointers,
  borrowed/transient aliases, mutable cross-actor references, and live
  domain-local channels are not permitted on actor mailboxes/output.
- Backpressure must never block an actor carrier or retain unbounded
  `Future`/waiter queues. Define finite pending writer/read quotas,
  cancellation behavior, buffer accounting, and terminal EOF/error results.
- Output readers must not gain permission to read actor-owned heap objects.
  Freezing or copy-across-isolation is mandatory.
- Per actor/mailbox and per output stream ordering is FIFO. Ordering *between*
  workers is nondeterministic. A merged stream must preserve each producer's
  sequence, not promise a global order.
- Parent cancellation/failure should terminate structured children and close
  output streams. `done` observes the existing carrier-exit finalization
  barrier rather than just a return from `receive`.

## Lexical scope / no ambient file access

Actor and ordinary class methods must not implicitly resolve sibling file-level
`main`, `helper`, or other top-level callables. Their own methods/fields are
accessed via `self`; dependencies must be explicitly imported. The initial
branch enforces the **unqualified callable fallback** in both `TypeChecker`
and the source evaluator. A future pass must also constrain same-file module
and class namespace lookups, static/qualified references, module-to-module
references, imported wildcard facades, and dynamic/linked-code-unit paths,
with positive tests for genuine explicit imports.

Built-ins are separately capability-checked; an explicit import never
weakens isolate policy.

## Implementation sequencing / acceptance gates

1. **This branch:** parse `define actor ... as ... end`, enforce first-pass
   implicit class-to-file callable boundary, and add regression tests.
2. Extend AST/parser/type checker with actor constructors, `spawn` expressions,
   reserved lifecycle hooks, `ActorRef<Worker>` and envelope/stream types.
   Keep constructor execution actor-local; reject `new Worker()`.
3. **Host substrate implemented in this branch:** `ActorRef.ready()` and
   `ActorRef.done()` expose runtime-only completion Futures. `ready()` schedules
   actor initialization even with no first message; stop-before-start and
   initializer failure settle the Future exceptionally. Before source exposure,
   still verify force-cancel and parent/child termination races with CI.
4. Implement inbox dispatch and bounded parent outbox with serialization,
   per-worker sequence counters, EOF and failure forwarding; lift `self.send`
   into asynchronous nonblocking flow control.
5. Expose parent-side `outputs` and async-generator/channel adapters;
   interoperate with `select`/`nb select`, `Future.all`, and stream merge.
6. Test actual source-level `main.ores` end-to-end with 3+ workers,
   `rt cooperate` interleavings, output order, overflow/backpressure,
   cancellation, async constructor failures, object/pointer/closure rejection,
   private/shared/untrusted policies, and module/import isolation.
7. Only after green tests for the *exact* head SHA consider merging. If the
   source org cannot allocate a GitHub Actions runner, mirror the **exact Git
   tree** into a funded test org's temporary branch, verify blob/tree identity,
   and run Actions locally to that repo without cross-org secrets.

Do not mark this milestone as implementing `spawn Worker()`, `w.ready`,
`self.send`, `self.end`, or streamed worker output: they remain future gates.
