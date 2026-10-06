# Shared actors and actor-local heaps

Oreslang treats scheduling, allocation, and sharing as separate concerns.

- **Scheduling domain** chooses the bounded carrier pool that executes an actor turn.
- **Allocation domain** says which semantic actor owns ordinary state and storage.
- **Sharing capability** controls whether an actor may intentionally access runtime-wide shared state.

A `shared actor` therefore means "may use explicit shared memory"; it does **not** mean every ordinary allocation belongs to a process-global shared heap.

## Runtime contract

Every actor owns an `ActorMemorySlice` exposed as `ActorContext.localMemory()`.

- SHARED -> actor-local heap, `privateMemory()` is empty.
- PRIVATE/isolated -> actor-local confined heap; `privateMemory()` aliases `localMemory()`.
- UNTRUSTED -> actor-local isolate/confined heap; `privateMemory()` aliases `localMemory()`.

Explicit `Shared<T>`, `SyncCell<T>`, `SharedMutex<T>`, and SHARED mailbox transport remain in the runtime-wide shared accounting domain. A SHARED actor's local direct block is still actor-local and owner-checked.

The JVM backend currently proves semantic ownership and quota accounting. It does not claim arbitrary Java objects are physically isolated. Native/polyglot backends may map the same domains to arenas, slabs, or isolate heaps.

## Accounting

The runtime reports:

- `privateMemoryBytes()`: confined actor-local bytes (PRIVATE + UNTRUSTED);
- `sharedActorLocalMemoryBytes()`: ordinary actor-local bytes owned by SHARED actors;
- `actorLocalMemoryBytes()`: sum of actor-local categories;
- `sharedMemoryBytes()`: explicit runtime-shared state and SHARED mailbox backing;
- `actorMemoryBytes()`: all of the above.

All categories compete for the parent runtime heap ceiling.

For a SHARED actor, its local heap and queued shared-mailbox bytes also compete for the **same per-actor** `maxHeapBytes` limit. This closes a quota-bypass where an actor could otherwise consume one full allowance locally plus another full allowance in queued mailbox storage.

## Ownership operations

This heap contract is intentionally independent of ownership syntax:

- `rt borrow`: no relocation;
- `rt take`: no implicit relocation within one domain;
- `rt share`: same-domain read-only ownership; not promotion;
- `rt copy` primitive: copy-elided/value-semantic, zero heap charge;
- `rt copy` heap value: fresh storage in the current semantic allocation domain.

Cross-actor or cross-isolate movement still goes through an explicit mailbox/channel/freeze/transfer boundary.

## Reference direction

The target topology is asymmetric:

- actor-local -> explicit shared: only through explicit promotion/freeze/capability construction;
- explicit shared -> actor-local: copy/reconstruction, not raw mutable aliasing;
- actor A local -> actor B local: copy/move/transport, never raw aliasing;
- actor termination retires its local heap without reclaiming unrelated runtime-shared state.

Carrier-thread migration never changes allocation ownership.
