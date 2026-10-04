# Actor factories, shared-actor groups, and mailmen

Status: design contract stacked on the resumable actor-event-loop work.

This document reconciles the actor callable/spawn work with the serialized shared-actor event-loop model.

## 1. Core distinction

Oreslang has persistent actors. An `actor fnc` / `actor routine` is the **factory/launch entrypoint** for one persistent actor; it is not an ordinary function and it is not itself a one-shot inbox turn.

A spawn target MUST be declared with an actor execution-domain modifier:

- `actor fnc` / `actor routine` -> SHARED domain
- `isoactor fnc` / `isoactor routine` -> PRIVATE domain
- `untrusted actor fnc` / `untrusted actor routine` -> UNTRUSTED domain

The callable's declared return type MUST satisfy the compiler-owned protocol:

```ores
ActorBehavior<In, Out>
```

A concrete actor class may satisfy this protocol structurally.

The behavior has exactly one public inbox ingress:

```ores
receive_message(In message): void
```

For shared actors, every other actor method and every mutable field is private to the behavior. The runtime never dispatches arbitrary public methods.

`Out` is the actor's typed outgoing-mail contract. The actor may emit zero or more `Out` values during a turn; these are appended to its ActorGroup outbox and are not returned from `receive_message`.

A no-output actor uses `ActorBehavior<In, void>`.

## 2. Example

```ores
define struct Increment
  int amount;
end

define struct CounterChanged
  int value;
end

shared actor Counter
  let int value;

  pub receive_message(Increment msg): void {
    self.value = self.value + msg.amount;
    emit CounterChanged(self.value);
    return;
  }
end

pub actor fnc counter(int initial): ActorBehavior<Increment, CounterChanged> {
  return new Counter(initial);
}

pub routine main(): void {
  val counters = actor_groups.define<CounterChanged>({
    min_actors: 0,
    max_actors: 64,
    inbox_capacity: 1024,
    outbox_capacity: 4096
  });

  val pending = spawn counter(10) with {
    group: counters
  };

  // identity is synchronous after reservation/admission
  stdio.println(pending.id);

  // READY means the behavior exists and its inbox endpoint is usable
  val counter_ref = await pending.ready;

  counter_ref.send(Increment(5));
  return;
}
```

The actor factory runs during STARTING with the child actor context installed. It may allocate/initialize actor-owned state, but it may not leak actor-owned mutable state, capture caller-owned mutable aliases, block a carrier, or perform an incomplete `await`.

## 3. Spawn contract

```text
RESERVED -> STARTING -> READY -> RUNNING -> TERMINATED
                  \-> FAILED_TO_START
```

`spawn actor_factory(args...) with { group: group_capability }` performs only bounded synchronous launch work. The `group` entry is required and non-null:

1. validate the spawn target is an actor factory;
2. resolve and authenticate the supplied ActorGroup capability;
3. require the group's execution domain to match the actor factory domain;
4. validate/copy/freeze/transfer launch arguments for the target actor domain;
5. reserve group/process/domain quotas and ActorId transactionally;
6. create local control futures;
7. enqueue actor initialization;
8. return the launch ticket.

The actor factory executes later on the target actor dispatcher. READY is published only after:

- factory execution completes successfully;
- the returned value satisfies `ActorBehavior<In, Out>`;
- the behavior is rooted as actor-owned state;
- the single `receive_message(In): void` ingress is installed;
- group/mailman routing is attached.

Startup failure fails `ready` and `done`, tears down the actor, releases its group/generation/quota leases, and never publishes a usable ActorRef.

## 4. ActorSpawn and ActorRef

Persistent actors do not have a function-result future.

The source contract is:

```ores
ActorSpawn<In, Out> {
  id: ActorId;
  ready: Future<ActorRef<In>>;
  done: Future<ActorExit>;
}
```

`await spawn actor_factory(...)` is shorthand for awaiting `ready` and therefore returns `ActorRef<In>`.

```ores
ActorRef<In> {
  id: ActorId;
  send(In message): void;
  is_alive(): bool; // unavailable where sandbox policy forbids lifecycle inspection
}
```

`ActorSpawn` remains local control state and is not Sendable/shared-safe.

The one-shot `ActorSpawn<T>.result` semantics introduced by the earlier spawn draft are intentionally not the persistent-actor ABI. If Oreslang retains one-shot actor tasks, they must use a distinct task abstraction rather than overloading the persistent actor factory contract.

## 5. Shared actor mutation and nlex/ownership

"Shared actor" means shared-address-space scheduling/capability domain, not unrestricted shared mutation.

A shared actor turn may mutate only:

1. actor-owned state rooted in `self`;
2. fresh turn-local state;
3. explicit compiler/runtime synchronization or capability objects.

It may not mutate arbitrary caller/global/external mutable state.

Actor-owned mutable state is non-escaping. The ownership checker must reject:

- returning a mutable actor-state alias;
- sending a borrow/reference into a mailbox;
- storing actor-owned mutable state in global/singleton/external state;
- capturing actor-owned mutable state in an escaping closure;
- retaining a turn-scoped borrow across `await`.

The existing `nlex` model should be reused for actor factories/actor-owned lexical state where applicable, but actor isolation is a stronger semantic rule and must not depend solely on surface `nlex` spelling.

## 6. Event loop and scheduling

No scheduler or mailman dynamically selects across every actor inbox.

Incoming path:

```text
send(message)
  -> actor.inbox.push(message)
  -> CAS IDLE -> QUEUED
  -> ActorGroup/domain runnable queue.push(actor_ref)
```

A carrier obtains an actor execution lease and runs a bounded quantum. At most one carrier executes guest code for one actor at a time.

`await`, timers, and I/O completion use the existing continuation path. Completion threads enqueue wakeups only; they never execute actor code. A suspended logical inbox turn remains serialized until its continuation completes.

Generator `yield` is unrelated and must never become an actor scheduling primitive.

## 7. ActorGroup and mailman

Every persistent actor belongs to exactly one `ActorGroup<Out>`. Source-level `spawn` MUST supply a non-null group capability explicitly; there is no implicit fallback group at spawn time.

Conceptually:

```text
ActorGroup<Out>
  actor registry
  runnable scheduling state
  one bounded inbox per actor
  one bounded MPSC outbox<ActorMail<Out>>
  exactly one logical ActorMailman<Out>
  supervisor/lifecycle policy
  quotas
```

Actors never expose one outbox per actor to a giant `select`. They append outgoing mail to the group's bounded MPSC outbox.

```text
Actor A --\
Actor B ----> group.outbox ---> logical mailman
Actor C --/
```

The mailman is one logical serialized consumer, not one permanently dedicated OS thread. It runs on the Oreslang VM CONTROL scheduler (shared with supervisors/root control work, never an actor-domain pool) and may migrate across control-plane carriers between quanta.

A future implementation may partition mailman work by an explicit routing key, but parallel routing must be opt-in because it weakens total ordering.

## 8. Outgoing envelope

The group outbox stores envelopes:

```ores
ActorMail<Out> {
  actor: ActorId;
  group: ActorGroupId;
  correlation_id: Option<CorrelationId>;
  message: Out;
}
```

Trace/span id, request id, deadline, sequence, or other runtime metadata may be attached without becoming guest-mutable actor state.

The mailman/supervisor routes with ordinary Oreslang pattern matching:

```ores
match mail.message {
  CounterChanged(value) -> {
    // route/persist/reply/etc.
  }
  _ -> {
    // group policy
  }
}
```

The actor itself does not receive ambient authority merely because the mailman can perform a side effect.

## 9. Supervisor vs mailman

Keep the concepts distinct even if the first implementation shares machinery.

Mailman:
- consumes ordinary actor output;
- routes replies/events;
- preserves configured ordering.

Supervisor:
- owns lifecycle;
- restart/escalation policy;
- generation leases;
- actor/group shutdown;
- startup/failure handling.

An unrecovered actor `raise`/`panic` goes to supervision policy, not through ordinary outgoing mail.

## 10. Compatibility with the current PR stack

This design is intentionally aligned with the current actor work:

- the single-carrier execution lease and three isolated SHARED/PRIVATE/UNTRUSTED dispatcher domains remain unchanged;
- resumable actor continuations, timers, and next-tick wakeups remain unchanged;
- generator `yield` remains separate from actor suspension;
- actor generation leases must cover factory initialization plus the full actor lifetime;
- global/singleton refs and shared-mutable capabilities remain forbidden from untrusted actors;
- actor-owned state follows ownership/borrow escape rules;
- actor HTTP/native transport ownership remains capability-based and does not turn shared actors into arbitrary socket owners.

The main semantic correction is to the earlier one-shot spawn draft: a persistent actor factory returns an `ActorBehavior<In, Out>`, and `spawn` returns a launch/control ticket rather than treating the actor callable's application return value as the actor's result.

## 11. Compiler/runtime enforcement checklist

Compiler:

- reject direct calls to actor factories;
- reject `spawn` of non-actor callables;
- require actor factory return type to satisfy `ActorBehavior<In, Out>`;
- require exactly one public `receive_message(In): void` on a concrete behavior;
- reject additional public shared-actor methods/fields;
- derive `ActorSpawn<In, Out>` from the factory return type;
- type `await spawn` as `ActorRef<In>`;
- type `ActorRef.send` against `In`;
- type `emit` against `Out`;
- reject actor-owned mutable alias escape and forbidden external mutation;
- preserve actor-domain/capability checks transitively.

Runtime:

- execute factories only after ActorId reservation on the target dispatcher;
- publish READY only after successful behavior construction/validation;
- root returned behavior exclusively in the actor cell;
- enqueue one actor ref per runnable transition, never scan mailboxes;
- enforce one active execution lease per actor;
- bound mailbox, continuation, timer, and group-outbox admission;
- route emitted mail through the actor's group outbox;
- reclaim group/actor/generation/resources exactly once on failure/termination.


## 12. Startup configuration: `.ores-actors.toml`

Actor-group policy is loaded and validated before `main` is admitted. The runtime must never discover group ceilings lazily after user actors are already running.

Recommended deployment file:

```toml
version = 1

[process]
max_groups = 4096
max_dynamic_groups = 2048
max_actors = 16384

[dynamic_defaults]
max_actors = 64
inbox_capacity = 1024
outbox_capacity = 4096
restart_strategy = "one_for_one"
max_restarts = 3
restart_window = "5s"

[[group]]
name = "default"
kind = "shared"
min_actors = 0
max_actors = 4096
inbox_capacity = 2048
outbox_capacity = 8192
restart_strategy = "one_for_one"
max_restarts = 10
restart_window = "5s"

[[group]]
name = "workers"
kind = "shared"
min_actors = 8
max_actors = 128
factory = "workers/worker.ores::worker"
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
factory = "workers/tenant_worker.ores::worker"
inbox_capacity = 512
outbox_capacity = 2048
restart_strategy = "one_for_one"
max_restarts = 3
restart_window = "5s"
```

The file is host/deployment authority, not actor-owned mutable state. It should be validated before guest execution using the same generated-contract discipline as other Ores configuration files.

All configured limits are ceilings. Source code may request a stricter/lower value but may not widen a deployment ceiling.

### Why a factory is required for a positive minimum

A numeric `min_actors` does not identify what the supervisor should create. Therefore:

- `min_actors == 0` requires no factory;
- `min_actors > 0` requires a concrete actor factory/template;
- the supervisor creates enough instances of that factory to reach the floor before the group is declared READY;
- after a crash or normal exit, the supervisor reconciles back to the floor unless the whole group is draining/stopping.

This is the Oreslang equivalent of OTP supervision child specs, with an explicit pool floor layered on top.

## 13. Static groups vs dynamic groups

There are two lifecycle classes.

### Static startup group

A `[[group]]` entry is part of application desired state.

- created before `main`;
- recreated from configuration when the application/supervisor generation restarts;
- may have `min_actors > 0`;
- may use `one_for_one`, `one_for_all`, or `rest_for_one` where ordering is meaningful;
- receives a stable logical group name, but every concrete runtime incarnation still has a generation-safe `ActorGroupId`.

### Dynamic group

A dynamic group is created at runtime from a configured `group_template`.

Conceptually:

```ores
val group = actor_groups.create(
  "tenant:acme",
  template: "tenant-workers",
  max_actors: 16
);
```

Creation is a supervisor/root capability operation. Ordinary actor code does not gain ambient permission to create arbitrary groups; it must either receive an explicit group-management capability or ask its supervisor by message. Untrusted actors can never create groups.

Dynamic overrides are **narrowing only**:

```text
requested.max_actors <= template.max_actors
requested.inbox_capacity <= template.inbox_capacity
requested.outbox_capacity <= template.outbox_capacity
requested.min_actors <= requested.max_actors
```

A runtime request may not change the template's actor execution domain, increase capabilities, increase restart intensity, or escape the process-wide ceilings.

Dynamic groups are ephemeral by default. If their supervisor/application generation disappears, they disappear too. Oreslang should not silently pretend to persist runtime-created desired state. A future durable-group service can explicitly persist and replay group specifications.

This deliberately follows OTP's distinction between static child specifications and dynamically added children: dynamic children can be added to a supervisor, but dynamic additions are not magically reconstructed from the supervisor's static initialization spec after recreation.

## 14. Group capacity and admission

`max_actors` is a hard admission limit, not a target and not a scheduler thread count.

A spawn into a full group fails synchronously during bounded spawn admission:

```text
GroupCapacityExceeded {
  group,
  current_actors,
  max_actors
}
```

