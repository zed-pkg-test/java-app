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
12. Every actor owns its mutable application state, and one logical turn is the only writer to that state at a time.
13. A shared actor may read explicitly published immutable/runtime-shared data under `ACTOR_SHARE_READONLY`, but its effective policy never retains `SHARED_MEMORY`.
14. `SyncCell` creation/update/close and every `SharedMutex` acquisition/recovery operation are supervisor/runtime-only; writable shared capabilities cannot cross actor mailboxes.
15. Cross-actor mutation is expressed as mailbox/channel commands and is executed by the semantic owner of the mutable state.
16. Actor `self` and move-only actor-owned state cannot escape a mailbox turn as ordinary mutable aliases.
17. Private slices and runtime-owned shared allocations compete for the configured parent memory ceiling.
18. Actor message graphs are cycle-checked and bounded by depth, node count, and logical byte quotas before transport.

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

Oreslang treats actor ownership as the primary synchronization model. A shared actor is not a writer into a common mutable heap: it owns its mutable state exactly like every other actor, executes at most one logical turn at a time, and communicates mutations through mailbox/channel commands.

The runtime has four distinct concepts:

- `Mutex<T>` is actor/execution-domain-local state. It does not make data cross-actor shared; it is confined to the semantic execution domain that created it.
- `Shared<T>` is deeply immutable runtime-owned data. A normal/shared actor may read it when its effective policy grants `ACTOR_SHARE_READONLY`. Isolated/private and untrusted actors do not directly dereference it.
- `SyncCell<T>` is a supervisor/runtime-owned synchronized cell. Shared actors may call its read-only `snapshot()`/`read(...)` surfaces, but actor execution cannot create, update, or close a cell.
- `SharedMutex<T>` remains a host/supervisor same-process synchronization primitive. Actor execution cannot create it, acquire it, recover it, use one of its guards, or receive it through an actor mailbox.

The capability split is intentional. `ACTOR_SHARE_READONLY` grants a shared actor the ability to observe approved immutable/shared state. `SHARED_MEMORY` is mutable shared-memory authority and is stripped from every actor's effective policy, including normal/shared actors. Root/supervisor code may retain `SHARED_MEMORY` for runtime implementation and embedding needs.

This gives actor code the stronger invariant:

> Mutable application state has one semantic actor owner. Other actors may observe approved immutable snapshots, but they request mutations by sending messages to the owner.

For example, instead of sharing a writable cache object between actors, use an owner actor:

```text
Reader A ──read immutable snapshot──┐
                                   ▼
                              snapshot v42
                                   ▲
Reader B ──read immutable snapshot──┘

Writer A ── Update(key, value) ──► CacheOwner actor
Writer B ── Delete(key)        ──► CacheOwner actor
                                      │
                                      └── mutates CacheOwner's local state
```

The host/runtime may still use `SyncCell<T>` or `SharedMutex<T>` for infrastructure that is outside actor application semantics. Those primitives remain bounded, ownership-checked runtime facilities, but they are not an escape hatch from the actor single-writer model.

Actor transport enforces the distinction defensively. `SharedMutex<T>` is rejected before mailbox admission. Mutable actor-local references never become sendable merely because they are reachable in the same JVM. Immutable/frozen transport is copied or wrapped according to the target actor/isolate rules.

A process-wide `singleton module` follows the same ownership direction: its mutable state is owned by the hidden singleton actor and callers interact through its serialized proxy/mailbox rather than directly mutating shared storage.

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
- **shared dispatcher** — shared actors, immutable sharing plus explicit `SyncCell<T>` shared state.

A per-actor atomic scheduling gate ensures only one drain task for that actor is active. The executor may run different turns on different threads; thread identity is never actor identity.

The runtime does not interrupt a carrier thread to stop one actor because that thread belongs to the dispatcher and may subsequently execute unrelated actors. Actor cancellation is observed at compiler-injected scheduler safepoints. Whole-runtime shutdown may interrupt the dispatcher executors.


## Shared actor memory

A normal/shared actor has exclusive write authority over its own mutable state. The actor may migrate between physical carriers, but actor identity—not thread identity—defines the ownership domain, and at most one logical turn for that actor executes at once.

The intended multi-heap lowering is:

- ordinary actor fields, objects, collections, closure environments, and persistent continuation state belong to the actor's local allocation domain;
- short-lived non-escaping values may use a turn-local nursery/region;
- explicitly published immutable data may live in a runtime-shared read-only region;
- writable runtime-shared primitives remain supervisor/runtime-only.

The current JVM reference backend already enforces the semantic authority boundary even where arbitrary Java objects are not yet physically allocated from a distinct actor arena. Automatic placement of ordinary Ores values into actor-local heaps is tracked separately; source semantics must not depend on whether the backend uses JVM accounting, pooled native arenas, slabs, or a stronger isolate.

A shared actor can therefore read `Shared<T>` or read-only `SyncCell<T>` snapshots when policy allows it, but it cannot call `SyncCell.update`, create/close a cell, acquire a `SharedMutex<T>`, or smuggle a writable shared handle through a mailbox. To change state owned elsewhere, it sends a mailbox/channel command to that owner.

This separation is useful for GC as well as race prevention: actor-owned regions can eventually be traced/retired independently, while immutable shared snapshots may outlive any one actor without granting additional writers.

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
