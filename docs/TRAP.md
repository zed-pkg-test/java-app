# `trap` keyword and dynamic exception-boundary contract

Status: **implemented synchronous compiler/runtime contract**

`trap` is a reserved callable effect that establishes a dynamic synchronous
exception boundary for one invocation.

The key rule is:

> A trap belongs to the invocation that owns it. It covers ordinary synchronous
> failures that unwind through that invocation, but it is not inherited by a
> function value merely because that value was created inside the trap.

## Syntax

Named callables may establish a trap in declaration scope:

```ores
trap fnc guarded_divide(int a, int b): int {
  return a / b;
}
```

A function expression may establish its own trap:

```ores
fnc make_callback(): void {
  let fnc callback = trap || -> {
    risky();
    return;
  };
  callback();
  return;
}
```

`trap`, `pure`, and `nlex` may be combined on a function expression.

The current compiler rejects `trap async` and trapped actor entry points.
Class/actor methods do not yet expose `trap` as a member modifier.

## Call result

The declared return type is the successful body return type.

At the call site, a trapped callable has a compiler-modeled two-slot result:

```text
[
  Option<success>,
  Option<Exception>
]
```

Exactly one side is populated by the current runtime wrapper.

For a successful non-`void` invocation:

```text
[Some(value), None]
```

For a trapped ordinary runtime exception:

```text
[None, Some(exception)]
```

A successful `void` invocation uses the empty tuple/unit value in the success
slot.

Example:

```ores
trap fnc divide(int a, int b): int {
  return a / b;
}

fnc use_it(): void {
  [const value, const err] = divide(10, 0);
  return;
}
```

The current implementation uses the normal tuple/Option representation in its
type and runtime lowering. A future nominal `TrapResult<T>` wrapper may make
invalid states unrepresentable at the language type level, but that is not
claimed by this revision.

## Dynamic extent

Trap is dynamic, not lexically inherited.

This is covered by the outer trap because the callback is invoked while the
outer invocation is still active:

```ores
trap fnc outer(): int {
  let fnc fail = || -> {
    return 10 / 0;
  };

  return fail();
}
```

But returning the ordinary callback does not give the callback a permanent trap
boundary:

```ores
trap fnc make_failure(): Fnc<int> {
  let fnc fail = || -> {
    return 10 / 0;
  };
  return fail;
}

// Later invocation is outside make_failure's old dynamic trap.
```

If the callback must remain trapped after it escapes, mark the function
expression itself:

```ores
fnc make_failure(): Fnc<int> {
  let fnc fail = trap || -> {
    return 10 / 0;
  };
  return fail;
}
```

This distinction is intentional. `trap` is not an inherited lexical property
like `nlex`.

## Proper tail calls

An active trap is a tail-call barrier.

Without this rule, transforming:

```ores
trap fnc outer(): int {
  return helper();
}
```

into a replacement/tail invocation of `helper` could erase the activation
that owns the trap before `helper` fails.

The runtime therefore keeps the trapped activation until its body has
successfully returned or an ordinary exception has been converted to the trap
result. Ordinary callables and function expressions remain eligible for normal
proper-tail-call lowering where no such boundary is active.

## Panic and fatal failures

The current runtime explicitly lets `OresPanic` bypass `trap`. Java
`Error`/fatal VM failures are not converted to ordinary trap results either.

The trap wrapper currently catches the runtime's ordinary
`RuntimeException` failure channel. Future first-class `throw`, `raise`,
`recover`, cancellation, and typed guest-safe error payloads must preserve the
same distinction between ordinary trappable failure and supervisory/fatal
control.

## Interaction with `pure`

`pure trap` is valid for synchronous non-actor callables. `pure` constrains
writes; `trap` constrains failure propagation. Neither effect substitutes for
the other.

## Future work deliberately not claimed here

This implementation does **not** yet claim:

- trap across `await` suspension or future completion;
- trapped actor entry points or actor supervision conversion;
- public `trap` method/interface effect contracts;
- a nominal unforgeable `TrapResult<T>`;
- a normalized guest-safe structured `TrapError` value;
- first-class `raise`/`recover` semantics;
- cancellation/fuel-exhaustion conversion.

Those features require explicit scheduler, ABI, and control-effect design. Until
then the compiler rejects combinations whose semantics would otherwise be
ambiguous.

## Compiler enforcement

The parser records `trap` on named callables and function expressions. The
type checker changes the call-expression result type to the two-slot Option
tuple. Ownership analysis models the same result shape.

The runtime installs a synchronous dynamic boundary around the body and
preserves it as a tail-call barrier. Compiler rewrites/tree shaking must
preserve the trap metadata exactly.
