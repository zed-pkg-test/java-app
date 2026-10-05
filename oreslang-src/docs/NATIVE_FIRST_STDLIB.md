# Native-first Oreslang standard library

Oreslang should keep the guest-visible standard library in Oreslang wherever the
operation can be expressed safely and efficiently in the language itself.

## Boundary

The VM/native layer owns mechanisms that require runtime authority:

- allocation and raw contiguous storage primitives such as `Array<T>`
- scheduler, actor, isolate, GC, atomics, locks, and safepoints
- operating-system I/O, sockets, threads, clocks, entropy, and process services
- FFI/JNI/Truffle adaptation and host-object marshalling
- compiler, parser, type-checker, linker, and AOT integration

The Oreslang standard library owns reusable language semantics:

- collection APIs and algorithms
- iteration and transformation policy
- ordering, replacement, uniqueness, and empty-value behavior
- numeric/string helpers that need no privileged runtime operation
- Option/Result/Future/Iterator/Stream combinators where the runtime primitive
  can remain narrow
- formatting, parsing, and other deterministic helpers that can be implemented
  without host authority

A Java helper should not exist merely because Java already has a library for the
same operation. Java is an implementation boundary, not the semantic owner of
ordinary Oreslang APIs.

## Collections

`std/collections` is the canonical Oreslang-owned collection module.

The initial implementation provides:

- `List<T>`, `ArrayList<T>`, and `Vector<T>`
- `Stack<T>`, `Queue<T>`, `Deque<T>`, and `ArrayDeque<T>`
- `Map<K,V>` and `OrderedMap<K,V>`
- `Set<T>`

These classes build on native `Array<T>` storage. Operations such as growth
policy at the public API level, contains/index lookup, stack/queue/deque
behavior, map replacement/removal, insertion ordering, set uniqueness,
iteration snapshots, and functional sequence operators are implemented in
Oreslang.

`Queue<T>` and `Deque<T>` use two-array/two-stack algorithms so end
operations are amortized O(1) rather than repeatedly shifting a contiguous
array.

The first `Map<K,V>` is intentionally insertion-ordered and uses linear key
search. It must not be labeled `HashMap` until Oreslang has a stable hashing
contract (for example a `Hashable` protocol plus a VM-level hash primitive
whose equality/hash invariants are specified). A later hashed storage backend
may optimize `Map` or introduce `HashMap` without moving public semantics
back into Java.

## Compatibility and migration

The unqualified native `List`/`Map` builtins from the early runtime remain a
transitional compatibility surface while existing code migrates. New standard
library work should prefer explicit module-qualified types, for example:

```ores
import module collections from "std/collections";

let values = new collections.ArrayList<int>();
let index = new collections.Map<string, int>();
```

The long-term target is to reduce the Java/native collection surface to storage
and low-level operations required by Oreslang implementations, then remove or
deprecate duplicate guest-visible policy from Java after compatibility gates
are satisfied.

## AOT and packaging

`std/*` is a compiler-owned, read-only namespace. Bundled `.ores` units are
resolved into the normal compilation graph, participate in ABI hashing and
dependency ordering, and are retained as GraalVM Native Image resources. Guest
source cannot shadow or traverse out of the reserved stdlib namespace.

This keeps JIT, AOT, and hybrid execution on the same Oreslang library source
rather than maintaining separate Java implementations for each mode.
