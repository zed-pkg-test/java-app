# Oreslang standard-library testing

Core-library behavior should be tested in Oreslang source, not reimplemented as
package-specific JUnit assertions.

## Layers

The standard harness is split deliberately:

1. `std/testing` owns test cases, suites, assertions, deterministic property
   cases, filtering, reporting, and failure policy in Oreslang.
2. `StdlibOresTestHarnessTest` is a small host bootstrap. It discovers
   `src/test/oreslang/stdlib/**/*.ores`, runs each program through the normal
   linked-program path, and verifies the machine-readable summary.

A new stdlib package should normally add an Oreslang test program under
`src/test/oreslang/stdlib`; it should not need a new Java test class.

## Machine protocol

Machine mode emits stable lines:

```text
ORES_TEST|PASS|suite/case|
ORES_TEST|FAIL|suite/case|detail
ORES_TEST|SKIP|suite/case|reason
ORES_TEST|SUMMARY|total=N|passed=N|failed=N|skipped=N|filtered=N
```

The host bootstrap treats absence of a summary or a non-zero failed count as a
test failure. `testing.require_success` also fails the Oreslang program so the
same suite remains useful outside JUnit.

## Determinism

Core stdlib tests are expected to be deterministic by default. Property/table
tests should provide explicit fixture cases (for example with
`check_int_cases`) so a failure is reproducible without hidden random seeds.

Scheduler/concurrency tests may use deterministic schedulers or explicit race
loops when the behavior under test is concurrency itself.

## Collection / rx-ores contract

Collection tests include the cross-library iterable invariant used by rx-ores:

- `List`, `ArrayList`, and `Vector` remain independent from rx-ores.
- each collection exposes `Symbol.iterator`;
- the iterator is a stable snapshot;
- rx-ores consumes only the iterable protocol and obtains a fresh iterator per
  cold subscription;
- one reactive demand consumes at most one iterable value;
- functional collection operations (`map`, `filter`, `reduce`) do not
  mutate the source.

This lets collection storage and reactive scheduling evolve independently while
keeping their interoperability contract executable as source tests.
