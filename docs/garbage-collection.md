# Garbage collection and lifetimes

Oreslang is **ownership first, tracing-GC last**. Ordinary guest values are governed by move/borrow/copy rules and actor ownership; the runtime collector exists for host/interop resources, dynamic cycles, reflection/FFI wrappers, runtime metadata, abandoned asynchronous capabilities, and other values that cannot be retired purely from static ownership.

The collector is not a memory-safety boundary. It must never make an otherwise-invalid ownership program valid.

## Rust-like deterministic lifetime model

The compiler/runtime should prefer these mechanisms, in this order:

1. **Copy values** for small immutable scalars/value structs.
2. **Move-only ownership** for ordinary mutable identity and container values.
3. **Lexical borrows** for temporary non-owning access.
4. **Deterministic release** at scope/actor/isolate boundaries for owned runtime resources.
5. **Bulk heap/arena retirement** for isolated actor domains.
6. **Tracing/weak-owner cleanup** only for residual host-managed graphs that cannot be proven dead statically.

This is intentionally closer to Rust's ownership/RAII model than to a conventional single tracing heap. Oreslang does not need user-written lifetime annotations for ordinary code when the compiler can infer the lifetime safely.

Runtime cleanup hooks are host/runtime hooks, not arbitrary guest finalizers. They must be idempotent and short/non-blocking. A long cleanup should hand work to a subsystem-owned worker rather than executing an unbounded operation on an actor finalization path.

## Heap domains

GC and reclamation follow **semantic ownership domains**, never carrier-thread identity.

### Shared actors

A SHARED actor now owns an **actor-local heap domain as well as access to the explicit shared region**. These are different allocation classes:

- ordinary actor-owned state and compiler-lowered temporaries belong in `context.localMemory()` / the actor-local heap;
- `Shared<T>`, `SyncCell<T>`, `SharedMutex<T>`, and shared mailbox transport belong in the runtime shared region;
- `context.privateMemory()` remains empty for SHARED actors, because a local heap does not imply private-actor capability isolation.

This hybrid is intentional. We keep locality, bulk actor teardown, and small actor-local tracing sets without giving up the zero-copy shared-memory path where the program explicitly asks for sharing.

The actor-local heap and its queued shared mailbox bytes consume **one combined per-actor heap ceiling**. A shared actor cannot evade a 16 MiB policy by using 10 MiB of local state plus another 10 MiB of queued shared messages.

The current JVM backend exposes actor-local direct-memory blocks with semantic-owner checks. A native backend should map the same domain to a dedicated arena/slab/heap (for example an `mmap`/VirtualAlloc-backed region) and keep explicit shared allocations in a separate shared allocator.

Shared-actor reclamation favors deterministic drop and bounded incremental cleanup. A shared actor must not trigger a whole-process tracing pass merely because one actor requests `actor.gc()`.

### Private / isoactors

A private actor owns an `ActorMemorySlice` independent of its carrier thread. On the current JVM runtime that slice includes quota accounting plus actor-confined direct-memory blocks; a native/isolate backend may map the same contract to a true per-actor arena or heap.

Actor termination closes the private slice **before** fallback host cleanup runs. This makes bulk actor-heap retirement the fast path. Host cleanup is only the residual path for resources that live outside the actor arena.

A native implementation should prefer dropping the actor arena/heap in O(1) or O(number of arena segments), rather than tracing every object in that heap.

### Untrusted actors

The untrusted-actor design uses a stronger isolation boundary (GraalWasm/native-image isolate, optionally an external process + OS sandbox). Its memory must be owned by that isolate/sandbox domain. Termination should destroy/revoke the whole untrusted heap/isolate before trusted runtime cleanup proceeds.

The trusted/shared collector must not trace through raw mutable references into an untrusted heap. Cross-domain transfer must be copied/frozen data or explicit capability handles with independent lifecycle rules.

## Runtime cleanup registry

Host/interop resources may register an idempotent cleanup hook with `RuntimeGarbageCollector.track(owner, cleanup)`. The owner is weakly referenced, so registration does not extend guest lifetime. Cleanup closures must not strongly capture the owner.

The registry is bounded per context and per actor. `track` returns a deterministic `CleanupHandle`; explicit close/drop retires the entry immediately. If cleanup fails transiently, the entry remains logically retired and is queued for later bounded retry even if a stale host reference keeps the former owner reachable.

Per-domain storage uses stable slots plus a rotating cursor. Repeated small actor sweeps therefore make progress across the whole domain without copying the domain registry or repeatedly scanning the same live prefix.

## Bounded collection quanta

Collection work is deliberately incremental:

- `actor.gc()`: at most 256 actor-domain entries per call.
- actor termination: at most 32 actor-domain entries synchronously on the finalization path (or a smaller configured actor quantum).
- periodic maintenance: at most 1,024 entries per tick.
- explicit `process.gc()`: at most 4,096 registry entries per call, in addition to its separately throttled host-GC request.

Retired actor domains are placed on a round-robin queue. A large actor or a repeatedly failing cleanup hook cannot monopolize the maintenance thread or prevent other retired heaps from making progress.

Retry work also has a bounded share of each process sweep. Weak-reference work is drained from a `ReferenceQueue`, so normal maintenance is O(newly-dead owners) rather than O(all tracked owners).

These are **entry budgets**, not permission for slow cleanup hooks. Runtime cleanup hooks must stay short.

## Periodic sweeps

A process-shared daemon timer schedules context sweeps, so Oreslang does not park one sleeping sweeper thread per language context.

Periodic sweeps never call `System.gc()`. They drain:

- bounded retry work,
- newly-dead weak owners from the `ReferenceQueue`,
- bounded round-robin work from retired actor domains.

Normal Java/Graal heap tracing remains under the host collector.

## Explicit collection

`actor.gc()` is actor-domain local and never requests JVM-wide collection. Calling it outside an actor mailbox turn is an error. Actor-domain identity comes from the stable semantic actor execution domain, not a scheduler/carrier thread.

`process.gc()` is the explicit global fallback. It requires `GC_CONTROL`, is unavailable to strict FaaS/untrusted policy, and its host-GC request is throttled so guest code cannot turn it into a high-frequency global pause primitive.

The common path should not require `process.gc()` for correctness or acceptable memory usage.

## Actor exit and logical liveness

An actor heap is not reclaimable merely because no carrier thread is currently running it. Mailbox envelopes, suspended continuations, pending futures/select registrations, timers, generation leases, and other actor-owned runtime state remain roots until their logical lifetime ends.

Once the actor is truly finalized:

1. mailbox reservations are drained,
2. its private/isoactor memory slice is invalidated and released,
3. the semantic GC domain is retired,
4. one small cleanup quantum runs,
5. remaining host cleanup is queued for incremental background retirement.

This ordering keeps isolated-heap reclamation deterministic while keeping dispatcher pauses bounded.

## Shutdown

Context shutdown first closes actor/async runtimes, then stops periodic GC and performs a best-effort final drain. Shutdown may do more work than an ordinary actor pause because the context itself is going away, but durable external resources should still use explicit close/release APIs instead of depending on shutdown cleanup.

## Direction for native backends

The target architecture is deliberately multi-heap:

- shared/context heap for shared runtime state;
- one arena/heap per private isoactor (or a small actor-group heap when explicitly chosen for density);
- one independently revocable heap/isolate for untrusted actors;
- pinned process/stable roots kept outside ordinary actor collection.

With ownership and deterministic drop eliminating most short-lived garbage, actor-local heaps can use tiny nursery/mark regions or pure arena retirement. That keeps tracing sets small and makes pauses proportional to one actor/domain rather than the whole Oreslang process.
