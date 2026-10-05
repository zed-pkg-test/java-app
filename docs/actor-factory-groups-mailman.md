# Persistent actor classes, generated factories, groups, and mailmen

Status: hardened design contract for the current actor-class/runtime model.

This document distinguishes three concepts that must not collapse into one
another:

1. **persistent actor classes** — long-lived typed mailbox objects;
2. **actor fnc/routine callables** — one-shot scheduled actor-domain jobs;
3. **runtime actor factories** — compiler/host construction descriptors used by
   supervisors, groups, and generated catalogs.

A source `actor fnc` is **not** the persistent actor-class factory ABI.

## 1. Persistent source actors are actor classes

A persistent actor is a class with an `ActorKind`:

- `actor` / `shared actor` / `extends Actor` -> SHARED
- `isoactor` / `extends IsoActor` -> PRIVATE
- `untrusted actor` / `extends UntrustedActor` -> UNTRUSTED

Every persistent actor has exactly one public source-level mailbox entrypoint:
`receive(message): void`. All other actor instance methods are private helpers.

```ores
define class Counter extends Actor<int, void, String> as
  let int value = 0;

  constructor(initial: int) {
    self.value = initial;
  }

  private normalized(delta: int): int {
    return delta;
  }

  pub receive(delta: int): void {
    self.value = self.value + self.normalized(delta);
    return;
  }
end
```

The runtime owns the persistent receive loop. One admitted mailbox message
acquires the actor execution lease and invokes `receive` once. User code does
not write a permanent `while receive` loop and does not expose several public
methods for the runtime to dispatch.

`Actor`, `IsoActor`, and `UntrustedActor` accept either no contract type
arguments or exactly `<Message, Reply, Error>`. If supplied, `Message`
must exactly match the one `receive` parameter. Reply/Error are schemas for
explicit response capabilities and failure contracts; they are not implicit
method-return RPC channels.

## 2. ActorRef is a typed mailbox capability

External code receives `ActorRef<Counter>`:

```ores
val counter = spawn Counter(40);
counter.send(2);
```

The source projection is deliberately small:

```text
ActorRef<Counter>.send(int): void
Counter.receive(int): void       // runtime invocation only
```

`ActorRef.receive`, a public mailbox object, or arbitrary
`ActorRef.method(...)` RPC dispatch are not part of the persistent actor
source API. A caller needing a reply sends a message containing an explicit,
bounded response capability/schema. That makes response ownership,
cancellation, timeout, and quota behavior visible rather than hiding it in
method-call sugar.

The reference may additionally expose policy-approved identity/lifecycle
operations such as `id` or `is_alive`; these are control/capability
operations, not actor application behavior.

## 3. Inheritance, interfaces, and hot-load ABI

Actor inheritance must preserve the execution/isolation domain. A child actor
must still provide the single concrete `receive(message): void` entrypoint
required by its actor class contract.

An actor may implement an interface when that interface is compatible with its
public source shape—for example, an interface declaring the same
`receive(Message): void` signature. Implementing an interface does not
synthesize `ActorRef<Interface>.method(...)` RPC behavior.

For hot loading, implementation code may remain opaque as long as the new
generation satisfies the required actor ABI:

- actor kind and isolate placement;
- constructor contract;
- `Message` schema and `receive(Message): void` signature;
- optional explicit Reply/Error schemas;
- mailbox/wire schema identifiers;
- lifecycle hooks and capability manifest;
- resource/sandbox policy.

The runtime loads the code generation behind that known ABI and never depends
on private implementation layout.

## 4. Boundary rules

The constructor boundary and mailbox-message boundary are checked statically
and again at runtime transport admission.

The compiler/runtime must reject:

- more or fewer than one public `receive` on a persistent actor;
- method-level generics on `receive`;
- mutable (`mut`) mailbox parameters;
- non-`void` `receive` returns;
- borrows/references crossing the mailbox;
- `Future`, mutex guards, actor spawn tickets, or mutable host objects crossing
  the boundary;
- actor instances transported by value rather than as capabilities;
- writable external shared state such as `SharedMutex<T>`;
- public actor fields;
- actor constructors that are public, generic, static, or suspending;
- raw imported/effect-unknown helper calls from actor code.

