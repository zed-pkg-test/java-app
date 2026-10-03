# Runtime isolation, hot reload, and compilation profiles

## Invariants

1. Guest source never grants itself authority.
2. The supervisor/launcher supplies an `IsolatePolicy`.
3. Compiler admission rejects language APIs not in that policy.
4. Runtime API facades repeat the authorization check.
5. Graal host access, native access, environment access, guest-created threads, host IO, and unrestricted polyglot access are disabled by default in restricted contexts.
6. Actors cannot exceed their configured mailbox capacity.
7. Hot reload never requires loading executable native libraries.
8. Every hot-loaded generation is a fresh guest context and may be mapped to a stronger Graal/native isolate by the production host.
9. `self` cannot be rebound.
10. An actor has one mailbox and never executes two mailbox turns concurrently.
11. Private, shared, and untrusted actors use separate dispatcher thread pools.
12. Private and untrusted actor transport rejects synchronized shared-memory cells.
13. Shared actor state is still actor-owned; ordinary actor field mutation is serialized by the mailbox, not by implicit locks.
14. Actor `self` and move-only actor-owned state cannot escape a mailbox turn as ordinary mutable aliases.
15. Synchronized shared memory requires `SHARED_MEMORY`; strict FaaS does not grant it by default.
16. Private slices and synchronized shared cells compete for one parent actor-memory ceiling.
17. Actor message graphs are cycle-checked and bounded by depth, node count, and logical byte quotas before transport.
18. SharedMutex runtime ownership is reserved before mailbox visibility and committed only after successful admission; failed first publication rolls back.
19. An untrusted actor has a hard lifetime ceiling of 300 seconds, a per-message execution-fuel budget, a bounded mailbox-return budget, and no ambient filesystem/network/FFI/process/thread authority.
20. Large HTTP request/response bodies use owner-bound per-exchange stream capabilities rather than actor mailboxes or raw file-descriptor authority.
21. Actor-owned native memory regions are zeroed and their FFM arenas are closed during actor teardown; logical reservations are released in the same teardown path.

## Deployment matrix

| Profile | Host | Guest execution | Hot reload |
| --- | --- | --- | --- |
| JIT | JVM/GraalVM | interpreter -> Truffle JIT | fresh source generation |
| AOT | Native Image | precompiled interpreter | fresh source generation, no executable-code load |
| HYBRID | Native Image | interpreter -> guest JIT where supported | fresh source generation |

iOS is treated as AOT-only by the execution-profile validator. Android may use AOT or another profile where platform policy allows it.

## Secure JIT hot-load execution domains

Hot loading is a control-plane operation, not a guest capability. The trusted
supervisor owns `HOT_CODE_LOAD`; a staged guest generation receives a separate
policy with loader authority removed.

The runtime exposes four generation domains:

| Domain | Host/runtime shape | Guest compilation | Security boundary |
| --- | --- | --- | --- |
| `TRUSTED_JIT` | shared JVM/Graal Engine | Truffle/Graal JIT | Ores ownership + capability model |
| `ISOLATED_JIT` | spawned Graal isolate | Truffle/Graal JIT | isolate sandbox + Ores capabilities |
| `UNTRUSTED_JIT` | spawned Graal UNTRUSTED isolate | Truffle/Graal JIT | deny-by-default isolate + quotas + Ores capabilities |
| `AOT_INTERPRETED` | stable Native Image/AOT host | source/IR interpreted by shipped runtime | deployment/process boundary |

The intended server architecture is therefore asymmetric: keep the small
supervisor, watchdog, process broker, and ingress/control plane AOT where that
reduces attack surface and startup variance; keep reloadable application/actor
code JIT so it can be versioned, optimized at runtime, and replaced without
restarting the supervisor.

### Two-phase activation

Loading a generation never routes new work to it. The sequence is:

```text
validate source/IR
  -> capability admission
  -> create fresh context/isolate
  -> STAGED
  -> start/init/readiness
  -> STARTED
  -> atomic activate
  -> ACTIVE

previous ACTIVE
  -> DRAINING
  -> close after final actor/request generation lease is released
```

A startup failure never displaces the healthy active generation.

Actors and requests that must remain on the code they started with pin the
active generation through a `GenerationLease`. New work uses the newly active
generation after the atomic switch; old work drains against its pinned context.
This prevents a hot reload from replacing code in the middle of one actor turn
or request.

### Loader authority versus guest authority

