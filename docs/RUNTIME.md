# Runtime isolation, hot reload, and compilation profiles

## Invariants

1. Guest source never grants itself authority.
2. The supervisor/launcher supplies an `IsolatePolicy`.
3. Compiler admission rejects language APIs not in that policy.
4. Runtime API facades repeat the authorization check.
5. Graal host access/class lookup, native access, environment access, guest-created threads, host IO, and unrestricted polyglot access are disabled by default in restricted contexts.
6. Actors cannot exceed their configured mailbox capacity.
7. Hot reload never requires loading executable native libraries.
8. Every hot-loaded generation is a fresh guest context and may be mapped to a stronger Graal/native isolate by the production host.
9. `self` cannot be rebound.
10. An actor has one mailbox and never executes two mailbox turns concurrently.
11. Private and shared actors use separate dispatcher thread pools.
12. Private actor transport rejects synchronized shared-memory cells.
13. Shared actor state is still actor-owned; ordinary actor field mutation is serialized by the mailbox, not by implicit locks.
14. Actor `self` and move-only actor-owned state cannot escape a mailbox turn as ordinary mutable aliases.
15. Legacy host/runtime synchronized shared memory requires `SHARED_MEMORY`; actor policies strip that authority. Actor-facing synchronized sharing uses the narrower `ACTOR_SHARED_PROXY` capability.
16. Private slices and synchronized shared cells compete for one parent actor-memory ceiling.
17. Actor message graphs are cycle-checked and bounded by depth, node count, and logical byte quotas before transport.
18. SharedMutex runtime ownership is reserved before mailbox visibility and committed only after successful admission; failed first publication rolls back.

## Deployment matrix

| Profile | Host | Guest execution | Hot reload |
| --- | --- | --- | --- |
| JIT | JVM/GraalVM | interpreter -> Truffle JIT | fresh source generation |
| AOT | Native Image | precompiled interpreter | fresh source generation, no executable-code load |
| HYBRID | Native Image | interpreter -> guest JIT where supported | fresh source generation |

iOS is treated as AOT-only by the execution-profile validator. Android may use AOT or another profile where platform policy allows it.

## Why hot reload is source/IR based

Native Image is fundamentally closed-world for Java classes. Oreslang therefore does not make hot reload depend on dynamically linking new Java/native code. The runtime/interpreter is part of the shipped artifact; newly downloaded Oreslang source (and later a stable serialized Ores IR) is treated as untrusted data, validated, then executed in a new generation.

That makes the mechanism consistent across Windows, macOS, Linux, Android, and AOT-only targets. Platform-specific native dynamic linking can remain an optional trusted-host optimization, never a semantic dependency.

## Native-first language/runtime boundary

Java/Truffle is a reference/bootstrap backend, not the semantic definition of
Oreslang runtime features. Ores-owned facilities should lower through
backend-independent runtime intrinsics and, where practical, be implemented in
the native runtime. JNI may be used as a narrow bridge from the Java-hosted
compiler/runtime, but Java reflection, JVM class identity, and Java library
behavior must not define Oreslang semantics.

Type tests, casts, and pattern matching therefore use Oreslang type and
constructor metadata. Java host objects remain explicit capability-gated
interop values and do not acquire Oreslang nominal identity through JVM
`instanceof`.

The native ABI direction and migration rules are specified in
[NATIVE_RUNTIME_ABI.md](NATIVE_RUNTIME_ABI.md).

## Java host interop boundary

Java imports are a two-key boundary. Source may name an explicit `java:` class, but execution requires both:

1. the Oreslang `JAVA_INTEROP` capability; and
2. an exact fully-qualified host-class allowlist entry supplied by the launcher/embedder.

The runtime uses Graal host lookup rather than guest-side `Class.forName`, disables host class loading, exposes only public **declared** members of explicitly allowlisted classes, and disables access inheritance. This prevents admitting one class from automatically exposing inherited reflection such as `Object.getClass()`.