`ActorRef<ConcreteActor>` is an explicit mailbox capability. A SHARED actor
may receive an explicit `RwLock<T>` read capability when `T` is
shared-safe, but actor code cannot acquire a write guard. PRIVATE and UNTRUSTED
actors cannot use shared external memory.

Actor restrictions propagate transitively through ordinary local functions and
methods called by actor code. A helper does not become an effect escape hatch
merely because it lacks an `actor` keyword.

## 5. One-shot actor callables are separate

`actor fnc` / `actor routine` remain one-shot scheduled jobs in an actor
execution domain.

They are invoked with `spawn`, not by ordinary call syntax, and produce an
`ActorSpawn<R>` control handle:

```text
ActorSpawn<R>
  id
  ready : Future<ActorRef<?>>
  done  : Future<bool>
  result: Future<R>     // only when R != void
```

This is intentionally different from a persistent actor class. Do not use
`actor fnc` as a hidden constructor for a long-lived class protocol.

## 6. Runtime generated factories

The Java/runtime `BehaviorFactory` abstraction is a kernel construction
mechanism. It is not the source `actor fnc` ABI.

For source actor classes, the compiler/linker may generate a construction
descriptor/factory that:

1. reserves the ActorId/domain/group quotas;
2. validates and transports constructor arguments;
3. initializes actor-owned state under the target actor context;
4. installs the actor's one-message `receive` behavior;
5. publishes READY only after initialization succeeds.

The SHARED reference evaluator uses a privileged compiler-facing
`spawnSourceSharedActor` adapter that still feeds the normal mailbox
`Behavior` path. PRIVATE and UNTRUSTED source actor classes require their
isolation-aware OresVM lowering and must not be silently routed through the
shared evaluator.

## 7. ActorFactoryCatalog meaning

`ActorFactoryCatalog` is immutable, generated, and generation-scoped. It is
link metadata, not a self-registering runtime service.

A descriptor key is path-oriented:

```text
workers/worker.ores::Worker
```

For a persistent actor class, descriptor `inputType` denotes the
compiler-known mailbox **Message ABI** accepted by `receive` (or the generated
wire envelope representing that single message contract). It must not be read
as evidence of several source-level public RPC endpoints. `outputType`
describes the group's typed emitted-output contract where applicable; explicit
Reply/Error schemas are separate ABI inputs when present.

The descriptor's ABI digest must cover actor kind, constructor contract,
Message/Reply/Error schemas, relevant capabilities, lifecycle contract, and
generated wire/message tags. Reordering private helpers must not perturb it;
changing the admitted message or capability contract must.

Catalogs are pinned to a code generation. Existing actors retain the generation
lease from which they were constructed.

## 8. ActorGroup and mailman

An ActorGroup owns runtime policy and routing for a set of actors:

```text
ActorGroup<Out>
  actor registry
  bounded inbox policy
  bounded MPSC outbox<ActorMail<Out>>
  one logical serialized ActorMailman<Out>
  supervisor/restart policy
  quotas
```

No scheduler scans/selects across all actor inboxes. A mailbox transition makes
one ActorRef runnable in its execution-domain queue.

The mailman is one logical serialized consumer scheduled on the CONTROL pool,
not a permanently dedicated OS thread and not an actor-domain carrier.

Actors may emit bounded group output:

```text
actor turn -> emit Out -> group outbox -> ActorMailman
```

When a mailman/supervisor routes application input back to an actor, it does so
by enqueueing an admitted message through that actor's mailbox capability.
Response traffic likewise uses explicit message/response capabilities rather
than direct actor-method dispatch.

## 9. Group capabilities

Supervisor/root code may hold `ActorGroupRef<Out>`, which is control-plane
authority.

Trusted actor code may receive only an opaque `ActorGroupHandle<Out>`. The
handle contains authenticated identity/domain/generation capability data and no
pointer to the runtime, scheduler, registry, inbox, outbox, or mailman state.

UNTRUSTED actors never receive a spawn-capable group handle.

Group ownership therefore does not transfer merely because an actor can request
a child spawn in that group.

## 10. Scheduling

All actor kinds preserve:

- one active execution lease per actor;
- bounded carrier pools by actor domain;
- local/global ready queues with work stealing only within the domain;
- no mailbox scanning;
- bounded turn/reduction/fuel budgets;
- `await` as a hard scheduler boundary;
- completion threads enqueueing wakeups only;
- continuation state rooted until the logical turn finishes.

A `receive` turn that suspends remains the same serialized logical mailbox
turn until its continuation completes. Later mailbox messages cannot mutate
that actor until the suspended turn resumes and finishes.

## 11. Group configuration

`.ores-actors.toml` is deployment authority. Limits are ceilings, not hints.

Current runtime policy uses a canonical `factory = "<path>::<symbol>"` key
when a supervisor needs to recreate actors automatically. For persistent source
actors, that key names a **generated actor-class constructor descriptor**, not a
source `actor fnc` return value.

Example:

```toml
version = 1

[[group]]
name = "workers"
kind = "shared"
min_actors = 8
max_actors = 128
factory = "workers/worker.ores::Worker"
inbox_capacity = 1024
outbox_capacity = 4096
restart_strategy = "one_for_one"
max_restarts = 5
restart_window = "10s"

[[group_template]]
name = "tenant-workers"
kind = "shared"
min_actors = 0
max_actors = 32
max_instances = 1024
factory = "workers/tenant_worker.ores::TenantWorker"
inbox_capacity = 512
outbox_capacity = 2048
restart_strategy = "one_for_one"
max_restarts = 3
restart_window = "5s"
```

A positive `min_actors` requires a factory key because the supervisor needs a
concrete generation-pinned actor constructor to restore the floor.

Dynamic overrides are narrowing-only. They may not widen capacities,
capabilities, restart intensity, or change actor execution domain.

## 12. Supervision

Use OTP-style restart vocabulary:

- `one_for_one`
- `one_for_all`
- `rest_for_one`

Dynamic groups currently use `one_for_one` only.

Restart intensity is bounded by `max_restarts` within `restart_window`.
Exceeding the window escalates rather than spinning forever.

Restart policy remains distinct:

- `permanent`
- `transient`
- `temporary`

An unrecovered actor failure goes to supervision. It is not ordinary outgoing
mail.

## 13. Tree shaking and hot reload

Actor declarations do not self-register or run at import time.

Build roots include:

1. ordinary executable roots such as `main`;
2. actor classes referenced by static group factory keys;
3. actor classes referenced by dynamic group templates;
4. deliberately exported/hot-load entry actors;
5. ordinary statically reachable actor classes/callables.

The linker emits only required generation-scoped descriptors. No reflection,
classpath scan, static initializer, or mutable global registry is required.

A new hot-load generation gets a new immutable catalog. Existing actors remain
pinned to their birth generation until drained/terminated.

## 14. Enforcement checklist

Compiler/type system:

- actor classes are persistent; actor fnc/routine callables are one-shot;
- every persistent actor has exactly one public `receive(message): void`;
- all other actor instance methods are private helpers;
- actor inheritance preserves execution/isolation kind;
- `Actor`/`IsoActor`/`UntrustedActor` accept zero or exactly three
  contract arguments `<Message, Reply, Error>`;
- when present, `Message` exactly matches the `receive` parameter type;
- `ActorRef<ConcreteActor>.send(message)` is the persistent actor behavior
  surface; direct `receive`/mailbox access and arbitrary RPC method calls are
  rejected;
- constructor and message boundary sendability are checked;
- actor input cannot be upgraded into mutable helper authority;
- actor effect restrictions propagate through helper call graphs;
- imported effect-unknown calls fail closed in actor context.

Runtime:

- one mailbox and one execution lease per actor;
- one admitted mailbox message invokes one `receive` turn;
- suspension preserves the same logical serialized turn;
- constructor/message transport validates ownership, capability affinity,
  quotas, and isolation before mailbox visibility;
- response authority is explicit and runtime-controlled rather than synthesized
  from arbitrary public method returns;
- queue/memory/fuel/lifetime limits are enforced before admission;
- PRIVATE/UNTRUSTED actor memory is reclaimable at actor termination;
- group/generation/quota reservations release exactly once;
- hot-reload code generations remain pinned for actor lifetime.
