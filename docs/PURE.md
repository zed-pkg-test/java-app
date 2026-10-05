# `pure` keyword and write-effect contract

Status: **implemented synchronous compiler contract**

`pure` is a reserved callable effect in Oreslang. In the current language
contract, purity means **no externally visible writes**. It is not a blanket
ban on reading outside state.

The compiler treats `pure` as an effect ceiling: every operation performed by
a pure callable, including work inside nested function expressions, must stay
within the same no-external-write guarantee.

## Syntax

Named callables may use `pure` in declaration scope:

```ores
pure fnc add(int a, int b): int {
  return a + b;
}

pub pure routine inspect(): void {
  return;
}
```

A local function expression may own an explicit pure contract:

```ores
fnc outer(): void {
  let fnc callback = pure |int value| -> {
    let int next = value + 1;
    next = next + 1;
    return;
  };
  return;
}
```

`pure`, `nlex`, and `trap` are independent function-expression modifiers
and may be combined in any order. Duplicates are rejected.

The current compiler rejects `pure async` and pure actor entry points.
Instance/class/actor methods do not yet expose `pure` as a member modifier;
an explicitly pure function expression may still be created inside a method.

## Core rule

A pure invocation may **read**:

- its parameters;
- module/file state;
- captured state visible to a lexical function expression;
- imported or otherwise statically visible values;
- locally created values.

A pure invocation may **write only invocation-local storage that is not an
alias of external state**.

Therefore these are rejected:

- reassignment of an input parameter;
- mutation through a parameter;
- mutation of module/file/global state;
- mutation of `self` or state reachable through `self`;
- mutation through a captured alias;
- mutation of an outer local from a nested function expression;
- mutation through an alias derived from external state;
- calls whose write effects are unknown or not compiler-proven pure.

Local mutation is allowed:

```ores
pure fnc increment_local(int input): int {
  let int result = input;
  result = result + 1;
  return result;
}
```

Outside reads are allowed:

```ores
define module settings
  let int current_limit = 10;

  pure fnc limit(): int {
    return current_limit;
  }
end
```

Outside writes are not:

```ores
define module settings
  let int current_limit = 10;

  pure fnc bad(): int {
    current_limit = 11; // ERROR
    return current_limit;
  }
end
```

## Nested function expressions

A nested function expression is a separate invocation boundary.

Inside a pure callable, every nested function expression must satisfy the pure
write ceiling even when the expression is not explicitly spelled `pure`:

```ores
pure fnc outer(): int {
  let int state = 0;

  let fnc callback = || -> {
    state = 1; // ERROR: writes captured state from another invocation
    return;
  };

  return state;
}
```

This prevents a pure function from manufacturing a closure that later mutates
state outside the closure invocation.

An explicit `pure` function expression gets the same analysis even when its
parent callable is impure:

```ores
fnc outer(List<int> values): void {
  let fnc callback = pure || -> {
    values[0] = 9; // ERROR
    return;
  };
  return;
}
```

`nlex` is stronger in a different dimension: it forbids the capture itself.
`pure` permits reads from captured state but rejects writes through it.

## Transitive calls

A pure callable may directly invoke only call targets whose effects are
compiler-proven pure. Named `pure fnc`/`pure routine` declarations and
locally created function expressions already checked under the pure ceiling are
accepted.

Unknown, dynamically dispatched, imported, runtime-object, or otherwise
unproven call targets fail closed until their effect metadata can be resolved.

This rule is intentionally conservative. It is safer to reject an unproven
call than to let an indirect call hide an external write.

## Interaction with `trap`

`pure` and `trap` are orthogonal:

```ores
pure trap fnc divide(int a, int b): int {
  return a / b;
}
```

A trapped failure changes control/result behavior, not the write-effect
contract. The body must still satisfy `pure`.

## What `pure` does not currently promise

`pure` does not imply:

- termination;
- absence of allocation;
- absence of reads from mutable or nondeterministic outside state;
- deterministic output;
- no exceptions;
- constant-time execution;
- memoizability;
- safe arbitrary reordering by the optimizer.

Those would require additional effect facts. The implemented contract is the
narrow, enforceable no-external-write guarantee described above.

## Compiler enforcement

The parser reserves `pure` and records it in callable/function-expression AST
metadata. The semantic pipeline runs a dedicated fail-closed write-effect pass
before ownership validation.

The checker tracks whether a binding is local, a parameter, or external, and
whether a local value aliases external state. Nested function expressions
create new callable boundaries for write analysis.

Optimization and rewriting passes must preserve the `pure` bit exactly.
