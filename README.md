# rx-ores

Standalone pull/Future-oriented reactive streams for Oreslang.

This repository is the home of rx-ores. The compiler/runtime owns generic
language primitives such as `Future<T>`, `await`, callables, ownership and
scheduling; it does **not** own the reactive library.

## Source model

`src/rx.ores` is a file/code unit, not an implicit module or package:

```ores
import * as rx from './src/rx.ores';
import class Observable, Subscription from './src/rx.ores';
```

The initial implementation is demand-driven. `subscribe()` creates independent
subscription state and each `next()` admits at most one item. There is no
prefetch queue. `take(0)` consumes nothing and cancellation is idempotent.

RX APIs use Oreslang's pointerless ownership model. Callback signatures use
ordinary managed value/reference types such as `Fnc<T, bool>`; they never encode
ownership with `&T`, `T*`, unary `&`, or unary `*`. When this library needs
to make a temporary read borrow explicit, it uses `rt borrow value`. Explicit
ownership transitions belong to the reserved runtime surface (`rt copy`,
`rt share`, `rt borrow`, `rt take`, and related runtime reference
operations), not C/Rust-style pointer operators.

Finite pull state is stored directly in subscription objects. `from_future()`
uses Oreslang's native Future/await suspension path; it does not invent a second
callback scheduler. Native Future values may be prepared first with `map`,
`compose` / `flatMap`, and `onSuccess`; guest callbacks resume as Ores
scheduler/actor turns rather than producer-thread callbacks. Higher-order
operators currently include `map`, `filter` and `take`.

This repository intentionally stays separate from:
- **rx-ores-callbacks** — synchronous push/callback composition.
- **rx-oreslang-channels** — pull semantics whose coordination state and hot
  sources use Channel/select.

The separation is deliberate so identical workloads can measure the costs and
strengths of each execution model before any hybrid/high-performance library is
designed.

## Validation

```sh
python3 scripts/setup.py
source .work/env.sh
python3 scripts/test.py
```

The benchmark driver lives in `bench/benchmark.py`; `bench/pipeline.ores`
is this implementation's common map/filter/take workload.