Private/memory-isolated actors have `JAVA_INTEROP` stripped from their effective policy. Java host objects are wrapped as host capabilities, are not ordinary Oreslang data, and are rejected from synchronized shared-state publication. Adversarial isolates cannot grant `JAVA_INTEROP` at all.

Class-level interop blocks VM-control/reflection infrastructure (for example `Runtime`, `System`, `Class`, class loaders, reflection/invoke, script/compiler APIs, and JDK internals). Broad JDK families additionally require their corresponding Ores capabilities: filesystem, network, thread creation, native access, or process info. Third-party classes remain the embedder's responsibility: allowlisting one explicitly grants access to its public declared host surface.

Example:

```text
oreslang-compiler --allow=JAVA_INTEROP --allow-host-class=java.util.ArrayList app.ores
```

## Capability ownership

Capabilities belong to a launch policy, not to source code. Source may eventually declare required capabilities for diagnostics, but declarations will never grant them.

The strict production direction is:
- parent supervisor owns maximum authority;
- child isolate/actor receives an equal-or-smaller capability set;
- no child may escalate its own policy;
- cross-actor values must pass sendability/freezing rules;
- hot-loaded code gets a new generation and new policy admission.

## Receiver implementation

Method code is stored once per class declaration. Object instances store state/layout data, not private copies of their methods.

A direct instance call dispatches through the statically known `(INSTANCE, name, arity)` selector and supplies the object as an implicit first argument. It does not create a bound callable.

First-class extraction such as `obj.method` is represented semantically as a compact bound-method pair containing receiver identity plus shared method identity/slot information. The current reference evaluator keeps the receiver and method-name family and selects the closed-world slot from callback arity; AOT may resolve that to a receiver pointer plus code/vtable slot. A non-escaping value may be stack/register allocated or optimized away, so the language does not require a heap allocation merely because method-value syntax was used.

The receiver is fixed when the method value is formed. Invocation never dynamically rebinds `self`.


## Proper tail-call runtime

OresVM implements proper tail calls itself instead of relying on GraalVM to infer tail-recursion optimization. A tail-position call is prepared as an internal invocation descriptor after its receiver/callee and arguments have been evaluated. The current activation unwinds, and an iterative trampoline executes the next raw activation. Self-recursion, same-evaluator mutual recursion, routines, instance methods, `static fnc`, same-unit module calls, lambdas, and evaluator-owned first-class Oreslang function values therefore share the same constant-call-stack mechanism. Untyped cross-code-unit imports are the explicit contract barrier described below.

The trampoline performs a scheduler safepoint every 64 tail transfers. This prevents a very long recursive chain from becoming an uncooperative scheduling loophole.

Tail transfer is deliberately blocked when caller-owned cleanup still exists: active `defer`, catch/finally semantics, or live mutex guards. Those calls use ordinary call/return behavior so cleanup ordering and lock lifetime remain correct. Arbitrary Java/host `Invokable` values are also not tail-transferred.

Tail-transferable first-class Oreslang callables carry the evaluator that owns their validated contract. A target owned by another linked code unit is treated as a contract barrier because imports are currently typed as `Unknown` within an individual unit. The caller waits for that imported invocation and performs its own declared return-shape validation. Internal tail calls in the target evaluator still trampoline normally. This avoids weakening runtime contracts while keeping retained heap state O(1); no return-validator chain is accumulated across recursive depth.

This mechanism is part of Oreslang semantics and runs identically inside the JVM/Graal JIT runtime, the Native Image AOT launcher, and the AOT-host/guest-JIT hybrid launcher.

## Truffle thread boundary

`ActorRuntime` owns host dispatcher threads; guest code still receives no ambient thread-creation authority. A dispatcher carrier is marked by the runtime, explicitly enters/leaves the associated `TruffleContext` for each actor batch, and only marked actor carriers are accepted for concurrent context access.

Non-adversarial contexts may therefore execute independent actor turns concurrently. Strict/adversarial contexts currently serialize guest actor turns with a fair context-level lock even though private/shared dispatcher pools remain separate. This preserves the strict one-guest-thread sandbox contract until isolated/private actor execution is backed by per-actor inner/polyglot/native contexts.

