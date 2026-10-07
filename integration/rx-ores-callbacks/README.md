# rx-ores-callbacks

Standalone callback-only reactive library for Oreslang.

This variant deliberately uses synchronous callbacks rather than channels,
Future/await, or a pull subscription. It stays separate from `rx-ores` and `rx-oreslang-channels` so
the execution models can be tested and benchmarked independently.

## Source model

`src/rx.ores` is a file/code unit, not an implicit module. Namespace it at the
import site:

```ores
import * as rx_callbacks from './src/rx.ores';
import class Observable from './src/rx.ores';
```

## Callback contract

`subscribe(on_next)` pushes values synchronously. `on_next` returns `true`
to continue and `false` to stop upstream immediately. That boolean is the
callback variant's backpressure/cancellation signal, so this implementation
does not need a channel, Future, scheduler hop, or pull Subscription.

Operators are callback composition: `map`, `filter`, `take`, and
`for_each`. Oreslang does not encode borrowing with pointer-like function
types: callbacks use ordinary types such as `Fnc<A, B>` and
`Fnc<T, bool>`. Calling such a callback gives it temporary read access under
the ownership checker without changing the source type. If code needs a named
borrow that lives beyond one call expression, it uses `rt borrow value`.
Independent copying and read-only shared ownership use `rt copy` and
`rt share`.

This variant intentionally stops at the callback boundary: no `Future<T>`,
`await`, Channel-backed transport, or pull subscription is part of `src/rx.ores`.
Async/Future composition belongs in the separate pull/Future `rx-ores` variant
while we measure the callback data path independently.

## Validation and benchmark

Run the pinned compiler tests with:

```sh
python3 scripts/setup.py
source .work/env.sh
python3 scripts/test.py
```

`bench/pipeline.ores` implements the same repeated logical pipeline used by
the central harness in `rx-ores/bench/benchmark.py`.


## Compiler compatibility

This pointerless callback surface requires the current-main ownership convergence
that makes first-class `Fnc<T,...>` calls temporarily read-borrow non-Copy
arguments and provides compiler-owned `rt borrow/take/copy/share`. The pinned
compiler revision is the exact head validated by the funded exact-tree carrier.