`HotReloadManager` accepts separate supervisor and guest policies. The
supervisor must hold `HOT_CODE_LOAD`. The guest policy always has
`HOT_CODE_LOAD` removed before a context is built.

`ISOLATED_JIT` additionally strips native/FFI, reflection, arbitrary
polyglot, and guest-thread escape hatches and forces the adversarial
spawned-isolate path.

`UNTRUSTED_JIT` is stricter still: ambient capabilities are empty, heap/time/
mailbox ceilings are intersected with the `UntrustedActor` ceiling, source and
code-unit identifiers are bounded before context creation, and the number of
simultaneously live generations is capped. Narrow HTTP request/response and
send-only `Recipient` handles remain object capabilities, not ambient network
authority.

The JIT itself is never treated as the sandbox. Isolation, capabilities,
ownership/sendability, resource limits, and OS/process containment remain the
security boundaries.

## Why hot reload is source/IR based

Native Image is fundamentally closed-world for Java classes. Oreslang therefore does not make hot reload depend on dynamically linking new Java/native code. The runtime/interpreter is part of the shipped artifact; newly downloaded Oreslang source (and later a stable serialized Ores IR) is treated as untrusted data, validated, then executed in a new generation.

That makes the mechanism consistent across Windows, macOS, Linux, Android, and AOT-only targets. Platform-specific native dynamic linking can remain an optional trusted-host optimization, never a semantic dependency.

## Capability ownership

Capabilities belong to a launch policy, not to source code. Source may eventually declare required capabilities for diagnostics, but declarations will never grant them.

The strict production direction is:
- parent supervisor owns maximum authority;
- child isolate/actor receives an equal-or-smaller capability set;
- no child may escalate its own policy;
- cross-actor values must pass sendability/freezing rules;
- hot-loaded code gets a new generation and new policy admission.

## Receiver implementation

Method code is stored once per class declaration. Direct calls dispatch to that definition with the receiver as an implicit immutable argument. Only first-class method extraction allocates a bound method pair. This provides Go-like receiver safety without allocating a closure for every instance or every direct method invocation.


## Truffle thread boundary

`ActorRuntime` owns host dispatcher threads; guest code still receives no ambient thread-creation authority. A dispatcher carrier is marked by the runtime, explicitly enters/leaves the associated `TruffleContext` for each actor batch, and only marked actor carriers are accepted for concurrent context access.

Non-adversarial contexts may therefore execute independent actor turns concurrently. Strict/adversarial contexts currently serialize guest actor turns with a fair context-level lock even though private/shared dispatcher pools remain separate. This preserves the strict one-guest-thread sandbox contract until isolated/private actor execution is backed by per-actor inner/polyglot/native contexts.

The guest `THREAD_CREATE` capability is separate from host/runtime dispatcher scheduling. Denying guest-created threads is never bypassed merely because the runtime owns carrier pools.


## Mutex and shared-memory model

Oreslang has two deliberately different mutex domains:

- `Mutex<T>` is actor/private-domain state. It owns the protected value, uses no JVM lock, is non-reentrant, and is confined to the creating semantic actor/execution domain. Shared actors may migrate between JVM workers without changing that domain.
- `SharedMutex<T>` is an explicit same-OS-process shared-memory capability within one `ActorRuntime`. It is non-reentrant and uses acquire/release synchronization. Only shared actors may receive/use it; private actors reject it even when the parent runtime is otherwise trusted. Sender and receiver must have `SHARED_MEMORY`. It binds transactionally to the first runtime that successfully publishes it, and later cross-runtime transport is rejected.
- The payload and declared type argument of `SharedMutex<T>` must be **SharedSafe**: concrete owned data whose reachable field graph contains no borrows, actor-local `Mutex`, `MutexGuard`, pending `Future`, closure/function values, or unresolved dynamic/generic state. This applies to signatures/fields/aliases as well as `SharedMutex.new(...)`. Until Oreslang has an explicit SharedSafe generic bound, unconstrained `SharedMutex<T>` is rejected conservatively. The type checker recursively validates class fields (including inherited generic substitutions), and the interpreter repeats runtime admission checks as defense in depth.
- `MutexGuard<T>` is a lexical linear capability. The runtime releases it on normal scope exit and poisons a shared mutex on abnormal scope exit. Guest code may call `guard.release()` for early release; there is intentionally no `mutex.unlock()`. A released guard can no longer expose its protected value.
- Guard access is deliberately non-escaping. Copy-like fields may be read, mutable fields may be replaced, and methods may be invoked directly when they return `void` or a copy-like value. Move-only nested fields and bound method values cannot be extracted through a guard. For compound mutation, use `with_lock(|state| -> { ... })`.
- `with_lock` and `recover` require an inline one-argument, `void` lambda. The callback parameter is treated as a lexical exclusive `&mut T`: it may mutate protected state but cannot move or return that state, escape it through a closure, or suspend with `await`.
- `await` while a guard is live and closure capture of a guard are compile-time ownership errors. Guard-bearing results must be bound once with `val`; they cannot be discarded, reassigned, stored in aggregates, passed through arbitrary calls, or hidden inside another mutex.
- Blocking `SharedMutex.lock()`/timed acquisition is rejected while executing an actor. `lock_async()` returns a runtime-owned, caller-cancellable `GuardFuture` and is the nonblocking acquisition primitive. Contended async acquisition is queued inside the mutex and receives the permit by direct guard handoff; it does **not** allocate one helper thread per waiter. Acquisitions reserve the semantic actor/execution domain before waiting, so recursive async acquisition fails instead of self-deadlocking even if an actor migrates JVM workers. Cancellation removes queued waiters and releases their domain reservation; poisoning drains queued async waiters with `PoisonedMutexException`. Admission is bounded by the current actor mailbox policy, an 8,192-waiter ceiling per mutex, and a 32,768-waiter JVM-process ceiling. When blocking host waiters and async waiters coexist, release alternates handoff preference so neither class monopolizes the mutex. The runtime also exposes `lockAsyncFor(Duration)`, using the same queue plus one shared daemon timeout scheduler; expiry completes with `LockTimeoutException` and removes the waiter immediately. This remains a backend/runtime API until source-level duration/timeout representation is finalized. Language `await` lowering must suspend/resume the actor turn rather than synchronously join an incomplete future; until continuation lowering is complete, actor-backed singleton/mailbox serialization is preferred over contended shared-memory locking from shared actors.
- A poisoned `SharedMutex<T>` rejects ordinary acquisition until `recover(...)` repairs invariants and clears poison. `recover` is not an ordinary lock operation: it is rejected when the mutex is healthy. Inside actor execution recovery is nonblocking; if another recovery owns the permit, the actor must retry on a later mailbox turn rather than park.

Example:

```ores
val state = Mutex.new(new Counter());
val guard = state.lock();
guard.count = guard.count + 1;
guard.release();

val shared = SharedMutex.new(new Cache());
val shared_guard = await shared.lock_async();
shared_guard.increment_hits();
shared_guard.release();

shared.with_lock(|cache| -> {
  cache.put("key", "value");
  return;
});
```

A process-wide `singleton module` is **not** raw shared memory. It is owned by one hidden singleton actor and accessed through its typed mailbox/proxy, so its mutable state is serialized by actor execution and normally requires no mutex. Do not wrap singleton-module state in `SharedMutex<T>` merely because multiple actors can call it. Use `SharedMutex<T>` only when code deliberately opts into a writable same-process memory object that multiple actor/execution domains may dereference directly.

When the private-arena/`isoactor` runtime is stacked with this work, isolated actors must run without `SHARED_MEMORY` authority. An `isoactor` may receive copied/frozen messages, but it must not receive a `SharedMutex<T>` or any other writable JVM-heap alias; otherwise the language would no longer be able to claim true actor memory isolation.

The current reference runtime uses a one-permit JVM semaphore for `SharedMutex<T>`. Java semaphore release/acquire provides the required memory-ordering edge and, unlike a thread-owned `ReentrantLock`, allows an asynchronously acquired guard to be resumed and released by the actor execution context.

Actor transport is independently hardened from mutex synchronization. Ordinary messages are recursively frozen with cycle detection and hard depth/node/byte budgets (256 levels, 100,000 nodes, 16 MiB estimated frozen size). Read-only shared wrappers are runtime-constructed and revalidated on every boundary. `ActorRef` capabilities may cross only inside their owning `ActorRuntime`; a wrapper cannot be used to smuggle a foreign actor reference into another runtime. When an `ActorRef` is explicitly granted to an untrusted actor it is a bounded messaging capability only: untrusted code may not use it to stop another actor or synchronously wait for that actor's termination. Prefer `ActorRef.recipient()` / `Recipient<M>` when only send authority is required; `Recipient<M>` exposes no lifecycle, wait, or failure-inspection surface and is validated as a runtime-owned channel capability at mailbox boundaries. Arbitrary host-controlled `Sendable` callbacks are not part of the transport boundary. Runtime-owned capabilities have explicit cases, while ordinary message graphs are recursively frozen/copied and validated.