The runtime does not queue an unbounded list of pending spawns behind a full group. The caller can retry, back off, choose another group, or let a higher-level router decide.

Capacity checks are hierarchical and transactional:

```text
process ceiling
  -> runtime ceiling
    -> execution-domain ceiling
      -> group ceiling
        -> actor reservation
```

If any reservation fails, every earlier provisional reservation is rolled back.

`min_actors` is maintained by the group supervisor; `max_actors` is enforced by admission. Neither value changes the carrier pool directly. Thousands of actors may still be multiplexed over the existing bounded carrier pools.

## 15. OTP-style supervision policy

Oreslang should borrow the stable OTP vocabulary rather than inventing different names:

```text
one_for_one
one_for_all
rest_for_one
```

Default: `one_for_one`.

- `one_for_one`: restart only the failed actor.
- `one_for_all`: terminate/restart the group's supervised cohort.
- `rest_for_one`: restart the failed actor plus actors after it in deterministic startup order.

For dynamic groups, v1 permits only `one_for_one`. Dynamically created actors generally have no semantically meaningful total startup order, and allowing `rest_for_one` would create surprising coupling.

Each group also has a restart-intensity window:

```text
max_restarts = N
restart_window = duration
```

If more than `N` supervised restarts occur inside the window, the group supervisor stops trying locally and escalates to its parent supervisor. This prevents crash loops from consuming the scheduler indefinitely.

Actor restart policy is separate from actor count policy:

```text
restart = permanent | transient | temporary
```

- `permanent`: restart after any termination while the group is running.
- `transient`: restart only after abnormal termination.
- `temporary`: never restart.

A factory used to maintain `min_actors` is effectively reconciled independently of an individual actor's restart marker: if the live count falls below the configured floor, the supervisor may create a replacement instance to restore desired capacity.

## 16. Registry and identity

Names are lookup conveniences; IDs are authority.

```ores
ActorGroupId {
  process_generation;
  group_generation;
  nonce;
}
```

The runtime maintains a process-local group registry:

```text
logical name -> current ActorGroupId
ActorGroupId -> live group state
```

A stale group ID must never become valid merely because a later group reuses the same logical name.

Static names must be unique at startup. Dynamic names must be unique among live groups. Destroying a dynamic group invalidates its ID before resources/outbox state are reclaimed.

The registry exposes bounded observational metadata to trusted supervisor/root code:

```text
actor_count
min_actors
max_actors
mailbox pressure
outbox pressure
restart-window count
state: STARTING | READY | DRAINING | STOPPED | FAILED
```

Actors should normally carry `ActorGroupRef` / send capabilities rather than perform ambient global string lookups on every message.


## 17. Actor factory catalog and tree shaking

Do **not** make actor/worker definitions self-register at runtime.

A Java/Angular-style eager registry implemented through import-time/static initialization would create several problems:

- merely importing a file could mutate global runtime state;
- every potentially registering actor definition could become an implicit executable root;
- tree shaking would have to conservatively retain definitions whose registration side effect might run;
- hot-reload generations could accidentally share/stomp registry entries;
- startup order would become observable;
- sandboxed/untrusted code could gain an ambient discovery surface.

Oreslang instead uses a **compiler/linker-generated actor factory catalog**.

Conceptually:

```text
source actor factories
        +
.ores-actors.toml static group factory references
        +
.ores-actors.toml dynamic-template factory references
        +
explicitly exported actor factories
        |
        v
closed-world reachability / tree shaking
        |
        v
generated ActorFactoryCatalog for this executable/generation
```

The catalog is immutable after a code generation becomes active.

It is not populated by executing actor definitions. Actor declarations remain inert until explicitly spawned.

### Build roots

The tree shaker treats the following as roots:

1. the ordinary executable roots such as `main`;
2. every factory named by a static startup group;
3. every factory named by a dynamic group template;
4. every actor factory deliberately exported for runtime/supervisor lookup.

Everything else remains removable.

Example:

```toml
[[group]]
name = "workers"
factory = "workers/worker.ores::worker"
min_actors = 8
max_actors = 128

[[group_template]]
name = "tenant-workers"
factory = "workers/tenant_worker.ores::worker"
max_instances = 1024
min_actors = 0
max_actors = 32
```

Those two symbolic factory references are additional build roots. An unrelated actor factory in `workers/experimental.ores` is still tree-shaken if ordinary reachable code does not reference it.

### Explicit discoverability

If an application truly needs name-based actor-factory lookup beyond configured groups/templates, discoverability must be explicit rather than automatic.

Proposed declaration marker:

```ores
@ExportActorFactory
pub actor fnc image_worker(Config cfg):
    ActorBehavior<ImageJob, ImageEvent> {
  return new ImageWorker(cfg);
}
```

`@ExportActorFactory` means:

- retain this actor factory as a build root;
- add one descriptor to the generated catalog;
- permit trusted supervisor/root code to resolve it by its canonical factory key.

It does **not** execute the factory, spawn an actor, create a group, or grant ordinary actor code ambient registry access.

Library builds may retain exported actor factories as part of the public ABI. Executable builds retain only config-referenced/reachable/explicitly-exported factories.

### Catalog descriptor

The generated catalog should contain metadata, not live actor objects:

```text
ActorFactoryDescriptor {
  key
  actor_kind
  input_type
  output_type
  abi_digest
  code_generation
}
```

A canonical key should be based on the path-oriented Oreslang dependency model, for example:

```text
workers/tenant_worker.ores::worker
```

The descriptor is linked to compiler-generated factory code for that same generation.

Dynamic group creation therefore resolves:

```text
template.factory key
       ->
current generation's immutable ActorFactoryCatalog
       ->
validated actor factory descriptor
       ->
spawn
```

No directory scan, reflection, classpath scan, static initializer, or global mutable self-registration is involved.

### Hot reload

Catalogs are generation-scoped.

A new code generation gets a new immutable catalog. Existing actors keep the generation/catalog lease they were spawned from. A dynamic group created through generation N must not silently resolve the same string key against generation N+1 halfway through its lifetime.

This aligns actor-factory discovery with the existing generation-pin model and prevents stale factory handles from becoming valid against unrelated replacement code.

### Why retain a catalog at all?

The catalog still serves useful purposes even though actors do not self-register:

- startup config validation can fail before `main` if a named factory does not exist;
- dynamic group templates can resolve factories without reflection;
- type/domain metadata can be validated before reserving actor capacity;
- observability can report which concrete factory a group uses;
- hot reload can bind a group to an exact code generation;
- the backend can emit a compact dispatch table for only retained actor factories.

So the registry concept is useful as **generated link metadata**, but harmful as a runtime side-effect mechanism.


## 18. Actor-side group capabilities and confined memory

Group ownership and group membership are intentionally different concepts.

The runtime/supervisor owns the actual `ActorGroup`. Root/supervisor code may hold:

```text
ActorGroupRef<Out>
```

Actor code never receives that control-plane object. A trusted SHARED or PRIVATE actor may instead receive:

```text
ActorGroupHandle<Out>
```

The handle is an opaque capability containing identity/domain/generation authentication only. It contains no Java/Ores pointer to the runtime, supervisor, mailman, actor registry, inbox, outbox, or other mutable group state.

This is especially important for `isoactor`: its `self.group` capability may cross into confined memory because it is opaque, but it cannot dereference the top-level ActorGroup graph.

UNTRUSTED actors are members of a runtime-owned group for supervision/output routing, but `self.group` does not expose a spawn-capable handle to them because untrusted actors cannot spawn children.

A child or grandchild spawn therefore uses a capability, never ownership transfer:

```ores
val child = spawn child_worker(config) with {
  group: self.group
};
```

The group continues to be supervisor-owned if the actor that requested the child later terminates.

## 19. One actor inbox, one group outbox

The runtime enforces the channel topology:

```text
external/group send
        |
        v
 actor.inbox                 exactly one per actor
        |
        v
 serialized actor turn      one execution lease
        |
      emit Out
        |
        v
 group.outbox                exactly one bounded outbox per group
        |
        v
 ActorMailman.receive_mail   one logical serialized mailman
        |
        +----> side effect/capability
        |
        +----> send(...) -> target actor.inbox
```

Actor state may be mutated only from its serialized turn. Actors do not receive direct mutable references to another actor, its inbox, group outbox, or mailman state.

The mailman may be a stateful class:

```ores
class WorkerMailman : ActorMailman<WorkerEvent> {
  let uint completed = 0;

  pub receive_mail(
      ActorMail<WorkerEvent> mail,
      ActorGroupContext<WorkerEvent> group
  ): void {
    match mail.message {
      WorkCompleted(job_id) -> {
        self.completed += 1;
      }

      WorkerIdle() -> {
        // Route through a typed ActorRef; this re-enters that actor's inbox.
      }

      _ -> { }
    }
  }
}
```

The application does not write the endless loop. The runtime owns the resumable loop and invokes `receive_mail` for a bounded quantum while holding the mailman's single execution lease. When the outbox is empty, no carrier thread remains blocked on the group.

`match` is the structural/pattern-matching construct. `switch` is reserved for classic case-style dispatch.
