# Actor event loop and isolation

Oreslang actors are lightweight schedulable execution domains, not OS threads.

## Non-negotiable execution invariant

For every actor A:

```
concurrent_executors(A) ∈ {0, 1}
```

An actor may execute on different carrier threads across its lifetime, but it must
never hold more than one execution lease at a time. Carrier identity is not actor
identity.

The runtime enforces this twice:

1. an atomic execution lease names the current carrier;
2. active actor turns are constrained to exactly 0 or 1.

A violation is a runtime invariant failure, not an application-level race to be
papered over with mutexes.

## Three carrier bulkheads

The process owns three independent actor carrier pools:

- SHARED actors: shared-memory domain;
- PRIVATE / isoactors: memory-isolated domain;
- UNTRUSTED actors: sandbox domain.

Production defaults use an elastic 5..20 carrier range per pool. Threads are
leased to runnable actors only while an event-loop quantum is executing. Idle
actors own no carrier.

Pools grow when runnable actor turns queue behind active carriers and shrink back
toward their configured floor after demand drains. A hard ceiling prevents actor
load from turning into unbounded native-thread creation.

## Event-loop quantum

Each actor owns one FIFO mailbox. A carrier that acquires the actor lease runs a
bounded event-loop quantum:

1. enter the actor execution domain;
2. initialize behavior if necessary;
3. dequeue and handle messages serially;
4. stop after the configured message throughput or batch-time quantum;
5. release the execution lease;
6. requeue the actor at the tail if mailbox work remains.

This gives mailbox fairness without assigning a permanent thread to an actor.

Compiler/runtime checkpoints remain mandatory for cancellation, untrusted fuel,
and deadlines. Arbitrary trusted native/JVM stacks are never asynchronously
suspended at an unsafe instruction. A message that ignores safepoints is handled
by the watchdog/fail-stop path rather than unsafe thread suspension.

## Shared actors: one public mailbox ingress

Shared actors do not expose an object-style public method surface.

A shared actor may declare:

- private state;
- private helper methods;
- exactly one public instance ingress named `receive_message`.

All external interaction goes through the actor reference/mailbox. Public static
helpers and additional public methods are rejected.

This preserves semantic isolation even though the backing address space is
shared: mutable actor-owned state is reachable only while that actor holds its
execution lease. Cross-actor shared mutation must use explicit synchronized
capabilities such as runtime-managed shared cells/mutexes; ordinary actor state
does not become concurrently callable shared-object state.

The public ingress is deliberately singular so future typed message patterns can
lower to one Erlang-style receive loop instead of many concurrently callable
methods.

## Private / isoactors

Private actors use the same single-executor event-loop scheduler but also receive
an actor-confined memory slice. Mutable private state cannot be dereferenced from
another actor or the host execution domain.

## Untrusted actors

Untrusted actors use the same execution-lease invariant on a dedicated carrier
pool plus stronger controls:

- memory isolation;
- hard lifetime;
- per-turn fuel;
- one-message scheduling quantum;
- bounded mailbox/HTTP data;
- restricted stateless outbound HTTP;
- no child actor spawning;
- watchdog cancellation/fail-stop behavior.

Untrusted actors do not gain raw TCP/socket authority merely because their
carrier changes.

## Why carriers are not pinned

Permanent actor/thread affinity would waste native threads and couple actor
lifetime to carrier lifetime. Oreslang instead permits soft locality: a later
turn may run on the same carrier when convenient, but migration is legal and
must not change semantics.

This follows the useful parts of Erlang/BEAM and Akka:

- actor/process identity is independent of scheduler-thread identity;
- one actor/process executes serially;
- runnable actors are multiplexed over bounded scheduler resources;
- hot actors yield at scheduler-defined boundaries;
- isolation comes from the actor model and capabilities, not from thread
  affinity.