Compiler-generated/context-aware `BehaviorFactory` values are capture-free for both private and shared actors. This prevents a shared actor from bypassing mailbox/capability semantics by closing over an arbitrary mutable JVM object. `spawnPrivateTrusted(...)`, `spawnSharedTrusted(...)`, and trusted `Supplier` construction are host/supervisor escape hatches only; adversarial policies reject them.

## Actor dispatchers

The scheduler combines three useful actor-runtime ideas: Erlang-style bounded execution quanta, Akka-style bounded mailbox throughput and bulkheading, and Rust/async actor practice that keeps one actor's handler serial while multiplexing many actors over a smaller execution substrate.

Oreslang deliberately has exactly three actor execution lanes:

- **shared/process dispatcher** — shared actors plus trusted host/main-process tasks;
- **private dispatcher** — trusted/cooperative memory-isolated actors using isolation-copy transport;
- **untrusted dispatcher** — adversarial memory-isolated actors with mandatory fuel/deadline checks.

The default worker allocation divides the available CPU budget across the three lanes instead of assigning a full CPU-count pool to every lane. Every lane still receives at least one worker. This prevents a busy trust domain from consuming every actor carrier merely because the machine has many cores.

Each actor owns exactly one mailbox and one atomic scheduling gate, so it can have at most one ready/running drain task. Ready queues are bounded FIFO queues with fair lock acquisition. Trusted private/shared actors process at most `DispatcherConfig.throughput` messages per dispatch turn; if work remains, the actor is re-enqueued at the tail. **Untrusted actors always process exactly one mailbox message per dispatch turn**, regardless of trusted throughput, before returning to the sandbox ready queue.

The runtime records per-lane queue depth, active workers, completed dispatches, maximum ready-queue wait, and starvation events (ready wait >= one second) through `dispatcherStats(...)`. These are diagnostics rather than a claim that an overloaded finite machine can make starvation mathematically impossible.

Trusted main-process work can be submitted through `submitProcessTask(...)`, which deliberately uses the shared/process pool. Actor turns cannot call that API: shared actors already run on the lane, and allowing an actor to enqueue arbitrary extra process tasks would create a scheduler-amplification path. Host process work has a separate in-flight admission limit (two tasks per shared worker by default), and the shared executor reserves additional queue capacity for those process tasks rather than letting them consume the actor-ready capacity implied by `maxActors`.

### Cooperative versus uncooperative execution

For trusted private/shared Oreslang code, compiler-injected scheduler safepoints remain cooperative. Mailbox batching prevents a hot **mailbox** from monopolizing a worker, and safepoints yield CPU to the operating system, but the JVM cannot safely suspend an arbitrary Java stack in the middle of one handler and later resume it as an actor continuation. A trusted host callback that never returns is therefore a programming error; blocking/CPU-heavy host work must be moved to an explicitly managed service rather than hidden inside an actor turn.

Untrusted actors use a different contract. They have their own pool, a one-message dispatcher quantum, mandatory statement/expression/call/loop fuel checkpoints, a hard lifetime watchdog, bounded mailbox output, per-actor heap/mailbox limits, and the parent runtime's aggregate actor-memory ceiling. The watchdog may interrupt the currently active sandbox carrier as a wake-up/control signal, and the turn clears that interrupt before a pooled worker is reused. Production security relies on metered Oreslang guest execution plus the killable Graal/OS sandbox boundary, not on Java's inability to force-stop an arbitrary hostile host callback safely.

This separation means an uncooperative sandbox callback can consume at most the sandbox lane's configured carrier capacity; it cannot take a private/shared carrier. Tests deliberately run a busy, checkpoint-free sandbox callback while private and shared actors continue to make progress on their own pools.


## Untrusted actor sandbox

`UNTRUSTED` is a third actor kind, not merely a flag on a shared actor. It inherits the private actor's isolation-copy transport and actor-owned memory slice, but it is always adversarial and receives a strictly reduced capability set. It cannot create child actors or gain `SHARED_MEMORY`, generic `NETWORK`, filesystem access, environment access, FFI/native access, reflection, child-process creation, guest thread creation, hot-code loading, or polyglot access.

The default sandbox limits are:

- maximum lifetime: **300 seconds** (an absolute upper bound; a host may choose less);
- execution fuel: **100,000 units per mailbox turn**;
- outbound actor-mailbox payload: **1 MiB**;
- HTTP request body read budget: **16 MiB**;
- HTTP request metadata budget: **64 KiB**, including an **8 KiB** path ceiling and at most **128** header lookups;
- HTTP response body write budget: **16 MiB**;
- HTTP response metadata budget: **128 headers / 64 KiB**;
- isolated actor heap: **64 MiB**;
- mailbox: **128 messages**.

HTTP is granted as an **object capability to one accepted exchange**, not as ambient network access. A host may bind `HttpRequestTransport` / `HttpResponseTransport` to its HTTP parser, event loop, `SocketChannel`, or equivalent. The actor receives owner-bound `HttpRequestCapability` / `HttpResponseCapability` handles through its turn context. Request-body chunks are read from the host request stream and response-body chunks are written to the host response stream directly; they are not serialized through the actor mailbox. `PrivateMemoryBlock.readFrom(...)` and `writeTo(...)` can bind those streams directly to the actor's FFM-backed native region, avoiding an intermediate JVM heap byte array as well. The handles are not Sendable, cannot be transferred to another actor, and expose no general socket/file-descriptor operations. This also keeps the model valid for HTTP/2 or HTTP/3, where a raw connection fd would be the wrong abstraction.

An untrusted actor's language-managed native blocks are allocated from closeable cross-thread FFM arenas. Teardown zeroes each block, closes its arena deterministically, drains mailbox reservations, drops behavior roots, cancels/aborts attached HTTP streams, and releases the actor slice's accounting. JVM metadata/wrapper objects remain ordinary managed objects; Oreslang therefore promises deterministic release of actor-owned native regions and runtime quotas, not the impossible claim that every JVM bookkeeping object disappears synchronously.

The watchdog provides a wall-clock kill boundary, while mandatory compiler/runtime checkpoints provide safe preemption of guest computation. Arbitrary native/FFI callbacks are forbidden precisely because a host call that ignores interruption and never returns could defeat language-level checkpoints; stronger deployment backends may additionally place an untrusted actor in a killable Graal/native/OS isolation unit without changing these source semantics.

## Shared actor memory

Shared actors keep ordinary mutable fields actor-owned and mailbox-serialized. Cross-actor mutable memory is exceptional and represented by `SyncCell<T>`.

`SyncCell<T>` is runtime-owned and closeable. Its frozen state consumes shared actor-memory quota; growth reserves quota before publishing a replacement value, shrink/close returns quota, and runtime teardown closes remaining cells. The combined private-slice plus shared-cell total cannot exceed the parent `IsolatePolicy.maxHeapBytes()`.

A private actor turn cannot create, snapshot, read, update, or close synchronized shared state even if trusted host code accidentally captured a cell handle. Source admission and runtime creation both require `SHARED_MEMORY`.

Actor failures are fail-stop in this layer. The actor ref retains the failure cause for diagnostics, queued reservations are drained, and later sends receive an `ActorTerminatedException` rather than silently targeting a dead mailbox.

## Private actor memory confinement

A private or untrusted actor is assigned an `ActorMemorySlice` when it is created. The slice is keyed by actor identity rather than by dispatcher thread because actor turns may migrate between worker threads.

Private mailbox admission is:

1. reject explicitly shared mutable handles such as `SyncCell<T>`;
2. isolation-copy/freeze the message graph;
3. conservatively estimate its logical Oreslang heap size;
4. reserve those bytes against the destination actor slice and the parent runtime budget;
5. enqueue only after both reservations succeed;
6. release transient mailbox bytes after the mailbox turn completes.

Persistent generated actor state reserves from the same slice. Actor teardown closes the entire slice, so leaked host-side reservation handles cannot keep a dead actor's memory budget alive.

The logical size metric intentionally does not claim to equal JVM object layout. It exists to enforce Oreslang memory-domain policy while actors remain multiplexed on one JVM. A hardened backend may replace the accounting implementation with arena/region allocation or a Graal/native isolate without changing source semantics.

A memory-isolated actor's memory owner is its **ActorId**, never its carrier thread. Successive mailbox turns may execute on different private-dispatcher workers. Consequently a future FFM/off-heap backend must not make `Arena.ofConfined()` carrier-thread identity part of Oreslang semantics. It should use a cross-thread-capable region whose access is guarded by the actor owner token, or map the private actor to a true Graal/native isolate when physical heap isolation is required.
