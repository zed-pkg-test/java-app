# Concurrency proof matrix

Pinned concurrency source stack:

`ores-truffle-oreslang/oreslang-source.java@8aef043fa8257c1aece5cb030bd4620cca10bbaa`

That is PR #269, which converges the VM-root scheduler, channels, callback write
surface, untrusted actors, and the ActorGroup/event-bus stack. Harness-local
fixes from async-testing PR #2 remain in place for Darwin pthread declarations,
closed-mailbox/subscription teardown, and null-valued `Future<void>` aggregation.

## Executable proofs

| Area | Proof | Status |
|---|---|---|
| VM root scheduler | CONTROL carriers are live before first root work; root work runs on the VM-owned scheduler carrier | executable |
| Future + scheduler | Awaiting a channel Future unwinds and resumes through a fresh owning-scheduler dispatch | executable |
| Producer isolation | Channel/Future completion thread never executes guest continuation code | executable |
| Mailbox/channel unification | ActorCell mailbox transport is `ChannelRuntime.Channel<MessageEnvelope>` | executable |
| `nb readch` | Pending channel Future can re-enter the actor through a runtime continuation envelope | executable |
| `nb writech` Future | `writeAsync` returns a pending `OresFuture<void>` until a reader commits | executable |
| `nb cb writech` syntax | Parser/typechecker produce a nonblocking callback channel operation with callback body | executable |
| `nb cb writech` scheduler substrate | Completion callback is re-enqueued through the owning actor mailbox/lease and never runs on the producer thread | executable |
| select / nb select | Channel/select registration uses the common runtime substrate; source parser supports nonblocking select | executable |
| ActorGroup event bus | Subscription is channel-backed; event Future completion re-enters subscriber actor through mailbox continuation | executable |
| Shared actors | Shared actor progresses on SHARED dispatcher | executable |
| Iso/private actors | Private actor progresses on separate PRIVATE dispatcher | executable |
| Untrusted actors | Untrusted actor progresses on an independently bulkheaded dispatcher with UNTRUSTED identity | executable |
| Hungry actors | Dedicated native carrier does not consume/starve ordinary actor dispatcher | executable |
| Cancellation | Actor cancellation detaches outstanding channel continuation and late completion cannot resurrect actor | executable |
| Event publisher isolation | Event publisher never runs subscriber guest callback inline | executable |
| `Future<void>` aggregation | Ready and pending void futures aggregate without null-loss/hangs | executable |

## Contract gaps this harness must not pretend are solved

### 1. Pending blocking source waits inside actors

Arbitrary **pending blocking** actor `await`, `readch`, `writech`, and
`select` still need source-frame/state-machine lowering onto the resumable
`OresScheduler.Task` ABI. Already-ready operations can complete; a genuinely
pending source-level blocking wait must not pin a bounded carrier.

**Required proof before marking complete:** a source-level actor suspends on
each pending operation, another actor continues on the same bounded dispatcher,
and the suspended actor later resumes on a fresh dispatch without retaining a
Java interpreter stack.

### 2. Physical untrusted-isolate revocation

The runtime now has a distinct UNTRUSTED actor kind, dispatcher, quota policy,
and force-cancellation contract. The final host isolation manager still needs
to prove that force cancellation revokes the actual execution/isolation domain
before logical teardown.

**Required proof:** execute hostile guest work in the real untrusted isolation
boundary, revoke it, positively verify guest execution cannot continue, and
only then observe logical actor teardown.

### 3. ActorMailman

ActorGroups and the event bus are present, but this harness does not claim a
final `ActorMailman`/group-outbox design.

Before claiming it, prove bounded MPSC outbox behavior, CONTROL-pool execution,
no mailbox scanning, runnable-actor enqueueing, and teardown that cannot strand
outbox entries.

### 4. Persistent actor-class convergence

This harness proves the runtime actor substrate and actor callable/channel
syntax. It does not yet certify the complete persistent
`define actor Worker as ... end` object model, hot-reload ABI, and all spawn
forms together.

## Acceptance rule

A feature moves from **contract gap** to **proven** only when:

1. there is an executable test here;
2. it uses the real pinned runtime/compiler implementation, not a mock;
3. it proves scheduler/carrier ownership, not only output values;
4. cancellation and late-completion behavior are covered where applicable;
5. bounded-resource behavior is covered (mailbox/channel/outbox/backpressure);
6. the proof runs in CI on a clean checkout.
