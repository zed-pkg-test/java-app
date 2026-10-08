# Oreslang actor contracts: sealed runtime entrypoint, private source handlers

**Decision, October 7, 2026.** This document is the target language/runtime
contract. **Status:** this draft branch implements an end-to-end private
`run(T): R` request/reply slice, including typed `ActorRef.request(T)`,
runtime-private reply Future envelopes, cancellation-before-dispatch,
actor-turn suspension, and independent actor-fatal panic propagation.
The existing private `receive(ActorMail<T>): void` and `send(T)`
event protocol remains supported. Request dispatch passes exact-source-tree
JVM CI, but the branch is **not merged or fully hardened**: imported ABI
compatibility, full return-graph transport lifetime accounting, cancellation
race model-checks, and explicit source `raise` semantics remain separate gates. See [ACTOR_CLASS_LIFECYCLE_DESIGN.md](ACTOR_CLASS_LIFECYCLE_DESIGN.md)
for the currently executable event/stream surface.

## Canonical user model

```ores
import class Job from "./job";
import class Result from "./result";

define actor Worker as
  let corrupted: bool = false;

  fnc on_start(): void {
    // Actor-local initialization; NOT a constructor.
    return;
  }

  fnc run(Job job): Result {
    if self.corrupted; then
      panic CorruptedStateError("actor state invalid");
    fi

    return process(job);
  }
end

pub async routine main(): void {
  val worker = spawn Worker(); // zero constructor arguments
  await worker.ready;

  // A request goes through the runtime mailbox, not Worker.run().
  val result = await worker.request(job);
  worker.stop();
  await worker.done; // settles after actor finalization
  return;
}
```

The referenced `Job`, `Result`, `CorruptedStateError`, `process`, and
`job` are illustrative dependencies, not a standalone runnable fixture.
`request` is the **proposed** reply-bearing ActorRef API, not a current API.
A request does not automatically stop the actor; the example's `done` wait
therefore requires a separate stop/end path in actual runnable code.

## Hard invariants

1. Every actor has a **sealed, implicit, VM-owned actor base**. It is an
   internal dispatch/lifecycle mechanism, **not** a resolvable Oreslang class,
   parent type, imported symbol, reflection value, or callable base method.
   No guest-visible `ActorBase`, `super`, `__dispatch`, `__run`, or
   constructor authority is introduced.
2. A source actor cannot declare a `constructor`, even one that is private,
   empty, or never used. `new Worker()`, direct `Worker()`, and
   `spawn Worker(args...)` are rejected. `spawn Worker()` is the only
   construction path. Field initializers then `on_start(): void` run in the
   actor's own execution domain; `ready` settles only after success.
3. `super`, `super()`, `super.method()`, and `super.field` are forbidden
   in actor code, including nested lambdas and lifecycle methods. This stays
   true when the source actor explicitly extends another **source actor**
   of the same isolation kind. Ordinary non-actor class inheritance is
   unaffected.
4. `fnc run(In message): Out` is the **private, instance-only**
   request/reply handler. `pub fnc run`, `static fnc run`, abstract,
   generic-method, overloaded, and constructor-like `run` declarations
   are rejected. The single required argument and its return type form the
   public *protocol shape*, **not** a public callable method.
5. The dispatcher is the **only** external entrypoint. Guest source may not
   invoke `worker.run(...)`, `self.run(...)`, `Worker.run(...)` or
   `super.run(...)`; a direct call would evade admission controls, quota,
   cancellation, supervision and actor-turn serialization. Helpers declared
   on an actor may call each other with ordinary private access.
6. External code holds an opaque `ActorRef<Worker>`, never a `Worker`
   object or reference to its heap. `ActorRef.request(In)` is inferred
   from the unique effective handler signature and yields `Future<Out>`.
   The input and returned output must be owned/sendable; mutable references,
   pointers, borrowed aliases and closures cannot cross the actor boundary.
   Reject unknown/dynamic handler signatures until link/AOT protocol metadata
   prove them safe.
7. A request owns a reply slot/future **in runtime-private envelope metadata**.
   The caller cannot forge reply identifiers or inspect a mailbox. Reject
   missing/duplicate/late replies. Queued requests cancelled before dispatch
   do not invoke guest code; cancellation during a turn never rewinds a side
   effect; a stopped actor cannot be resurrected by a late completion.
8. The actor mailbox has one logical execution lease at a time. Async suspension
   resumes the current logical turn before admitting another user request.
   Bounded message/reply accounting and backpressure work identically for
   shared, private and untrusted actors. No guest method can override
   `send`, `end`, `endWithCleanup`, `ready`, `done` or the dispatcher.
9. `throw` fails only the current request by default: the request's future
   fails, and the actor remains usable. `raise` bypasses ordinary catch and
   requires its explicit recovery semantics; unrecovered escalation terminates
   the actor. `panic` is actor-fatal and reports failure to its supervisor,
   **never automatically the whole OresVM**. Always settle affected futures
   and release mailbox/output/heap accounting on termination.
10. Explicit source-actor inheritance may remain, but only across compatible
    isolation kinds and with one statically unambiguous effective handler.
    A child may replace its inherited handler with a compatible signature
    and cannot use `super` to invoke the old handler. The runtime sealed base
    is not present in source-level class linearization or diamond resolution.

