# Oreslang VM and scheduler domains

Status: runtime contract implemented as a stack on the ActorGroup/mailman design.

## Boundary

Oreslang has an explicit **Oreslang VM** runtime boundary.

`OresContext` is the Truffle/Graal integration object for one language context.
`OresVM` owns the physical process scheduler infrastructure. Logical
`ActorRuntime` instances attach to the VM and keep their own actor/group
registries and policy ceilings.

Production contexts and hot-reload generations in one OS process attach to the
same process VM. Creating another Truffle context must not silently multiply the
physical scheduler pools.

Guest Oreslang code never receives a Java `Executor`, `ThreadPoolExecutor`,
thread factory, or mutable VM scheduler handle.

## Four scheduler domains

The VM owns exactly four guest/control execution pools:

1. **CONTROL** — main/root execution, supervisors, and ActorGroup mailmen.
2. **SHARED_ACTOR** — shared-address-space actors.
3. **ISOACTOR** — private/memory-confined actors.
4. **UNTRUSTED_ACTOR** — sandboxed untrusted actors.

Timers, I/O reactors, watchdogs, GC threads, and Graal/JVM service threads are
runtime infrastructure. They are not additional actor scheduler domains and may
not execute actor guest code directly.

Each scheduler is M:N: actors/mailmen are logical tasks multiplexed over bounded
carrier threads. A logical actor or mailman does not own an OS thread.

The production default keeps the existing elastic carrier policy: a small floor
(5 today) with bounded headroom (20 today). The control pool currently inherits
the same configured floor/headroom policy as the shared pool, but it is a
physically distinct executor and authority domain.

## Control plane

Supervision and ordinary actor execution must not share the same scheduling
queue.

The control plane runs:

- root/main Oreslang execution;
- supervisor lifecycle and failure policy;
- one logical `ActorMailman` per `ActorGroup`;
- bounded runtime control work.

Actor mailmen are serialized logical consumers. They may migrate between
control-plane carrier threads across quanta; they are not permanently assigned a
thread.

Mailmen never execute on the shared/private/untrusted actor dispatcher chosen by
the actor that emitted a message. The actor's execution domain determines where
the actor runs, not where its group's mailman runs.

Supervisor failure/lifecycle policy remains semantically distinct from mail
routing even though both live on the control-plane scheduler.

## Actor scheduler isolation

Actor execution remains bulkheaded:

```text
OresVM
├── CONTROL
│   ├── main/root
│   ├── supervisors
│   └── ActorGroup mailmen
├── SHARED_ACTOR
│   └── shared actors
├── ISOACTOR
│   └── private/confined actors
└── UNTRUSTED_ACTOR
    └── sandboxed actors
```

The existing actor invariants remain unchanged:

- one active execution lease per actor;
- runnable-actor queues rather than mailbox scans;
- bounded scheduler quanta;
- await/timer/I/O completion re-enqueues work instead of running actor code;
- isoactors do not gain shared-memory authority;
- untrusted actors cannot create threads or escape their sandbox.

## VM ownership and lifecycle

`OresVM.process()` is host/process owned. Disposing one `OresContext` closes
that context's logical `ActorRuntime`, actors, groups, and generation state, but
must not shut down the process VM's physical scheduler pools because other
contexts/generations may still use them.

Explicit host/test `new ActorRuntime(...)` construction receives a dedicated
VM scheduler set and owns that VM's lifecycle. Closing that runtime shuts down
all four dedicated scheduler pools.

This split keeps Truffle integration separate from Oreslang runtime semantics:

```text
OresLanguage
    ↓
OresContext
    ↓
OresVM
    ├── control scheduler
    ├── shared-actor scheduler
    ├── isoactor scheduler
    ├── untrusted-actor scheduler
    └── internal timer/watchdog/reactor services
        ↓
ActorRuntime / ActorGroupRuntime
```

## Security boundary

Scheduler submission is runtime-internal. Source code must use language/runtime
primitives such as `spawn`, `ActorGroup`, supervision, inbox/outbox messaging,
and async I/O.

Java interop must not become an escape hatch to:

- create arbitrary Java threads;
- construct executors/fork-join pools;
- shut down or resize VM schedulers;
- submit work directly to a scheduler domain;
- transfer an untrusted/isoactor continuation onto a different domain.

The VM exposes immutable topology/identity metadata for observability, not raw
executor authority.

## Follow-up hardening

The initial VM declaration establishes the ownership boundary and physical fourth
pool. Follow-up work should add explicit control-plane priority classes so
supervisor-critical work cannot be starved by a flood of mailman quanta, while
keeping each mailman bounded and serialized.