The guest `THREAD_CREATE` capability is separate from host/runtime dispatcher scheduling. Denying guest-created threads is never bypassed merely because the runtime owns carrier pools.


## Mutex and shared-memory model

Oreslang has two deliberately different mutex domains:

- `Mutex<T>` is actor/private-domain state. It owns the protected value, uses no JVM lock, is non-reentrant, and is confined to the creating semantic actor/execution domain. Shared actors may migrate between JVM workers without changing that domain.
- `SharedMutex<T>` is a legacy host/runtime same-process writable-memory primitive. It remains non-reentrant and uses acquire/release synchronization, but it is **not actor authority**: creation and every operation fail closed during actor execution, independently of actor-policy capability derivation. Host/runtime compatibility code may still use it. Actor mailboxes reject SharedMutex handles before payload inspection or runtime binding; actor-side shared mutation uses ownership/messages or explicit `rt proxy`.
- The payload and declared type argument of `SharedMutex<T>` must be **SharedSafe**: concrete owned data whose reachable field graph contains no borrows, actor-local `Mutex`, `MutexGuard`, pending `Future`, closure/function values, or unresolved dynamic/generic state. This applies to signatures/fields/aliases as well as `SharedMutex.new(...)`. Until Oreslang has an explicit SharedSafe generic bound, unconstrained `SharedMutex<T>` is rejected conservatively. The type checker recursively validates class fields (including inherited generic substitutions), and the interpreter repeats runtime admission checks as defense in depth. Nested `SharedMutex` values are also rejected for now; recursive publication and lock-order semantics must be explicit before lock-containing-lock state is admitted.
- `MutexGuard<T>` is a lexical linear capability. The runtime releases it on normal scope exit and poisons a shared mutex on abnormal scope exit. Guest code may call `guard.release()` for early release; there is intentionally no `mutex.unlock()`. A released guard can no longer expose its protected value.
- Guard access is deliberately non-escaping. Copy-like fields may be read, mutable fields may be replaced, and methods may be invoked directly when they return `void` or a copy-like value. Move-only nested fields cannot be extracted through a guard, and instance methods are direct-call-only everywhere rather than becoming bound method values. For compound mutation, use `with_lock(|state| -> { ... })`.
- `with_lock` and `recover` require an inline one-argument, `void` lambda. The callback parameter is treated as a lexical exclusive `&mut T`: it may mutate protected state but cannot move or return that state, escape it through a closure, or suspend with `await`.
- `await` while a guard is live and closure capture of a guard are compile-time ownership errors. Guard-bearing results must be bound once with `val`; they cannot be discarded, reassigned, stored in aggregates, passed through arbitrary calls, or hidden inside another mutex.
- Host/runtime callers may use blocking, try-lock, async, and timed `SharedMutex` acquisition. Async waiters remain bounded/cancellable and use direct guard handoff without one helper thread per waiter. These APIs are retained for embedding/runtime compatibility only; they are not an actor synchronization surface. Actor code should use owner-actor messaging/channels or `rt proxy`, whose logical RW leases integrate with the actor/source scheduler.
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

A process-wide `singleton module` is **not** raw shared memory. It is owned by one hidden singleton actor and accessed through its typed mailbox/proxy, so its mutable state is serialized by actor execution and normally requires no mutex. Do not wrap singleton-module state in `SharedMutex<T>` merely because multiple actors can call it. `SharedMutex<T>` is reserved for host/runtime compatibility code outside actor execution.

When the private-arena/`isoactor` runtime is stacked with this work, isolated actors must run without `SHARED_MEMORY` authority. An `isoactor` may receive copied/frozen messages, but it must not receive a `SharedMutex<T>` or any other writable JVM-heap alias; otherwise the language would no longer be able to claim true actor memory isolation.

