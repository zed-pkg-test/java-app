# GPU actor mapping

Oreslang borrows a design principle from Regent/Legion: **prove logical independence in the language/runtime, then let a mapper choose physical hardware placement**.

## Invariants

1. Actor identity is never a CPU thread, GPU lane, warp, wavefront, SM, CU, or device ordinal.
2. One actor still processes at most one mailbox turn at a time.
3. Different actors may have turns in flight concurrently when isolation/effect checks prove their inputs do not conflict.
4. A `gpu` actor turn never falls back to the CPU evaluator.
5. Host/device transfer remains explicit for GPU-resident resources.
6. Physical placement is a backend mapping decision constrained by a portable logical request.

## Mapping model

The GPU runtime carries a `DispatchContext` with the execution class, actor UUID/kind when applicable, and optional device, logical partition, and affinity hints.

A backend can use this to hash actors across GPUs, keep an actor on one device for locality, assign actor groups to separate partitions or streams, fuse independent turns into an index-space launch, or map partitions onto MIG instances and other backend-specific resources.

The language must not promise a stable physical GPU core number. "Separate cores" is modeled as distinct logical partitions or affinity domains and lowered by the backend to the strongest hardware parallelism/isolation it can actually provide.

## Regent concepts translated to Oreslang

| Regent / Legion | Oreslang analogue |
| --- | --- |
| task | actor mailbox turn / gpu callable |
| logical region | actor-confined state or GPU resource handle |
| read/write privileges | borrow/share/take + actor isolation + effect checking |
| disjoint partition | isolated actor memory / disjoint GPU data partition |
| index-space launch | parallel batch of independent actor turns |
| mapper | GPU backend placement policy |
| physical instance | device allocation / GpuArray backing storage |

The key borrowing is the split between correctness and placement: Oreslang proves safety from ownership, actor isolation, and effects; the mapper optimizes device placement without changing those semantics.

## Current surface

```ores
pub gpu isoactor fnc score(GpuArray<f32> features) => f32 {
  return reduce_score(features);
}
```

The actor turn is admitted and isolated first. GPU dispatch then receives that actor's UUID and a portable placement request.

## Planned surface

Device and partition controls should be explicit portable hints rather than core pinning:

```ores
gpu(device = 1) isoactor fnc score(...) => f32 { ... }
gpu(partition = shard_id) isoactor fnc score(...) => f32 { ... }
```

Group-level mapping can later target actor collections:

```ores
val workers = ActorGroup.of(a, b, c, d);
workers.gpu.parallel(partition_by = actor.id);
```

These spellings are design targets, not accepted syntax yet.

## Persistent GPU actors

Persistent actor classes require a stable device-side state layout, device-state ownership, mailbox-to-kernel lowering, checkpoint/migration rules, device-reset failure semantics, per-actor/group GPU-memory quotas, and strict rules preventing implicit mutable host/device sharing. Until those exist, only GPU actor callables are admitted.
