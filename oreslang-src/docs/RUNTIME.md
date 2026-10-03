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
10. A `singleton module` has exactly one actor-owned state cell per OS process by language contract, not per actor or ordinary Graal context.
11. A spawned Graal isolate has its own heap; the current adversarial profile therefore refuses `PROCESS_SINGLETON` until a trusted supervisor/process coordinator is installed.
12. Importers/callers receive only a proxy/handle; mutable singleton state never leaves the singleton actor.
13. Singleton call arguments and results pass the normal sendability/freezing boundary, and singleton calls are request/reply operations.
14. Process-singleton mailboxes, registry cardinality, request wall time, message graph depth/node count/size, and individual singleton field values are bounded.
15. Cross-singleton wait cycles are rejected before enqueue can create a mailbox deadlock.
16. Singleton code replacement requires `HOT_CODE_LOAD`; managed hot-reload generations are monotonic and stale generations cannot roll behavior back.
17. Process-owned singleton code cannot fall back into caller-local modules/imports or ambient `process`/`stdio` capabilities; such dependencies fail static checking until an explicit process-safe effect model exists.
18. Ordinary module/file state is lifetime-scoped to the executing actor cell, or to the Graal context for non-actor/main execution.

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


## Process-wide singleton modules

`define singleton module name as` has an OS-process singleton contract. In the non-isolated JVM/runtime profile, the current local backend uses static process-lifetime state independent of any one `OresContext`. A Graal ISOLATED/UNTRUSTED engine has a distinct heap and therefore cannot use isolate-local statics to satisfy that contract; the current adversarial profile rejects `PROCESS_SINGLETON` until an embedding supplies a trusted supervisor coordinator. The first access creates one virtual-thread actor with a serial mailbox and initializes the module bindings on that actor. Later ordinary Graal contexts sharing the same host runtime heap resolve the same canonical module key to the same actor identity. Spawned isolated Graal heaps do not use this local registry; they are rejected until a trusted supervisor coordinator is installed. The key includes the defining source code-unit identity, its explicit namespace (or a default-namespace marker), and the module name. This prevents unrelated files/tenants from aliasing one another even if they choose the same namespace and module name, while hot-reload generations of the same code unit retain the same singleton identity.

Public functions are exposed through a typed module proxy. A call such as `await config.read()` enqueues a request to the singleton actor and waits for its reply. Cross-singleton calls must be immediately awaited; an un-awaited transport future may not escape into arbitrary code. Calls made by singleton code to another function in the same singleton module are direct calls against the already-owned state environment, including calls reached through singleton-private helper classes, methods, static functions, and iterators. This avoids self-mailbox re-entry.

The runtime tracks outstanding singleton-to-singleton waits. If adding an edge would close a wait cycle, the call fails before enqueue instead of allowing two serial actors to deadlock. Queue admission is bounded by both a process ceiling and the caller's `IsolatePolicy.maxMailboxMessages`. The caller's `maxWallTime` includes queueing time; expired queued work is skipped, and compiler/runtime safepoints enforce the same budget during singleton execution.

Singleton fields are actor-owned. Process-lifetime fields require explicit, process-stable storage types and context-free initializers. Public scalar/container fields remain forbidden. A `pub val` or `pub const` class instance is permitted only as an exported singleton-object capability: callers receive a typed proxy, never the raw object, and public method calls are serialized through the singleton mailbox. Type aliases and context-dependent initialization (capability access, function calls, class construction, awaiting work, mutation, or closures) are rejected for singleton state. Candidate values are validated before commit, and the aggregate singleton state graph is revalidated after mutation. If a mutation would introduce a cycle, non-sendable value, excessive graph depth/node count/size, or another storage violation, the mutation is rolled back.

### Init lifecycle

Field/binding initializers run before lifecycle init. A file/root or ordinary-module `init routine() => void` belongs to the executing actor when invoked from actor code and runs once for that actor-local scope. The state is stored on the actor cell and dies with it. Main/non-actor execution instead uses Graal-context-lifetime module state, so repeated execution inside one context does not rerun init. A singleton-module init belongs to the process singleton cell and runs exactly once when that cell is created. It is not rerun on hot reload.

