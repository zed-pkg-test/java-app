# Runtime ownership domains for actor memory

This branch provides the runtime allocation/provenance layer. The `rt copy/take/share/borrow` parser, type/ownership checker, and evaluator from PR #186 require a separate integration PR. No surface syntax or ownership effects are fabricated on current main by this runtime layer.

## Actor-kind contract

The current parser maps `actor` and `shared actor` to SHARED, `isoactor` to PRIVATE, and `untrusted actor` to UNTRUSTED. An isolated actor here means the private actor domain; a physically separate Graal/native isolate is an additional backend boundary.

| Context | Local copy destination | Local take/borrow/share | Crossing the mailbox |
| --- | --- | --- | --- |
| `actor` | Its actor-local, quota-accounted heap | Same actor domain; share is read-only same-object ownership | Existing shared transport data/capability rules and effective policy |
| `isoactor` | Its private slice | Same actor domain, without acquiring SHARED_MEMORY authority | Existing destination isolation-copy path; no confined references escape |
| `untrusted actor` | Its private slice, within the fixed adversarial limits | Same actor domain, without acquiring ambient capabilities | Inbound data only; current runtime forbids outbound actor sends |

All three retain serialized actor turns. Local sharing is distinct from shared-memory transport. Local copying copies the requested value's supported graph, never an entire actor heap. Actor memory identity follows the actor cell across worker migration and continuation resumption.

## Compiler-facing API

`ActorContext.memoryDomain()` and `ActorRuntime.currentActorMemoryDomain()` resolve the stable cell-owned `ActorMemoryDomain`. Off-actor lookup returns empty. Foreign-runtime actor lookup rejects rather than silently falling back to a root/thread domain. ActorGroup Mailman work has no ambient actor ownership authority.

`requireLocalOperation(operation, sourceDomain)` validates current execution, liveness, and source provenance. All four operations reject a foreign actor domain, including COPY: this API cannot authorize reading another actor's confined graph. Mailbox transport must first validate/detach the source data, allocate for the receiver, and tag the resulting receiver-owned graph. No guest operation accepts a raw foreign-heap pointer.

The domain check is necessary but does not prove borrow lifetimes, exclusive mutation, source-use invalidation, read-only qualifiers, graph copyability, or safe class `copy()` behavior. Those remain compiler/type-contract obligations. `rt take`, `rt borrow`, and `rt share` preserve runtime identity and do not themselves reserve fresh storage.

`reserveAllocation(bytes)` reserves actor-owned allocation before construction. `allocateCopy(sourceDomain, bytes, verifiedCopyPlan, collector)` performs reservation, construction, cooperative safepoint/liveness checks, and weak cleanup registration before publication. Quota rejection does not invoke the plan. Throwing/null plans, cancellation, and cleanup-registry admission failures roll back the reservation. A returned allocation handle can be read only in its live owning actor domain; it is neither sendable nor a shared-memory capability.

`bytes` is a trusted compiler/backend-proven upper bound for the copy and retained construction storage. The Supplier is a trusted lowering hook, not a guest callback API or evidence of graph independence. A variable-size class copy must use budget-enforcing allocation throughout its construction or supply a proven bound. It must retain the existing source-mutation/fresh-identity/no-mutable-alias checks. Graph walks and user copy methods need bounded traversal and compiler safepoints; the entry/exit safepoints alone cannot interrupt a non-cooperative host callback.

The allocation's reservation stays live after mailbox release. The collector weakly tracks the payload without retaining it through its cleanup closure. Compiler ownership drops may close the allocation explicitly; collection and actor finalization release remaining accounting idempotently. Retirement invalidates the domain even while a host test still retains an old allocation handle. Runtime domain/reservation metadata may be retained only by its own private actor; this does not admit arbitrary mutable payload graphs into private behavior fields.

## Accounting and physical isolation

Private/untrusted allocations use `ActorMemorySlice.reserveHeap`: the per-actor slice and aggregate runtime quota both apply. Shared actor allocations use an actor-owned heap reservation: its persistent heap plus mailbox bytes fit the same per-actor policy, while shared and private charges fit the aggregate runtime ceiling. Shared cells and approved shared transport handles retain their independent existing policy and quota rules.

On JVM, these are logical confinement/accounting contracts. This API does not turn a JVM object into physically separate private memory, pin GC objects, or provide a hostile-code sandbox. Physically isolated/untrusted backends must bind the same domain to their allocator/isolate and revocation mechanism, preserve copy/transport semantics, and reject cross-isolate pointer capabilities. Untrusted execution requires the existing sandbox/backend gates, not merely an UNTRUSTED actor label.

## Separate compiler integration gate

1. Reconcile PR #186 onto the three-kind actor compiler stack without reverting current actor/security features.
2. Tag mutable allocations and transported receiver graphs with their actual domain. Preserve the caller's domain through ordinary helper calls; never infer ownership from a Java thread.
3. Route local rt provenance checks and copy allocation through this API. Define a separate root/context allocation domain rather than using a scheduler thread as root ownership.
4. Preserve class-copy checks, immutable scalar behavior, shared qualifiers, affine take effects, and lexical/suspension borrow rules.
5. Retain receiving-domain mailbox transport, type/capability admission, runtime affinity, depth/node limits, and policy checks. A local share must not become an approved cross-domain shared handle.
6. Keep copied allocations charged while retained in actor state; release on ownership drop, collection, cancellation, or actor retirement.
7. Add source-level rt tests for all three actor kinds, helpers, suspension, nested graphs/capabilities, quota exhaustion, transport, shutdown/restart, and physical-isolate backends.

Runtime tests on this branch cover the domain/allocation layer. They are not end-to-end proof of the later rt compiler integration or an untrusted physical-isolate backend.
