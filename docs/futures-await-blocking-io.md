# Futures, await, blocking I/O, and scheduler suspension

Status: runtime Future/suspension ABI, stackless async source lowering, proper
async tail transfer, and logical async traces are implemented on top of the
OresVM four-domain scheduler. `await` projects through the built-in
`Awaitable<T>` contract and always re-enters through a fresh scheduler
dispatch. The recursive evaluator remains a host/root compatibility fallback
and fails closed inside actor turns rather than blocking or inline-resuming a
carrier.

## Rule: ordinary execution does not implicitly yield

Yielding is a property of specific suspension operations and scheduler policy,
not of every Oreslang call.

| Operation | Scheduler yield? |
| --- | --- |
| ordinary `fnc` / `routine` call | no |
| CPU work | no |
| start nonblocking I/O and keep its Future | no |
| `await future` | yes, always |
| Ores blocking/suspending I/O | yes while waiting |
| empty inbox receive | yes |
| timer/sleep wait | yes |
| scheduler safepoint | normally no |
| untrusted fuel/deadline exhaustion at a safepoint | forced handoff/abort |

A nonblocking call therefore behaves like:

```ores
val pending = socket.read_async(buffer);

// Still the same execution turn.
calculate_something();

val bytes = await pending;
// await ends the turn and resumes from a continuation later.
```

Calling an async operation is not itself a scheduling boundary.

## OresFuture is not CompletableFuture

`Future<T>` is represented by the runtime-owned `OresFuture<T>`.

It deliberately does **not** implement Java `CompletionStage` and does not
inherit `thenApply`, `thenAccept`, `thenRun`, or other APIs whose callback
may execute according to a producer's completion policy.

The runtime owns completion. Guest code may:

- observe done/cancelled state;
- request cancellation;
- await the Future.

Runtime components may register an internal completion waiter. Such a waiter may
only settle another runtime Future, release runtime accounting, or enqueue a
continuation. It must not execute Oreslang guest code.

Host `CompletionStage` values are compatibility inputs only. They are
immediately normalized into an OresFuture before they participate in Oreslang
suspension.

## Awaitable<T>

`await` is defined in terms of one language-level projection:

```ores
define interface Awaitable<T> as
  get_awaited() => Future<T>;
end
```

(`=>` is the syntax accepted by the current parser for callable return
declarations; the language-design notation may spell this callable return with
`->` once that syntax migration lands.)

The runtime equivalent is `Awaitable<T>.getAwaited() -> OresFuture<T>`.
`Future<T>` implements `Awaitable<T>` by returning itself. The two-phase
`ActorSpawn` control handle implements `Awaitable<ActorRef>` by returning its
readiness Future.

User classes may implement the same contract:

```ores
define class ReadyValue implements Awaitable<int> as
  pub get_awaited() => Future<int> {
    return Future.from_callback<int>(|cb| -> {
      cb.resolve(42);
      return;
    });
  }
end

val value = await new ReadyValue();
```

A statically known non-awaitable value is rejected:

```ores
val nope = await 123; // compile error: await requires Awaitable<T>
```

Dynamically imported/hot-loaded values whose static type is unresolved are
checked again at runtime before suspension.

## Callback-only API adaptation

A single-shot callback API can be turned into an Ores Future without granting
the callback producer authority to run Oreslang continuations:

```ores
val future = Future.from_callback<int>(|cb| -> {
  legacy_api(cb);
  return;
});

val value = await future;
```

The callback completion capability supports explicit settlement:

```ores
cb.resolve(value);
cb.reject(error);
cb.cancel();
```

and can itself be called in value-only or error-first form:

```ores
cb(value);
cb(error, value);
```

A callback may settle only once. A later `resolve`, `reject`, or `cancel`
is rejected as an already-settled callback Future.

Callback-only work can also be chained after a Future:

```ores
val next = first.attach_callback(|value, cb| -> {
  some_callback_only_api(value, cb);
  return;
});

val result = await next;
```

The registrar itself is synchronous: it registers the foreign callback and
returns. It may not `await`. If the foreign API invokes `cb` synchronously,
that invocation only settles the dependent Future. It **cannot** recursively
resume the awaiting Oreslang frame.

Repeated/multi-shot callback sources are not Futures. They belong in a
Stream/Channel/Observable-style abstraction.

