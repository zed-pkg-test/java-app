# Actor-local allocation heaps

Oreslang separates three independent concerns:

1. **Scheduling domain** — which bounded carrier pool executes an actor turn.
2. **Allocation domain** — which actor owns ordinary state and allocations.
3. **Sharing capability** — whether code may intentionally access runtime-wide shared state.

A `shared actor` therefore means “may intentionally use shared memory,” not
“all ordinary allocations live in one process-global heap.”

## Runtime mapping

Every actor has one `ActorMemorySlice`, exposed by
`ActorContext.localMemory()`. Its provenance is exactly the semantic
`AllocationDomain` from the allocation-domain runtime contract:

| Actor kind | Semantic domain | Local accounting |
| --- | --- | --- |
| SHARED | `ACTOR_LOCAL(actorId)` | `sharedActorLocalMemoryBytes` |
| PRIVATE / isolated | `ACTOR_PRIVATE(actorId)` | `privateMemoryBytes` |
| UNTRUSTED | `UNTRUSTED_ISOLATE(actorId)` | `untrustedLocalMemoryBytes` |

`ActorContext.privateMemory()` remains a compatibility/confinement view:
PRIVATE and UNTRUSTED actors receive it; SHARED actors do not.

`Shared<T>`, `SyncCell<T>`, `SharedMutex<T>`, and future explicitly
synchronized shared structures remain in `RUNTIME_SHARED`.

The JVM backend currently proves provenance, owner checks, and quota accounting.
It does not claim arbitrary Java object allocation is already physically
separated. Native/isolate backends may map the same semantic domain to arenas,
slabs, pages, or isolate heaps without changing source semantics.

## Ownership operations

Allocator placement happens only after ownership/type analysis:

- `rt borrow`: no allocation and no relocation;
- `rt take`: no allocation/relocation for a same-domain transfer;
- `rt share`: no allocation-domain promotion; actor-local storage remains local;
- `rt copy` of a Copy scalar: copy-elided, zero local-heap charge;
- `rt copy` of identity/storage-bearing data: fresh storage in the current
  semantic allocation domain.

Class and module namespaces are not runtime values and never reach allocation
lowering.

## Reference topology

The intended reference graph is asymmetric:

- actor-local -> approved explicit shared capability: allowed;
- explicit shared -> actor-local mutable pointer: forbidden;
- actor A local -> actor B local raw mutable pointer: forbidden;
- ordinary cross-actor transport: receiver-side copy/freeze/reconstruction or an
  explicit transactional transfer;
- immutable runtime-shared data may be referenced by multiple SHARED actors when
  capability policy permits it.

This keeps actor-local reclamation independent and avoids turning actor-local
heaps into one process-wide tracing graph.

## Accounting and teardown

Runtime counters remain distinct:

- `privateMemoryBytes()`
- `sharedActorLocalMemoryBytes()`
- `untrustedLocalMemoryBytes()`
- `actorLocalMemoryBytes()` — sum of the three actor-local categories
- `sharedMemoryBytes()` — explicit runtime-shared storage
- `actorMemoryBytes()` — actor-local + explicit shared

All categories compete for the parent runtime `maxHeapBytes` ceiling.
Terminating an actor closes its local slice and bulk-releases that actor's local
accounting. It does not reclaim unrelated `RUNTIME_SHARED` values.

Direct confined byte blocks remain available only to PRIVATE and UNTRUSTED
actors. SHARED actors may reserve local storage but cannot use the stronger
private/direct-memory capability.

## Lowering target for #289

Compiler/interpreter allocation lowering should use the current
`ActorContext.localMemory()` only for storage-bearing values:

- actor fields and persistent actor state;
- Ores class/struct/record instances;
- list/map/dynamic-struct/array backing storage;
- closure environments owned by the actor;
- continuation state that survives `await` / channel / select suspension.

A future per-turn nursery may use the same semantic domain with a shorter
lifetime. Values that escape the turn must be promoted to persistent actor-local
storage before the turn nursery is reset.

Carrier migration never changes any of these identities.