There is intentionally no ambient "process init" hook at file scope. Process-wide initialization must be owned by a singleton module so state, authority, serialization, and hot-reload behavior have one explicit lifecycle owner. Singleton init is deterministic/context-free and cannot depend on the first caller's capabilities or actor-local state.

The public singleton transport surface is deliberately narrower than ordinary Oreslang APIs until explicit `Send` constraints exist: public singleton functions are non-generic, non-`async`, cannot accept `mut` or structural parameters, and may use only statically sendable scalar/container/Option values. Borrows, class instances, functions/closures, unresolved/generic types, and other actor-local values are rejected. Runtime freezing remains the second line of defense.

Hot reload preserves the same singleton actor and state when a new generation keeps the same declared field schema, so new function code can operate on existing process state. Singleton-owned object instances retain their state storage, but method dispatch is resolved against the currently authorized class generation; old class ASTs cannot pin stale behavior. A generation that changes the singleton field schema fails closed with a migration-required error; state is never silently reinterpreted. Changing singleton function code against live state requires the caller context to hold `HOT_CODE_LOAD`. `HotReloadManager` stamps contexts with a process-global monotonic generation number; an older generation cannot later roll the singleton's active behavior backward, and an unversioned context cannot replace code once managed generations are active. An explicit state-migration hook is intentionally a separate future language feature.


## Actor message freezing limits

The host actor boundary is defensive against malformed or adversarial object graphs. `ActorRuntime.freeze` rejects cycles and unknown mutable host objects, re-freezes public `Shared<T>` wrappers, and bounds traversal depth, node count, and approximate frozen size. Lists, sets, maps, and arrays become unmodifiable frozen copies. Duplicate set elements or map keys created by freezing are rejected rather than silently collapsing data.

These are runtime defense-in-depth checks; source-level singleton APIs are also statically restricted to sendable types. Production cross-isolate transports should serialize the frozen representation rather than share writable Java object references.


## Ownership-first garbage collection and lifetimes

Oreslang does not use tracing GC as its primary memory-safety model. Source
semantics use affine ownership, inferred borrows, and deterministic scope
retirement. JVM/Graal GC remains the final reclamation mechanism for unreachable
host objects, reflection/interop artifacts, and other runtime implementation
objects.

Lifetimes are inferred first. The compiler reasons about these domains without
requiring Rust-style lifetime punctuation:

1. expression temporary;
2. lexical binding;
3. actor-owned state;
4. context/code-generation state;
5. process-singleton state.

A borrow may never outlive its owner. Explicit named lifetime syntax should be
added only if generic borrow-return APIs cannot be safely expressed/inferred.

Runtime cleanup has two layers:

- deterministic scope retirement closes runtime-owned `AutoCloseable` values;
- safe points scavenge runtime caches implementing `GcRuntime.Scavengeable`.

Periodic scavenging never forces process-global host GC. That avoids allowing
one actor/tenant to impose global collection pressure on unrelated actors.

### Explicit cleanup

`process.gc()` requires `GC_CONTROL`, scavenges context-owned runtime caches,
signals actors to self-scavenge at their next safe point/turn, and may make a
rate-limited host-GC request. It never directly walks another actor's mutable
roots.

`actor.gc()` requires an active actor turn plus `GC_CONTROL`, scavenges only
that actor's runtime-owned caches, and may make the same rate-limited host-GC
request.

Both are optimization/cleanup controls, never correctness primitives.

### Private and shared actors

The runtime distinguishes `PRIVATE` and `SHARED` actors.

- Private actors reject `Shared<T>` transport. Send ordinary values so the
  mailbox boundary isolation-copies/freezes them.
- Shared actors still exclusively own ordinary mutable fields. They may accept
  explicitly deeply immutable `Shared<T>` only when
  `ACTOR_SHARE_READONLY` is granted.
- Both execute one actor turn at a time and retain separate actor-local roots.

This takes the mailbox-serialization lesson from Akka/ractor/actix while adding
Oreslang's compile-time ownership guarantee against cross-actor mutable aliasing.