The current reference runtime uses a one-permit JVM semaphore for `SharedMutex<T>`. Java semaphore release/acquire provides the required memory-ordering edge and, unlike a thread-owned `ReentrantLock`, allows an asynchronously acquired guard to be resumed and released by the actor execution context.

Actor transport is independently hardened from mutex synchronization. Ordinary messages are recursively frozen with cycle detection and hard depth/node/byte budgets (256 levels, 100,000 nodes, 16 MiB estimated frozen size). Read-only shared wrappers are runtime-constructed and revalidated on every boundary. `ActorRef` capabilities may cross only inside their owning `ActorRuntime`; a wrapper cannot be used to smuggle a foreign actor reference into another runtime. Arbitrary host-controlled `Sendable` callbacks are not part of the transport boundary. Runtime-owned capabilities have explicit cases, while ordinary message graphs are recursively frozen/copied and validated.

Compiler-generated/context-aware `BehaviorFactory` values are capture-free for both private and shared actors. This prevents a shared actor from bypassing mailbox/capability semantics by closing over an arbitrary mutable JVM object. `spawnPrivateTrusted(...)`, `spawnSharedTrusted(...)`, and trusted `Supplier` construction are host/supervisor escape hatches only; adversarial policies reject them.

## Async task runtime

Ordinary `async` callables are separate from actor dispatchers. The reference interpreter owns one context-local async scheduler and returns runtime-owned `OresFuture<T>` / language `Future<T>` values immediately. Java `CompletionStage` is host interop only and is normalized one-way into `OresFuture`; it does not define guest continuation scheduling. The current compatibility execution bridge still uses Java virtual threads so blocking host/runtime operations do not consume the bounded private/shared actor worker pools. This is transitional: Oreslang source semantics are future/continuation based, not virtual-thread based.

The design intentionally mirrors the strongest C# async/await practices:

- do not make `async` synonymous with "new OS thread";
- avoid sync-over-async on bounded actor workers;
- propagate cancellation to the underlying task;
- preserve the original exception at `await`;
- keep the execution scheduler out of the source-level future contract;
- separate I/O/task concurrency from explicitly CPU-bound scheduling.

Actor dispatcher carriers must not park on pending future reads. Pending `OresFuture.get()`, positive-timeout `get(...)`, and `join()` host/runtime bridges are rejected before registering a waiter. Settled reads and zero-timeout polling remain available. Guest `await` uses the compiler continuation path where supported; host blocking bridges cannot substitute for that suspension/resumption protocol.

Async callable arguments/results are detached at the evaluator boundary. This is stricter than C#'s shared managed heap and preserves Oreslang's ownership direction: mutable task state is owned by the task instead of becoming an implicit cross-thread alias. Generic async boundaries remain closed until a Send/task-safe generic contract exists.

## HungryActor: explicit dedicated CPU carrier

`HungryActor<M>` is the deliberate exception to the ordinary multiplexed actor rule. It is a runtime primitive for sustained CPU-bound or thread-affine work and owns one dedicated **JNI-attached pthread carrier** from construction until `release()`/termination. It does not create a Java platform thread.

Its invariants are:

- exactly one dedicated native pthread carrier per live HungryActor;
- bounded nonblocking mailbox admission;
- messages are frozen before delivery;
- serial message execution;
- fail-stop behavior on callback failure;
- cooperative CPU-loop cancellation through `schedulerSafepoint()`;
- `release()`/close relinquishes the carrier, with bounded shutdown observation;
- it never consumes a private/shared ActorRuntime dispatcher worker.

A HungryActor is intentionally expensive. It is appropriate when reserving a whole carrier is the requirement—not as the default way to obtain parallelism. Ordinary actors should remain multiplexed, and ordinary `async` should remain task/future based. The current class is a host/compiler runtime primitive; exposing a richer source-level constructor must preserve the same ownership and capability checks rather than becoming a raw guest thread API.

## Actor dispatchers

