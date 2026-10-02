# Garbage collection, ownership, and actor isolation

Oreslang separates **lifetime semantics** from **physical heap reclamation**.

## Ownership is authoritative

Normal guest values follow the ownership/borrow checker. Move-only values have one owner, immutable borrows may overlap, mutable borrows are exclusive, borrows cannot outlive their owners, and moved values cannot be used. These rules define when a value is semantically dead independently of the host VM.

Oreslang should prefer inferred lexical lifetime regions rather than user-written lifetime syntax. Explicit lifetime parameters should be introduced only if APIs eventually require relationships that cannot be inferred safely.

## JVM/Graal heap reclamation

The first Truffle implementation runs on a managed JVM heap. Consequently, Oreslang cannot promise Rust-style physical deallocation at the exact lexical drop point. When ownership says a value is dead, the interpreter stops retaining it; HotSpot/Graal GC decides when the backing storage is reclaimed.

`process.gc()` and `actor.gc()` are therefore **collection hints**, not `free()`:

- they never invalidate a live borrow or owner;
- they never expose object addresses, finalizer ordering, or memory reuse;
- requests are rate-limited so guest code cannot turn full-heap collection into a tight-loop denial of service;
- `actor.gc()` is valid only in an actor execution domain;
- on a shared JVM heap, `actor.gc()` cannot promise a physically actor-local collection.

## Shared actors

Shared actors share a host address space but not ordinary mutable Oreslang state. Their semantic isolation is enforced by:

1. actor-qualified execution domains rather than JVM thread identity;
2. ownership/move checking for local values;
3. deep-freeze message transport for ordinary cross-actor values;
4. explicit read-only sharing for frozen graphs;
5. capability-gated `SharedMutex<T>` for the exceptional shared-mutable case;
6. rejection of foreign-runtime actor references and unknown mutable host objects.

This follows the useful part of Akka/Ractor/Actix practice: communicate by message, make mutable actor state actor-owned, and make shared mutable memory an explicit exceptional capability rather than the default.

## Private/isolate actors

A private/isolate actor should retain the same language-level ownership rules but use a stronger runtime boundary (separate Truffle isolate/process/address space where configured). Cross-boundary values must be serialized/frozen; Java object references are never a valid isolate transport.

## Runtime-managed junk

Reflection metadata, code-cache entries, dead actor cells, transport buffers, and future FFI handles belong to runtime maintenance rather than the guest ownership graph. Periodic maintenance may clean those registries independently of host heap GC. Such registries should use bounded caches, weak references where appropriate, and deterministic close/drop hooks for OS resources.
