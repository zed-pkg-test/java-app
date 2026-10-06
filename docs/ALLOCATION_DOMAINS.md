# Allocation domains and explicit ownership

Oreslang keeps **ownership state** and **allocation domain** separate.

Ownership answers whether a value is uniquely owned, read-only shared, borrowed,
or moved. Allocation domain answers where the storage belongs and which semantic
execution identity may dereference it.

The runtime exposes logical allocation-domain tokens so compiler/interpreter
lowering does not infer memory ownership from JVM carrier-thread identity.

| Runtime context | Allocation domain |
| --- | --- |
| root / ordinary task | `ROOT` |
| ordinary state of a `shared actor` | `ACTOR_LOCAL(actorId)` |
| isolated/private actor | `ACTOR_PRIVATE(actorId)` |
| untrusted actor | `UNTRUSTED_ISOLATE(actorId)` |
| `Shared<T>`, `SyncCell<T>`, `SharedMutex<T>`-style explicit shared state | `RUNTIME_SHARED` |

Actor-owned allocation domains are keyed by `ActorId`, never by a pthread/JVM
thread. Carrier migration and continuation resumption therefore do not change a
value's ownership domain.

## Contract for rt ownership operations

When the explicit ownership stack is integrated, lowering must obey these rules:

- `rt borrow x` does not relocate storage. The borrow retains the allocation
  provenance of `x` and cannot widen actor/isolate authority.
- `rt take x` changes logical unique ownership within one allocation domain.
  It does not silently migrate bytes to another actor heap.
- `rt copy x` creates independent storage in the **current semantic allocation
  domain**. Immutable scalars may remain allocation-free.
- `rt share x` changes same-domain ownership to read-only shared ownership. It
  does **not** promote actor-local/private/untrusted memory into
  `RUNTIME_SHARED`.

Mailbox/channel/event-bus/task boundaries remain separate transport operations.
Cross-domain movement must use an explicit checked copy/freeze/transfer protocol;
raw actor-local mutable references are never made sendable by `rt share`.

## Backend rule

The current JVM runtime may implement some domains as logical accounting and
alias checks. Native/AOT implementations may map the same tokens to arenas,
slabs, regions, or hard isolates. Source semantics must not depend on the
physical allocator implementation.

Opaque host/FFI values have no inferred Oreslang allocation provenance and must
fail closed for operations whose alias/copy safety cannot be proven.
