# Oreslang Async / Actor / Channel Proof Harness

This repository is an executable integration harness for the Oreslang concurrency stack.

It is intentionally designed to prove cross-layer invariants involving:

- OresFuture / Awaitable / async-await scheduler resumption
- the VM-owned CONTROL/root scheduler before `main`
- actor spawn and actor execution domains
- shared, private/iso, untrusted, and hungry actors
- mailbox = policy around `Channel<Envelope>`
- readch / writech / nb readch / nb writech / nb cb writech
- static and dynamic select / nb select
- ActorGroup event-bus delivery and backpressure
- cancellation, fairness, progress, and carrier-starvation safety

The current proof branch tracks the active convergence work in
`oreslang-source.java` PR #269.

Pinned concurrency source:
`ores-truffle-oreslang/oreslang-source.java@8aef043fa8257c1aece5cb030bd4620cca10bbaa`.

The harness also retains its own proven fixes from PR #2 for Darwin pthread
declarations, closed channel/subscription teardown, `Future<void>` typing, and
null-valued void-future aggregation.

Run the proof suite with:

```sh
mvn -B -DtrimStackTrace=false clean test
```

CI exercises Linux and macOS on JDK 21 and 25. When the source organization
cannot allocate a runner, validate the exact Git tree in a funded carrier and
verify the carrier tree SHA matches before treating the run as evidence.

See `PROOF_MATRIX.md` for executable invariants and deliberately unresolved
contract gaps. In particular, pending blocking source-level actor waits still
need true resumable source-frame lowering and must not be represented as solved.