## OresScheduler ownership

Every ordinary Oreslang task has exactly one owning `OresScheduler`.

The Future being awaited does **not** choose the scheduler. The waiting task
already owns one, and the compiler-generated async state machine is re-enqueued
there when the Future settles.

```ores
const io = new OresScheduler(5);

const work = io.start(async || -> {
    const a = await read_a();
    const b = await read_b(a);
    return b;
});
```

The task may execute on carrier #2 before an await and carrier #5 afterward.
Scheduler affinity is guaranteed; physical thread affinity is not.

Each scheduler task also owns a stable logical execution-domain token. Runtime
objects whose safety depends on local ownership, such as `Mutex<T>`, bind to
that task token rather than the transient carrier Thread. This lets a mutex
created before `await` remain owned by the same logical task after resumption,
while a different task on the same scheduler still has a distinct domain. Actor
code continues to use the actor's stable execution-domain token instead.

A single Future may therefore have waiters owned by different schedulers:

```text
shared Future
  +--> waiter A --> scheduler A
  +--> waiter B --> scheduler B
```

Its producer/completion thread only settles the Future. It never runs either
guest continuation.

The process entrypoint receives an implicit root `OresScheduler` backed by the
OresVM CONTROL domain. Root async turns use bounded admission and the same
reserved root lanes as legacy root work so they cannot consume every CONTROL
carrier and starve supervisor/ActorMailman work.

User-created `OresScheduler(n)` values own `n` carrier threads and a bounded
ready queue. They are constructed with ordinary Oreslang `new` syntax and are
owned by the current Ores context:

```ores
val io = new OresScheduler(5);

val work = io.start(async || -> {
    val response = await fetch_data();
    return response;
});

val response = await work;
io.close();
```

`scheduler.start(...)` accepts an inline zero-argument lambda. A synchronous
`|| -> { ... }` body is ordinary CPU/non-suspending work and is submitted as a
scheduler task; an `async || -> { ... }` body may use `await` and its captured
continuation remains scheduler-affine until completion. Both forms return an
`OresFuture<T>` for the lambda's logical result `T`. The context also closes any remaining user
schedulers during teardown, so forgotten scheduler handles cannot leak carrier
threads. A scheduler cannot close itself from one of its own task turns; close
is initiated from an outside/root task so teardown cannot self-cancel the turn
that is performing teardown.

Custom scheduler construction is forbidden from actor code: actors stay on
their owning SHARED/ISOACTOR/UNTRUSTED_ACTOR scheduler domain. Adversarial
contexts also cannot create custom scheduler pools.

User schedulers are managed context resources rather than raw thread authority.
The current runtime caps one pool at 64 carriers, caps a context at 32 user
schedulers and 256 user-scheduler carriers in total, rolls accounting back if
pool creation fails, and closes remaining pools during context teardown. Each
custom carrier is admitted by the language thread-access gate and explicitly
enters/leaves the owning Truffle context around every guest scheduler turn.

Actors are the deliberate special case. They do not migrate to a user-created
OresScheduler. Their await lowering continues to target the actor cell:

- SHARED actors -> SHARED_ACTOR domain;
- private/isoactors -> ISOACTOR domain;
- untrusted actors -> UNTRUSTED_ACTOR domain.

Each actor retains its atomic single-executor lease, so at most one carrier can
execute that actor's code at a time, including resumed await continuations.

## Async source rule

Ordinary `await` is permitted only inside an explicitly `async` ordinary
callable or lambda. An ordinary async callable has logical result type `T`,
while calling it produces `Future<T>`.

Actor callables/methods are inherently MAY_SUSPEND and therefore implicitly
async. `async actor` is intentionally redundant/illegal rather than a second
spelling for the same declaration.

## Await

The semantic lowering is:

```text
evaluate awaited operation
        |
        v
OresFuture<T>
        |
        v
capture resume point + live locals
        |
        v
register enqueue-only waiter
        |
        v
release logical execution lease / carrier
        |
        v
WAITING
        |
        | Future settles
        v
enqueue owning continuation
        |
        v
correct OresVM scheduler domain
        |
        v
reacquire logical execution lease
        |
        v
restore frame and continue after await
```

`await` is a scheduling boundary even when the Future was already settled.
The continuation is enqueued for another scheduler dispatch instead of being
resumed inline.

