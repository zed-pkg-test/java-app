# Futures, await, blocking I/O, and scheduler suspension

Status: runtime Future/suspension ABI implemented on top of the OresVM five-domain
scheduler. Actors already suspend through captured continuations. Root/main tasks
now have the same runtime state-machine substrate on a dedicated ROOT_TASK carrier
pool. Source-frame lowering for arbitrary nested `await` expressions remains the
compiler follow-up; the recursive evaluator fails closed inside actor or root turns
rather than blocking, joining, or inline-resuming a carrier.

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

## Virtual-thread-like logical task model

Oreslang tasks are conceptually closer to **Java virtual threads / structured
tasks** than to bare Go goroutines in one important respect: a spawned async
operation has an awaitable completion object.

```ores
pub async fnc calculate(int value) : int {
    return value * 2;
}

pub async fnc main() : void {
    val pending = calculate(21);
    val answer = await pending;
    print(answer);
}
```

The intended lowering is:

```text
async fnc call
    -> create logical Ores task
    -> return Future<T> immediately
    -> execute task on owning scheduler domain
    -> await suspends that logical task
    -> Future completion makes its continuation runnable
```

The logical task is **not** a Java platform thread and does not permanently own
a carrier. It may execute on carrier A, suspend, and resume on carrier B. That is
the property Oreslang borrows from virtual threads: blocking-looking source
semantics without pinning a scarce carrier.

This is deliberately unlike a bare goroutine handle: Oreslang async work has a
first-class `Future<T>` result that can be awaited, cancelled, composed, and
used for structured lifetime management.

Actors use the same suspension substrate with stronger mailbox/ownership rules.
An `ActorRef` exposes an awaitable `done()` lifecycle Future, while one-shot
actor callables expose `ready()`, `done()`, and `result()` Futures. Guest
code must await those Futures; synchronous actor termination joins are host-only.

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

## Awaitable<T> protocol

`await` is defined over a compiler-known nominal protocol rather than over a
magic field name or arbitrary reflection.

Conceptually, the built-in contract is:

```ores
define interface Awaitable<T> {
  fnc getAwait() => Future<T>;
}
```

`Future<T>` itself implements `Awaitable<T>`; its `getAwait()` projection
returns the same Future. Runtime handles may also implement the protocol. For
example, `ActorSpawn<R>` projects its readiness Future so `await spawn`
continues to mean "wait until the actor identity is ready."

A user type may compute the projected Future instead of storing it in a fixed
field:

```ores
define class DeferredReply implements Awaitable<Response> as
  private val Request request;

  pub getAwait() => Future<Response> {
    return self.request.response_future();
  }
end
```

The hook may itself be `async`:

```ores
define class LazyReply implements Awaitable<Response> as
  pub async getAwait() => Response {
    val headers = await load_headers();
    return await load_body(headers);
  }
end
```

An `async getAwait() : T` has the same effective projection type as a
synchronous `getAwait() : Future<T>`, because calling any async callable
produces `Future<T>`.

The lowering of `await value` is exactly:

```text
evaluate value exactly once
        |
        +-- Future<T> ----------------------+
        |                                   |
        +-- Awaitable<T>                    |
              |                             |
              +-- invoke getAwait() once    |
                      |                     |
                      +---- Future<T> -------+
                                            |
                                            v
                              capture continuation
                                            |
                                            v
                                  suspend on Future
```

Important invariants:

- `getAwait()` is invoked at most once for one evaluation of an `await`
  expression; resumption never invokes it again.
- The synchronous form must return `Future<T>`; the async form declares
  `T` and its call result is `Future<T>`.
- `getAwait()` may lazily construct the Future.
- A null/non-Future projection is a runtime contract violation.
- The compiler rejects `await` on values that are neither `Future<T>` nor
  nominally `Awaitable<T>`.
- Oreslang never scans fields looking for a Future and never uses reflection to
  guess what should be awaited.
- Interfaces may extend `Awaitable<T>`, allowing framework-specific task
  handles to add richer APIs without changing `await` semantics.
- Cancellation/error identity comes from the projected Future.
- AOT lowering can resolve the projection statically; no dynamic plugin lookup
  is required.

The recursive reference evaluator now schedules ordinary async callables,
including `async getAwait()`, as real logical ROOT_TASK executions and returns
their Ores Future. A non-suspending async hook therefore works end-to-end today.

If the async hook itself reaches a nested source `await` while running on an
Ores carrier, that nested suspension still fails closed until general
stackless/CPS source-frame lowering can preserve the evaluator frame. The
scheduler/Future ABI underneath is already the same one that lowerer targets.

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
The continuation is enqueued for a later turn instead of being resumed inline.

For actors, the runtime target is
`ActorContext.suspendOn(OresFuture, ActorContinuation)`. For root/main tasks,
the runtime target is `ActorRuntime.suspendCurrentRootOn(OresFuture,
RootContinuation)`. Both paths capture state before suspension and both enqueue
the continuation for a later turn; neither resumes guest code on the producer
thread. The existing host `CompletionStage` actor overload is only an adapter
and normalizes to OresFuture.

A suspended actor remains logically inside the same mailbox turn:

- later inbox messages cannot overtake it;
- the admitted message graph remains rooted/accounted while captured by the
  continuation;
- completion threads never acquire actor execution authority;
- resumption may occur on a different carrier;
- the actor's generation lease remains valid across suspension.

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

It must never mean "park this OresVM carrier in a host blocking call."

As a defense-in-depth rule, host-style `OresFuture.get()` and `join()` reject
calls from every OresVM carrier. Host/embedder threads may use those APIs; guest
execution must use scheduler suspension.

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

## Five scheduler domains

Continuation wakeup preserves the task's owning domain:

```text
OresVM
├── CONTROL
│   ├── supervisors
│   ├── ActorGroup mailmen
│   └── VM maintenance
├── ROOT_TASK
│   ├── main
│   └── ordinary async Ores tasks
├── SHARED_ACTOR
├── ISOACTOR
└── UNTRUSTED_ACTOR
```

ROOT_TASK is physically separate from CONTROL so application async work cannot
consume supervisor/mailman carrier capacity. A root continuation may resume on a
different ROOT_TASK carrier than the one on which it suspended.

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

## Current lowering boundary

The actor scheduler, root-task scheduler, OresFuture, timer path, and blocking
bridge provide the runtime substrate. The recursive reference evaluator cannot
safely preserve an arbitrary Java call stack across an actor or root/main
`await`.

Until the stackless/CPS source-frame transformation lands, source `await`
encountered inside an actor or root turn fails closed instead of:

- blocking the carrier;
- using `join()`;
- inline-resuming an already-completed Future;
- allowing a producer thread to execute guest code.

The compiler lowerer must produce heap-safe resume frames containing the program
counter and live Oreslang locals and call the existing suspension ABI.