## Event-only compatibility and migration

Current `receive(ActorMail<T> mail): void` is a **private event handler**
used by `ActorRef.send(T)`. Keep it functional through migration. The target
contract uses one explicit message-handling mode per actor:
- **Request/reply:** private `run(T): R` with `ActorRef.request(T)`.
- **One-way/event:** private `receive(ActorMail<T>): void` with
  `ActorRef.send(T)` and optional parent-observable `outputs`.

Do not allow a single actor to define both reserved handlers until the
compiler has a precise tagged protocol and deterministic dispatch for mixed
modes. Preserve one mailbox and one execution lease regardless of mode.
The `run` handler is *not* ordinary `pub` actor protocol exposure; the
public API belongs to the generated typed `ActorRef`.

`on_start` remains zero-argument, private, synchronous and actor-local.
Startup parameters are delivered by a normal typed first request after
`ready`, never secretly treated as constructor arguments.

## Compile-time negative test matrix

Every item below must be rejected by parser, type analysis or ownership check,
not merely fail on first runtime invocation:

```ores
// Negative: constructor, even without pub.
define actor Bad as
  constructor() {}
end

// Negative: public handler cannot be called through the raw object model.
define actor Bad as
  pub fnc run(int message): int { return message; }
end

// Negative: superclass authority cannot be called or inspected.
define actor Bad as
  fnc run(int message): int {
    super.run(message);
    return message;
  }
end

// Negative: 'self.run' bypasses mailbox dispatch.
define actor Bad as
  fnc run(int message): int {
    return self.run(message);
  }
end

// Negative: no actor constructor argument, even if a handler expects int.
val bad = spawn Bad(42);

// Negative: direct invocation on ActorRef.
val badResult = bad.run(42);

// Negative: multiple implicit handler candidates.
define actor Bad as
  fnc run(int message): int { return message; }
  fnc run(String message): int { return 0; }
end
```

Also test `static run`, `run` with zero/two parameters, generic `run<T>`,
mixed `run`/`receive`, mismatched inherited handler signatures, sendability,
return-type mismatch, actor-to-ordinary-class inheritance, nested super
expressions, actor-scoped closures, and reflection/dynamic lookup bypasses.

## Positive executable acceptance gates

- `spawn Worker()` creates an actor, invokes `on_start` once, then settles
  `ready`; no constructor or caller-heap alias exists.
- `await ref.request(job)` returns a typed result from private
  `run(Job): Result`, including imported actors and AOT mode.
- Two queued requests serialize; explicit suspension keeps a logical turn
  intact; a peer actor can progress on a single carrier.
- A `throw` only rejects its request; the next request succeeds. `panic`
  rejects affected requests, terminates the actor, and leaves other actors
  and the VM alive.
- Stopping/cancelling during queued and suspended work does not leak envelope,
  reply-slot, shared-memory, output-stream or continuation reservations.
- Malicious reflective/interop code cannot obtain the hidden base or dispatch
  directly, and private/untrusted actors cannot gain capabilities via it.
- Migration fixtures using the existing `receive` protocol still pass.

## Implementation checklist (currently outstanding)

1. **Parser/semantic contract (partly implemented on this draft):** the
   pre-type-check AST pass rejects public/static/overloaded/generic local
   `run`, mixed local and locally resolvable inherited `run`/`receive`, and
   direct `self.run`/`self.receive`/`self.on_start` captures, including lambdas.
   The existing parser already rejects actor constructors and `super` syntax.
   Runtime method and bound-method lookup now deny direct actor handler
   invocation even through aliases. Still required: imported actor
   inheritance/link-time ABI validation and exhaustive foreign-interop probes.
2. **Typed `ActorRef` (implemented locally):** `request` checks an input
   against the private handler signature and types the returned `Future<R>`.
   Imported actor ABI digest/revision checks and generics/AOT parity need
   separate acceptance proofs.
3. **Runtime lowering (implemented first slice):** runtime-owned envelopes
   dispatch private handlers; synchronous and suspended replies complete once,
   and caller cancellation suppresses not-yet-dispatched work. Admission shares
   bounded send transport; replies validate/freeze/copy sendable graphs.
   Outstanding: lifetime-based result-memory accounting and adversarial races.
4. **Error semantics (partially implemented):** ordinary handler exceptions
   fail only their request. `OresPanic` and explicit runtime panic exceptions
   terminate their actor and reject its replies while peers continue. Full
   source-language `panic`/`raise` surface, handler recovery, and precise
   supervisor observability are still separate gates.
5. **Tests + CI:** add the negative/positive proofs above and run on the exact
   SHA using both Java and native carriers. If source Actions cannot allocate
   runners, mirror the **exact Git tree** to a funded test organization,
   compare tree and blob SHAs and execute local Actions there without secrets
   or private cross-organization checkout.
6. **Downstream migration:** update actor demos, lifecycle docs, marketing
   examples and compiler ABI pins **only after** the above runtime is green.

A passing local request/reply execution test is evidence for that specific
runtime slice, **not** proof that all imported/AOT/interop/failure and
isolation acceptance gates are complete. Do not merge until those gates pass.