The scheduler is allowed to choose that continuation immediately if no
higher-priority work is runnable. With a one-carrier scheduler it can therefore
run again on the **same OS thread**. That does not weaken the invariant:

```text
carrier-7:
  dispatch #100 -> actor/task reaches await
  unwind all guest frames
  return to scheduler
  dispatch #101 -> same actor/task resumes
```

The physical native stack storage may be reused, but the previous guest call
frames are gone. Resumption starts from the heap/state-machine continuation in
a fresh logical dispatch. Runtime dispatch IDs exist specifically so tests can
distinguish "same carrier" from "same execution turn".

For actors, the current runtime target is
`ActorContext.suspendOn(OresFuture, ActorContinuation)`. The existing host
`CompletionStage` overload is only an adapter and normalizes to OresFuture.

A suspended actor remains logically inside the same mailbox turn:

- later inbox messages cannot overtake it;
- the admitted message graph remains rooted/accounted while captured by the
  continuation;
- completion threads never acquire actor execution authority;
- resumption may occur on a different carrier;
- the actor's generation lease remains valid across suspension.

## Proper async tail transfer

Oreslang treats a verified tail-position async call as a state-machine transfer,
not as a chain of pending Futures.

```ores
async fnc walk(int n) => int {
  if n == 0 do
    return 0;
  fi
  return await walk(n - 1);
}
```

The compiler/evaluator may lower the final statement to `TAIL_AWAIT` when:

- the source form is a true `return await <Oreslang async call>`;
- caller and callee logical return types are identical;
- no caller result conversion/post-processing remains;
- no active `defer`, `catch`, or `finally` scope must run afterward;
- the transfer stays in an ordinary task execution domain;
- loop/control scopes with separately-owned lexical state are not fused unless
  their cleanup has been modeled explicitly.

A tail transfer replaces the active `AsyncPlan` frame in the existing
`OresScheduler` task. It does **not** allocate a child scheduler task/Future.

`TAIL_AWAIT` is still an await boundary. The current turn transitions through
`WAITING`, releases its execution lease, fully unwinds the guest stack, and
the replacement frame becomes runnable only through a fresh scheduler dispatch.
The same physical carrier may be selected again, but inline recursive execution
is forbidden.

Lexical cleanup remains mandatory: scope-owned mutex/RW-lock guards are released
as on an ordinary return. Pending defer/error-handling cleanup disables fusion
or fails closed rather than being skipped.

Actor boundaries are intentionally not fused. An actor keeps its mailbox turn,
execution lease, scheduler domain, supervision/resource accounting, and
isolation boundary.

## Logical async stack traces

Physical carrier stacks are not the Oreslang call stack. Every async task keeps
bounded guest-language causal metadata containing:

- current Oreslang symbol;
- source id plus line/column;
- `await` boundaries;
- tail-await transfers;
- actor-message/runtime boundaries;
- a non-authoritative hot-load generation id.

The opaque generation-binding capability is never included in diagnostics.

Repeated identical tail transfers are run-length compressed:

```text
at walk (walk.ores:2:11 @gen=19)
--- tail-await walk -> walk repeated 100000 times (walk.ores:6:10 @gen=19) ---
```

Non-repeating history is capped and older events are explicitly elided. Keeping
one diagnostic frame per optimized tail call would recreate the eliminated
stack on the heap, so bounded history is part of the constant-space semantic
contract.

Failures keep their original exception/error and attach the logical Oreslang
trace as structured diagnostic data with synthetic guest frames. Awaiting parent
tasks can add their own causal trace without replacing the child's provenance.
This metadata is independent of physical carrier identity and is preserved
across JIT, AOT-interpreted, and hybrid execution.

## Safepoint is not suspension

A compiler/runtime safepoint checks control state such as cancellation, deadline,
fuel, debugger/maintenance requests, and untrusted execution policy.

A normal safepoint does **not** yield merely because it was reached.

For untrusted actors, a safepoint can become an enforced handoff/termination
point when fuel, deadline, or other sandbox policy requires it. This is separate
from cooperative `await`.

## Blocking-looking I/O

An Ores API may expose synchronous-looking behavior while using logical
suspension internally:

```ores
val bytes = fd.read(buffer);
```

If `read` is a suspending/blocking effect, the language semantics are:

```text
start/dispatch host operation
capture continuation
suspend logical Ores task
return carrier to OresVM
resume when operation completes
```

It must never mean "park this Ores actor carrier in a host blocking call."

Native async readiness should be preferred for sockets, pipes, timers, and other
reactor-friendly operations.

## VM-owned blocking bridge

When a host API is genuinely blocking, OresVM owns two implementation paths.

### Java blocking calls

Trusted Java blocking interop uses bounded admission plus Java virtual threads.

Virtual threads are an implementation substrate only:

- Actor != Java virtual thread.
- OresVM remains the scheduling authority.
- Java virtual threads do not receive ActorCell, OresVM, OresContext, or guest
  mutable actor state.
- completion settles an OresFuture; it never resumes guest code directly.

### JNI / FFM / native or unknown blocking calls

Native calls can pin or ignore virtual-thread unmounting, so they use a bounded
platform-thread executor.

The native queue uses abort-on-saturation. It never uses CallerRunsPolicy.
Therefore saturation cannot make an actor carrier execute the blocking call.

Cancellation is a request, not proof that host work stopped. Admission for a
running uncooperative Java blocking call remains charged until its worker
actually exits.

## Four scheduler domains

Continuation wakeup preserves the task's owning domain:

```text
OresVM
├── CONTROL
│   ├── supervisor/root work
│   └── ActorGroup mailmen
├── SHARED_ACTOR
├── ISOACTOR
└── UNTRUSTED_ACTOR
```

I/O reactors, timer drivers, completion threads, Java virtual threads used for
blocking interop, and native blocking workers are runtime service threads. They
are not additional Ores scheduler domains and may not execute actor guest code.

## Compiler effect model

The intended compiler effect categories are:

```text
NOSUSPEND
MAY_SUSPEND
BLOCKING_HOST
```

Pure code is a refinement of NOSUSPEND rather than a scheduling category.

`await` is an explicit MAY_SUSPEND boundary. A synchronous-looking I/O
primitive may be MAY_SUSPEND because its implementation dispatches host work and
suspends the logical task. BLOCKING_HOST identifies the low-level interop
operation that must be moved to the VM blocking bridge.

The compiler must reject ordinary borrows/guards that would escape across a
suspension unless their ownership/lifetime representation explicitly permits it.

Actor external-state rules are stricter:

- actor-owned mutable fields/state may be written only while that actor holds its
  single-executor lease;
- mutable lexical captures from outside the actor are forbidden;
- SHARED actors may receive an explicit `OresRwLock<T>` capability and acquire
  only its read guard;
- private/isoactors and untrusted actors cannot access that shared-memory
  capability;
- no actor may acquire an `OresRwLock<T>` write guard;
- actor/scheduler carriers never block waiting for an external RW lock; a
  contended immediate acquisition fails with `WouldBlock` and future async-lock
  lowering will suspend through `OresFuture` instead of parking a carrier;
- `SharedMutex<T>` is not an actor escape hatch for external mutation and is
  rejected when acquired from actor execution;
- legacy `SyncCell<T>` is host/root-only; actors cannot receive, read, update,
  close, or create it as shared mutable state;
- an RwLock guard is lexical/thread-affine and therefore may not live across
  `await`.

Outside actors, ordinary root/scheduler code may hold the write side of an
`OresRwLock<T>` and publish updates. The Oreslang type checker must expose an
actor read guard's value as read-only.

## Current lowering boundary

Async source evaluation is lowered into explicit heap/state-machine plans.
Await points hold the Future plus a continuation; scheduler tasks resume that
plan only through their owning scheduler. Actor callables use the corresponding
stackless actor plan runner and `ActorContext.suspendOn(...)` ABI.

The lowering must never:

- block a scheduler/actor carrier;
- use `join()` from actor code;
- inline-resume an already-completed Future;
- allow a producer/completion thread to execute guest code;
- tail-fuse across an actor boundary;
- tail-elide a frame whose `defer`, `catch`, `finally`, lexical guard, or
  other control-scope cleanup still has work.

The recursive reference evaluator remains only as a host/root compatibility
path. All actor/async guest execution uses heap-safe continuations. Interpreter,
JIT, AOT-interpreted, and hybrid execution must preserve the same guest-level
scheduling, tail-transfer, and logical-trace semantics.
