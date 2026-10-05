# Data-Oriented Programming and ECS

Oreslang treats Entity Component System (ECS) as an opt-in data-oriented execution model, not as a replacement for classes, actors, modules, or ordinary structs.

The design goal is to make bulk state layout and access effects explicit enough that the compiler and OresVM can preserve sequential semantics while choosing cache-efficient CPU execution, parallel scheduling, SIMD lowering, and eventually heterogeneous CPU/GPU placement.

## Language model

### Entities

An `Entity` is opaque identity only. It has no fields and no methods. OresVM uses a generation plus slot index so a stale entity handle cannot accidentally refer to a later entity that recycled the same slot.

### Components

Components are declared with `define component`:

```ores
pub define component Position as
  x: f32
  y: f32
end

pub define component Velocity as
  x: f32
  y: f32
end
```

A component is a restricted, data-only declaration:

- no methods or behavior;
- no inheritance;
- no field initializers;
- no annotations or field modifiers;
- fixed, statically known fields;
- only fixed-layout POD state in the initial ABI: integer/floating/complex scalars, booleans, `Entity`, tuples/records of POD, and other POD components.

Variable-sized/string/container/object/borrow/function/future/mutex state is intentionally rejected from component storage in this first ABI. This keeps component layout analyzable and leaves room for direct SIMD/GPU layouts without silently changing representation later.

Empty components are legal and act as tags.

### Systems and effects

A system is an ordinary synchronous `fnc` with explicit ECS annotations:

```ores
@system
@reads(Velocity)
@writes(Position)
@gpu_eligible
fnc movement(Query<Position, Velocity> rows): void {
  return;
}
```

The compiler treats the annotations as effect contracts, not comments.

- `@reads(...)`: required read-only components.
- `@writes(...)`: required writable components. A write is also readable at runtime.
- `@optional(...)`: optional read-only components.
- `@without(...)`: archetypes containing these components do not match.
- `@gpu_eligible`: declares placement eligibility; it is not permission to bypass normal capability/effect checks.

The positive component set in `Query<...>` must exactly match the union of `@reads`, `@writes`, and `@optional`. Read/write/optional sets must be disjoint, and excluded components cannot also be required or optional.

Systems are deliberately stateless in this model. A system currently accepts exactly one `Query<...>` parameter and may additionally accept one `Commands` parameter for deferred structural changes.

```ores
@system
@reads(Health)
fnc reap(Query<Health> rows, Commands commands): void {
  return;
}
```

A `@gpu_eligible` system cannot request `Commands`.

## Effect scheduling

The compiler emits deterministic read/write effect metadata for each `@system`. Two systems conflict if either writes a component the other reads, optionally reads, or writes.

For example:

```text
movement
  R Velocity
  W Position

regenerate_health
  W Health

render
  R Position
```

`movement` and `regenerate_health` may occupy the same parallel batch. `render` must be scheduled after `movement` because of the `Position` write/read dependency.

This metadata is intentionally shaped to converge with Oreslang's broader task/effect analysis rather than creating a separate ECS-only scheduler.

## Runtime storage

`EcsWorld` uses archetypes keyed by an entity's exact component set. Each archetype contains bounded chunks. Each chunk stores:

```text
Entity[]
Position.x[]
Position.y[]
Velocity.x[]
Velocity.y[]
...
```

That is field-level Struct-of-Arrays (SoA), not an object graph and not a per-entity component map.

A query chooses matching archetypes once and then iterates dense chunk rows. There is no per-entity `has(component)` branch in the hot loop.

The current reference runtime uses Java `Object[]` field columns as a correctness substrate. Future AOT lowering can specialize those columns to primitive/native vectors without changing source semantics.

## Structural mutation

Adding/removing components or destroying an entity changes its archetype and is therefore structural mutation. Systems should queue these operations through a `Commands` buffer and apply them at a synchronization point.

The runtime command buffer:

1. records operations without changing storage;
2. preflights the complete batch against simulated component signatures;
3. applies validated operations in deterministic order;
4. migrates entities between archetype chunks with swap-remove;
5. invalidates query views after the structural barrier.

Ordinary component writes do not invalidate a query.

## Safety invariants

The reference runtime currently enforces:

- generation-checked entity identity;
- canonical component schema registration;
- exact component field sets at spawn/add time;
- non-null component field values;
- query-gated reads and writes;
- no writes through read-only/optional access;
- archetype-level query filtering;
- stale query rejection after structural mutation;
- preflighted one-shot structural command buffers;
- deterministic system conflict batching.

## Relationship to actors

Actors and ECS solve different concurrency problems.

Actors are appropriate for long-lived identity, encapsulated mutable state, asynchronous messaging, supervision, and control-oriented concurrency.

ECS is appropriate for dense homogeneous state, bulk traversal, predictable memory access, vectorization, and data-parallel execution.

An Oreslang program can use both. Actor/runtime isolation rules are not weakened by ECS.

## Current implementation boundary

This draft establishes the source declaration/effect contract, compiler metadata, chunked SoA/archetype reference runtime, structural command barrier, and tests.

The next lowering step is to make source-level `Query`, `Entity`, and `Commands` values execute directly against `EcsWorld`, then specialize component columns in AOT backends. Cross-file component-specific import syntax is also intentionally deferred rather than inferred unsafely; component references in this slice must resolve unambiguously within the compilation unit.