The host actor runtime follows the same scheduling shape as Akka's event-based dispatcher: many actors share an executor, each actor has its own mailbox, and a scheduled actor drains only a bounded number of messages before yielding back to the executor. The configured throughput bound prevents one hot mailbox from monopolizing a worker.

Oreslang deliberately uses two executors:

- **private dispatcher** — private actors, isolation-copy message transport;
- **shared dispatcher** — shared actors with actor-owned mutable heaps, mailbox/channel mutation, immutable published reads, and optional explicit `Proxy<T>` capabilities. `SyncCell<T>` / `SharedMutex<T>` are legacy host/runtime compatibility primitives, not ambient actor state.

A per-actor atomic scheduling gate ensures only one drain task for that actor is active. The executor may run different turns on different threads; thread identity is never actor identity.

The runtime does not interrupt a carrier thread to stop one actor because that thread belongs to the dispatcher and may subsequently execute unrelated actors. Actor cancellation is observed at compiler-injected scheduler safepoints. Whole-runtime shutdown may interrupt the dispatcher executors.


## Shared actor memory

A SHARED actor is a scheduling/runtime-domain classification, **not permission to mutate process-wide application state**. Its ordinary mutable fields remain actor-owned and mailbox-serialized, exactly one logical writer is active at a time, and the ownership domain follows ActorId rather than carrier-thread identity.

The target allocation model is actor-local heaps/arenas for both private and shared actors. A shared actor may retain zero-copy references to explicitly published immutable data, but an ordinary mutable allocation belongs to one actor domain. Cross-actor application mutation should normally travel through a mailbox/channel to the owning actor.

### Runtime proxy escape hatch

Source-level `rt proxy value` consumes an owned class/dynamic-struct value into `ActorRuntime.Proxy<T>`. The proxy is runtime-owned, quota-accounted, and protected by a fair **logical read/write lease queue**. Lease ownership is semantic/runtime state, never JVM-thread or carrier identity. It exists for object graphs where copying is expensive enough to justify synchronized shared access.

The evaluator keeps the raw target inaccessible to source code and maps operations as follows:

- scalar/immutable field/index read -> read lock;
- field/index replacement -> write lock;
- explicitly read-only receiver method -> read lock;
- all other direct methods -> write lock;
- extracted/bound proxy methods -> rejected;
- async or potentially suspending method -> rejected before executing under the lock;
- nested class/`DynamicStruct` result -> interned child `Proxy<U>` sharing the same fair logical RW-lease domain; the raw nested reference never escapes;
- raw mutable collection/callable/capability result -> rejected until a dedicated synchronized adapter or immutable snapshot boundary exists;
- field/index assignment -> write lock and `void` result, so storing a move-only value cannot manufacture a second raw owner;
- `Proxy<T>.dispose()` -> proxy lifecycle revocation; the handle releases its quota and strong runtime root.

Creating a proxy is also an allocation-provenance boundary. A value created inside a SHARED actor is semantically promoted from that actor's local allocation domain into a runtime-owned proxy domain before the capability can cross actors. The current JVM backend realizes that promotion as a strong `ActorRuntime` root and records the source ActorId/domain. Native/#289 arena lowering must perform a real move/promotion out of the actor-local arena before publishing the proxy; it must never leave a transportable proxy pointing into memory that actor teardown can retire.

The lock domain is independent of native carrier identity, so an actor may migrate between carrier threads without changing proxy correctness. Contended actor/source acquisition registers a cancellable `OresFuture` waiter and yields the scheduler turn instead of parking the reusable carrier. FIFO grants batch readers only ahead of the first queued writer, bounding writer starvation; cancellation detaches waiters, and runtime close fails queued waiters. Repeated nested projections are interned by target identity within one lock domain, avoiding per-read handle/quota growth. Cross-lock-domain nested locking is rejected rather than attempting a global lock-order protocol, and read-to-write upgrade is rejected rather than parking forever. Runtime teardown revokes proxy access immediately and defers strong-root retirement until active logical leases drain, so an uncooperative holder cannot turn `ActorRuntime.close()` into an unbounded lock join.

A proxy may be transported only between SHARED actors in the same `ActorRuntime`, with `ACTOR_SHARED_PROXY` on both sides. PRIVATE/UNTRUSTED actors, data-only freezes, immutable `Shared<T>` wrappers, and ordinary async-task boundaries reject proxy handles.

`SyncCell<T>` and `SharedMutex<T>` remain host/runtime compatibility primitives during migration. They continue to require the separate `SHARED_MEMORY` capability, but **no actor kind receives that authority**, including trusted host-created SHARED actors. Host/supervisor code may still use the compatibility primitives directly. New actor-facing designs should prefer actor ownership/messages, immutable publication, or the explicit proxy capability.

Actor failures remain fail-stop. The actor ref retains the failure cause for diagnostics, queued reservations are drained, and later sends receive an `ActorTerminatedException` rather than silently targeting a dead mailbox.

## Private actor memory confinement

A private actor is assigned an `ActorMemorySlice` when it is created. The slice is keyed by actor identity rather than by dispatcher thread because actor turns may migrate between worker threads.

Private mailbox admission is:

1. reject explicitly shared mutable handles such as `SyncCell<T>`;
2. isolation-copy/freeze the message graph;
3. conservatively estimate its logical Oreslang heap size;
4. reserve those bytes against the destination actor slice and the parent runtime budget;
5. enqueue only after both reservations succeed;
6. release transient mailbox bytes after the mailbox turn completes.

Persistent generated actor state reserves from the same slice. Actor teardown closes the entire slice, so leaked host-side reservation handles cannot keep a dead actor's memory budget alive.

The logical size metric intentionally does not claim to equal JVM object layout. It exists to enforce Oreslang memory-domain policy while actors remain multiplexed on one JVM. A hardened backend may replace the accounting implementation with arena/region allocation or a Graal/native isolate without changing source semantics.

A private actor's memory owner is its **ActorId**, never its carrier thread. Successive mailbox turns may execute on different private-dispatcher workers. Consequently a future FFM/off-heap backend must not make `Arena.ofConfined()` carrier-thread identity part of Oreslang semantics. It should use a cross-thread-capable region whose access is guarded by the actor owner token, or map the private actor to a true Graal/native isolate when physical heap isolation is required.

## Native runtime boundary

Oreslang's preferred actor carrier backend is now a JNI bridge to a bounded pthread pool on Linux and macOS. The library is built from `src/main/c/oresthread.c`; each pthread attaches to the host VM once and then multiplexes many unrelated Oreslang actor turns. Actor identity remains independent of physical carrier identity.

The backend selector is `-Dores.runtime.carriers=auto|native|java`:

- `auto` prefers the native pthread backend on supported Unix hosts and falls back only when the native library cannot be linked;
- `native` fails closed if the JNI runtime cannot be loaded or initialized;
- `java` is an explicit compatibility/debugging backend and must not be treated as the production Oreslang scheduler.

`process.descriptor.actor_carrier_backend` reports the physical backend so tests and supervisors can verify that native execution is actually active.

This does **not** mean the whole runtime is native yet. The current actor mailbox containers, shared-memory synchronization, async virtual-thread bridge, GC timer, and several host-integration data structures still use Java runtime primitives. Those are migration targets behind Oreslang-owned abstractions; Native Image compilation by itself is not considered proof that a primitive is natively implemented. New runtime features should avoid exposing Java concurrency types in language semantics and should prefer the JNI/native substrate where a physical scheduler, clock, thread, or memory primitive is required.

### Strict isolate root admission

The VM prestarts its CONTROL carriers before guest execution. In UNTRUSTED
contexts, ROOT_TASK turns are queued to the calling thread that owns the isolate
JNI scope, preserving the existing one-thread sandbox limit. Pending waits leave
guest execution while the caller waits for queued continuations. Resuming a task
retains its logical scheduler ownership without admitting another guest thread.
A rejected context entry settles the owning task exceptionally rather than
leaving a host waiting on an unresolved Future.
