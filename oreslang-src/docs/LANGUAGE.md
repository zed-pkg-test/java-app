# Oreslang language design (v0.6)

Oreslang is a statically typed guest language for GraalVM/Truffle. Named types are nominal by default; structural compatibility is explicit at selected boundaries. Its core invariants are explicit mutation, actor-owned mutable heaps, message-only actor communication, read-only sharing, and a stricter isolate profile for untrusted FaaS execution.

## Files, modules, and imports

A source file may contain multiple named modules. A normal module is a namespace: exported members are accessed through the module name, such as `math.add(1, 2)`.

A process-wide singleton module is declared with `define singleton module NAME as`. Its language-level contract is exactly one runtime state cell per canonical module identity per OS process, owned by a hidden serial actor/service. Every caller receives only a proxy/handle; importing or referencing the module never copies its mutable state. Canonical identity includes the defining source code-unit identity, namespace, and module name. Process-singleton access requires the `PROCESS_SINGLETON` capability.

The local JVM backend satisfies this contract only when callers share the host runtime heap. A spawned Graal isolate has a separate heap, so the current adversarial isolate profile refuses `PROCESS_SINGLETON`. A future/embedding-specific trusted supervisor coordinator must route those requests outside tenant heaps; Oreslang does not silently degrade `singleton` to one instance per isolate.

```ores
define module math as
  pub fnc add(int a, int b) => int {
    return a + b;
  }
end

define module app as
  pub fnc main() => void {
    val answer = math.add(40, 2);
    stdio.println(answer);
    return;
  }
end
```

Singleton state is actor-private. Public functions are asynchronous request/reply boundaries. In addition, a singleton module may export an immutable `val`/`const` class instance as a typed process-object proxy; the raw class instance never leaves the owner actor:

```ores
define singleton module counter as
  let int count = 0;

  pub fnc next() => int {
    count = count + 1;
    return count;
  }
end

define module app as
  pub fnc main() => void {
    val int n = await counter.next();
    stdio.println(n);
    return;
  }
end
```

```ores
define class Foo as
  val int value = 42;

  pub read() => int {
    return self.value;
  }
end

define singleton module X as
  pub val Foo f = new Foo();
end

define module app as
  pub routine main() => void {
    val int value = await X.f.read();
    stdio.println(value);
    return;
  }
end
```

Outside `X`, `X.f` is not an ordinary local `Foo`: it is a capability proxy. Its public method calls execute in the singleton owner actor and must be immediately awaited. Object fields, raw references, method extraction, and rebinding the proxy into an ordinary local are rejected. Other public singleton fields remain illegal.

Singleton state fields require explicit process-stable types because they form a process-lifetime hot-reload schema. Ordinary state initializers must be context-free: literals and pure expressions over already initialized stable singleton fields are allowed, while capability access, arbitrary function calls, `await`, mutation, and closures are not. The narrow exception is an exported immutable class-instance proxy initialized directly with a context-free `new Class(...)`. Type aliases are currently excluded from process state so redefining an alias cannot silently reinterpret an existing layout.

The exported transport surface is intentionally strict. Public singleton functions may use sendable scalar values, `Option<T>`, and `Array/List<T>` of sendable values. Until explicit `Send` constraints are part of the type system, exported singleton functions cannot be generic or `async`, cannot accept `mut` or structural parameters, and cannot transport borrows, class instances, functions/closures, or unresolved actor-local values. Classes declared inside a singleton module are actor-private helpers and cannot be accessed through the external module/class namespace.

Process-owned code also has a stricter effect boundary. Singleton functions and process-owned object methods may use their own singleton state, helper functions/classes declared in the same singleton module, and explicit calls to other singleton services. They may not depend on caller/context-local modules, ordinary helper functions, imports, `process`, `stdio`, or `print`. Exported process-object classes declared outside the singleton module are checked under the same rule. This avoids making process state or behavior depend on whichever actor/context invoked it. A future explicit `process-safe`/effect declaration can widen this surface deliberately.

Every external singleton call must be immediately awaited. Calls from one singleton function to another function in the same singleton are direct and keep the same actor-owned state, including helper method/static-function dispatch. Calls between different singleton actors use request/reply mailboxes. The runtime detects wait cycles before they can deadlock, applies caller mailbox/backpressure limits, and treats queueing time as part of the caller's wall-time budget.

A hot-reloaded generation with the same singleton field schema reuses the existing actor state while executing the new function bodies. Incompatible singleton field-schema changes are rejected until an explicit migration mechanism is provided; the runtime never silently treats old process state as a new layout. Replacing singleton code against live state requires `HOT_CODE_LOAD`; managed generations are process-monotonic and stale code cannot roll active singleton behavior backward.

## Initialization lifecycle

Oreslang has one explicit lifecycle declaration:

```ores
init routine() => void {
  // initialization work
  return;
}
```

Its scope determines its lifetime:

- **file/root scope:** once per executing actor when reached from an actor; otherwise once for the owning Graal context;
- **ordinary module:** once per executing actor when first activated by that actor; otherwise once for the owning Graal context;
- **singleton module:** once when the OS-process singleton state cell is first created.

Actor-local module state is stored on the actor cell itself and disappears when that actor dies. Non-actor/main execution uses context-lifetime storage, so repeatedly invoking the same checked source in one Graal context does not rerun ordinary init. Ordinary module state is keyed by the checked code digest; changed code gets fresh ordinary state rather than silently reinterpreting an old layout.

Module/file field initializers run first, in declaration order, and the `init routine` runs afterward. A scope may declare at most one init routine. It has no parameters, must explicitly declare `=> void`, cannot be public/async, and classes cannot declare it. Process-global initialization is intentionally not available as a free-floating file hook: it belongs inside a `singleton module`, where ownership and serialization are explicit.

Singleton-module init is deliberately deterministic and context-free: it may derive and assign singleton state from already-declared singleton values, but it cannot use ambient capabilities, arbitrary calls, `await`, object creation, or caller-local state. This prevents whichever tenant first touches the singleton from defining process-global state accidentally.

Hot reload does not rerun an already-created singleton module's process init. Compatible process state is retained; incompatible schema changes require an explicit migration.

Imports are explicit about what kind of symbol is entering the compilation unit:

```ores
import module foo from "../xyz";
import module {foo, bar} from "../xyz";
import class {x} from '../xyz';
import fnc * as funcs from '../xyz';
import * as x from './xyz';
```

Wildcard imports always require a namespace alias. This avoids silently injecting an unbounded set of names into the local scope. Import paths are part of the AST/compiler contract; filesystem/package resolution is a host build/bundling concern so strict isolates do not gain ambient filesystem access merely by using `import`.

## Module interfaces / OCaml-style module signatures

Interfaces are storage-free structural contracts. They may require public functions and public data members, but an interface data member is only a **shape requirement**: it has no initializer, allocates no storage, contributes no constructor slot, and has no object identity.

A module opts into checking with `@AdheresTo(...)`:

```ores
define module contracts as
  define interface MathApi as
    fnc add(int a, int b) => int;
    String name;
  end
end

@AdheresTo(contracts.MathApi)
define module math as
  pub fnc add(int a, int b) => int { return a + b; }
  pub val String name = "math";
end
```

Only exported (`pub`) members satisfy an adherence contract. `@AdheresTo(A, B)` may name more than one interface. Reusable stored state belongs in a trait or class, not an interface.

A `singleton module` cannot currently use ordinary `@AdheresTo`, because its external functions are asynchronous mailbox calls and exported objects are proxy capabilities rather than direct synchronous values. A dedicated service/singleton interface kind should model that surface explicitly.

## Functions and returns

Functions use `fnc` and are private by default. `pub` exports them. Return statements are always explicit; a non-`void` function must return on every control-flow path.

```ores
fnc add(int a, int b) => int {
  return a + b;
}

@Ret<int>
fnc answer() {
  return 42;
}
```

`@Ret<T>` and `=> T` are equivalent. If both are present they must agree. A function returns exactly one value; multiple logical values are represented by a tuple, array, object, class value, or another aggregate.

## Bindings

Every local binding is declared as exactly one of:

- `const`: compile-time constant; cannot be reassigned.
- `val`: runtime single-assignment binding; cannot be reassigned.
- `let`: mutable binding and the only local binding kind that may be reassigned.

```ores
const max = 10;
val request_id = process.context_id;
let retries = 0;
retries = retries + 1;
```

Destructuring carries mutability per element:

```ores
[const code, let body] = (200, "ok");
```

## Classes, receivers, multiple inheritance, interfaces, and traits

Methods omit `fnc`. Instance methods always have an implicit receiver named `self`.

```ores
define class Box<T> as
  val T value;

  @Ret<self>
  identity(take self)() {
    return self;
  }
end
```

The explicit receiver form remains available:

```ores
find(self Box)(int key) => int {
  return key;
}
```

The receiver variable name is always `self`. Receiver ownership is explicit when needed: the default/explicit `self` form is a read borrow, `mut self` is an exclusive mutable borrow, and `take self` transfers ownership into the method. A method that mutates `let` instance or trait state must opt into a mutable receiver:

```ores
increment(mut self)() => int {
  self.count = self.count + 1;
  return self.count;
}
```

A consuming method uses `take self`:

```ores
into_self(take self)() => self {
  return self;
}
```

A borrowed `self` or `mut self` may not be returned as an owned value without an explicit return-provenance contract. This prevents a method from manufacturing a second owner.

A mutable-receiver method can only be called through a mutable owner (`let`) or an existing `mut` parameter. Calling it through an immutable `val` owner or ordinary read-borrow parameter is rejected by the ownership checker. Extracting a mutable-receiver method as a bound method value is rejected in v0 because that would require a persistent exclusive lifetime tied to the captured receiver; call the method directly instead.

A class may list multiple parent classes, satisfy interfaces with `is`, and compose reusable state/behavior with `with`:

```ores
define class Combined
    extends Cacheable, Serializable
    is HasId, Named
    with Metrics, RetryState
as
end
```

`implements` and `impl` remain accepted as migration aliases for `is`; new source should use `is`.

Parent order is significant and is the deterministic v0.2 method-resolution order after child methods: the first declared parent is searched before the next parent. The static checker rejects inheritance cycles and incompatible inherited member shapes. Child members may override inherited members only with compatible types.

`Object` and `List` are extensible base classes:

```ores
define class RecordBag extends Object as
end

define class Names extends List as
end
```

Inline object and array literals are values, not classes, and cannot be inherited from.

## Inline values

Structural inline object:

```ores
val user = obj{name: "Ada", age: 37};
stdio.println(user.name);
```

Inline array:

```ores
val values = arr[10, 20, 30];
val first = values[0];
```

`arr[...]` is the canonical inline-array spelling. The original bare `[...]` literal remains accepted for source compatibility and destructuring migration.

Tuples preserve per-position static types:

```ores
val pair = (1, "one");
[const number, let label] = pair;
```

## Interfaces and stateful traits

Interfaces are storage-free contracts. They may contain function signatures and data-member requirements, but never backing storage or initializers and never have object identity.

Interface methods are **read-receiver contracts** in v0. A class or composed trait method declared with `mut self` cannot satisfy an ordinary interface method, because that would allow mutation through a shared/read view. Synchronization primitives such as a mutex may still implement read-receiver methods using compiler/runtime-recognized interior mutability; arbitrary class state may not use that as an escape hatch. An explicit mutable-interface receiver form can be added later when interface callables carry receiver ownership in their type.

```ores
define interface Named as
  String name;
end

define class User is Named as
  pub val String name = "Ada Lovelace";
end
```

Stateful reuse is a distinct construct: `trait`. A trait may contain initialized fields, concrete methods, abstract method requirements, and interface obligations. It cannot be instantiated and contributes no separate runtime object identity. Traits are not nominal runtime types: they cannot be used as parameter, field, return, alias, or generic argument types. Use an interface for a contract and a class for a value type.

```ores
define interface CounterApi as
  fnc bump() => int;
end

define trait Counter is CounterApi as
  private let int count = 0;

  pub bump(mut self)() => int {
    self.count = self.count + 1;
    return self.count;
  }
end

define class Worker with Counter as
end
```

The compiler flattens trait state and behavior into the host class before ordinary type, ownership, capability, and runtime lowering. Trait fields require initializers and are not positional constructor parameters. Private trait state remains lexical to the defining trait; host classes and foreign traits cannot reach it directly. Private trait helper methods are lexical as well: they remain callable from methods originating in that same trait but are not exposed to the consuming class.

Traits must declare their dependencies. If a trait method needs host behavior, it declares an abstract method requirement instead of silently reaching into the host class:

```ores
define trait Loads as
  pub abstract load() => int;

  pub read() => int {
    return self.load();
  }
end

define class Store with Loads as
  pub load() => int {
    return 42;
  }
end
```

Trait composition is intentionally strict:

- two traits contributing the same field are rejected;
- two concrete trait methods with the same name and arity are rejected unless the class declares a compatible resolving method;
- there is no declaration-order or “last wins” rule;
- concrete classes must implement abstract trait requirements;
- unused traits are still statically validated.

For v0, trait composition is module-local. This preserves lexical module lookup while flattening is the implementation strategy. Cross-module traits can be added later with an explicit lexical-environment/import contract.

Class interface satisfaction uses public members, including inherited and composed public members.

## Option and null

Oreslang does **not** have ambient nullable references. A bare `null` value is a compile-time error, and `null` is not a standalone variable/parameter/return type.

Optionality is explicit, using Rust-style `Some(value)` and `None`:

```ores
fnc lookup(bool found) => Option<int> {
  if found; do
    return Some(42);
  else
    return None;
  fi
}
```

`Option<T>` is a closed two-state sum type. It is never implicitly assignable to
`T`, and its payload cannot be accessed through fields, indexes, or an unchecked
`unwrap()`. Code must prove the `Some` state with an exhaustive match before the
payload is available as `T`:

```ores
fnc value_or_zero(Option<int> maybe) => int {
  match maybe {
    Some(value) => {
      return value;
    }
    None => {
      return 0;
    }
  }
}
```

An Option match has exactly one `Some(name)` arm and one `None` arm. Missing or
duplicate arms are compile-time errors. The `Some` binding is scoped only to that
arm and is statically refined to the non-null payload type `T`.

Option matching participates in pointerless ownership:

- `match maybe { ... }` reads/borrows a non-`Copy` option; the option remains
  usable after the match and a non-`Copy` payload is a read borrow inside
  `Some(...)`.
- `match borrow(maybe) { ... }` makes that read borrow explicit.
- `match take(maybe) { ... }` consumes a non-`Copy` option and gives the
  `Some` arm ownership of a non-`Copy` payload.
- `match copy(maybe) { ... }` is legal only when `Option<T>` is proven
  `Copy`. `Option<T>` is `Copy` exactly when `T` is `Copy`.
- `Some(value)` owns its payload. Constructing `Some(x)` therefore moves a
  non-`Copy` `x`; use `Some(copy(x))` only when the payload type satisfies
  the copy contract.
- Storing a borrow inside `Some(...)` is currently rejected until container
  borrow provenance is represented explicitly; this prevents a borrow from
  escaping its checked lifetime through an option.

`copy`, `take`, and `borrow` never erase the requirement to prove `Some`.
`share` remains fail-closed for ordinary values, including options, until an
explicit shared-capability type is provided.

`Option<null>` is accepted only as an explicit type-level escape hatch when an interoperability boundary truly needs to preserve a null marker. The `null` marker cannot escape that direct `Option<null>` position. `Option<void>` is invalid; use `void` when a function returns no value.

## Numbers

Built-in numeric families include integral, floating, decimal, and complex types. Imaginary literals use `i`:

```ores
const complex z = 3 + 4i;
```

Numeric widening is loss-aware; real values can widen toward complex values, but silent lossy narrowing is not performed.

## Lambdas

Lambdas use `->`:

```ores
val Fnc<int, int> inc = (int x) -> x + 1;
```

## Conditionals

`fi` is a real, distinct conditional terminator. It is not an alias for module/class `end`.

```ores
if ready, authorized | process.is_admin; do
  return serve();
elseif retryable; do
  return retry();
else
  return reject();
fi
```

Within a condition, comma means AND and `|` means OR. Comma binds more tightly.

## Exceptions and defer

Both structured exceptions and lexical `defer` are supported:

```ores
try {
  risky();
} catch (err) {
  recover(err);
} finally {
  cleanup();
}
```

`defer` executes in LIFO order when its lexical scope unwinds, including returns and exceptional exits.

## Async / await

`async` and `await` are reserved and parsed. `await` unwraps future-like runtime values. The scheduler is intentionally separate from the language surface so actor isolation does not depend on a specific OS-thread implementation.

## Actors

Actors own their mutable heaps. Cross-actor communication occurs through mailboxes, and message values are frozen/copied/serialized at the runtime boundary. Arbitrary mutable host objects are rejected as messages. Deeply immutable values may use read-only sharing.

A `singleton module` is a language-level process service built on the same ownership rule: one actor owns the module bindings for the entire OS process, while all other actors/isolates communicate with it through generated/runtime proxies. It is not one singleton per isolate or per Graal context.

## Isolates

An isolate is stricter than an actor and is intended as a FaaS/tenant security boundary. Strict isolate contexts deny host reflection, native access, arbitrary filesystem/IO, child-process creation, guest-created threads, environment access, and unrestricted polyglot access unless an explicit capability is granted by the host.

Actors may run inside an isolate. Actor semantics never weaken isolate policy.

## Built-in globals

`process` is an Oreslang runtime descriptor/capability facade, not unrestricted OS process access. `stdio` is capability-scoped standard IO. `print(value)` is shorthand for the output facade.

## Compiler pipeline

1. UTF-8 source -> lexer.
2. lexer -> parser / AST.
3. imports and declarations are collected without executing user code.
4. generic, structural, module-interface, inheritance, mutability, and return-flow checks run.
5. actor/isolate sendability constraints are enforced at relevant runtime boundaries.
6. checked source is lowered/executed as Truffle guest code.

The parser and static checker execute no user code.


## File-level entrypoints

Named modules remain the normal namespace unit, but a source file may also contain file-level callables such as an entrypoint. The compiler places those declarations in an internal file-root namespace; that namespace is not written by user code.

```ores
define module x as
  define class y as
  end
end

pub routine main() => void {
  val y = new x.y();
  stdio.stdout.write(y)
}
```

Qualified names such as `x.y` retain their module namespace.

## `fnc` versus `routine`

`fnc` is the recursive/function form. It may participate in recursive call graphs. Tail-position calls from `fnc` are optimization-eligible, but v0.3 deliberately does **not** promise that every recursive `fnc` executes in constant stack space yet.

`routine` is the non-recursive procedural form:

```ores
pub routine main() => void {
  run_app();
}
```

The static checker rejects direct or indirect call cycles that contain a routine. Routines are not tail-call-optimization targets. This makes entrypoints, orchestration steps, and lifecycle procedures explicit.

Lambdas may recurse when their binding supplies an explicit function type so the closure's own signature is available while its body is checked:

```ores
let Fnc<int, int> fact = |int n| -> {
  return n == 0 ? 1 : n * fact(n - 1);
};
```

## Semicolons

Semicolons are strongly recommended. They remain the canonical formatter output.

They may be omitted only where the parser has an unambiguous structural boundary, such as the final expression immediately before `}`, `fi`, or `end`. Oreslang does not use broad JavaScript-style automatic semicolon insertion.

```ores
pub routine main() => void {
  stdio.stdout.write("done")
}
```

## Nominal typing and opt-in structural parameters

Named classes and interfaces are nominal by default. Structural matching at an API boundary is explicit with `@Structural`:

```ores
pub interface Brand {
  markerBrand: 'marking/branding'
}

fnc consume(@Structural Brand value) => String {
  return value.markerBrand;
}
```

A value does not need to nominally implement `Brand` for that parameter, but its public/static shape must satisfy the interface. Without `@Structural`, the normal nominal implementation/inheritance rules apply.

Structural parameters are read-only views. Until structural callable types carry ownership modes, a structural interface/class may expose only methods with read-only receivers and ordinary borrow parameters. Public `mut self`, `mut` parameters, or `take` parameters make that type ineligible for `@Structural` use; the compiler rejects the view rather than erasing the ownership contract.

Interfaces may inherit from other interfaces and support literal-string marker fields:

```ores
pub interface Bar {
  markerBrand: 'marking/branding'
}

pub interface Foo extends Bar {
}
```

Explicit `implements` and module `@AdheresTo(...)` checks remain structural conformance proofs.

## Method overloads

Only methods overload, and only by arity:

```ores
define class Lookup as
  find() => Option<int> {
    return None;
  }

  find(int id) => Option<int> {
    return Some(id);
  }
end
```

Two methods with the same name and same arity are a compile-time error even when their parameter types differ. Top-level/module `fnc` and `routine` declarations never overload.

## Ternary expressions

The ternary operator is right-associative and lazy in its selected branch:

```ores
fnc find(bool found) => Option<int> {
  return found ? Some(42) : None;
}
```

## Loops, iterators, and scheduler safepoints

Oreslang supports conventional imperative loops:

```ores
for (let i = 0; i < 10; i = i + 1) {
  work(i);
}
```

and iterator-style loops:

```ores
for (val item of values) {
  work(item);
}
```

Classes can expose a JavaScript-like iterator symbol:

```ores
define class Bag as
  [Symbol.iterator]() => Array<int> {
    return arr[1, 2, 3];
  }
end
```

The compiler/runtime inserts a scheduler safepoint on **every loop iteration**. The current runtime hook checks cancellation/interruption and yields execution; it is intentionally centralized so actor supervisor/control-mailbox polling can evolve without changing source syntax. User code does not receive ambient thread-control capability.

This means Oreslang does not require recursion as the only way to loop, while still giving actor/isolate schedulers a compulsory cooperation point inside generated loop execution.

## Standard output

In addition to `stdio.print` and `stdio.println`, the stream-shaped form is available:

```ores
stdio.stdout.write(value);
stdio.stdout.println(value);
```


## Execution profiles: JIT, AOT, and hybrid

The same Oreslang source model supports three deployment profiles:

- **JIT** — normal GraalVM/JVM host with Truffle JIT available.
- **AOT** — Native Image host with the Truffle interpreter retained and guest JIT disabled. This is the conservative mobile/FaaS profile and still supports source hot reload because new Oreslang source is data consumed by the precompiled interpreter.
- **HYBRID** — Native Image host plus Truffle guest JIT on targets where executable-code generation is permitted.

The CLI accepts `--mode=jit|aot|hybrid` and `--platform=server|windows|macos|linux|android|ios`. The iOS execution contract is intentionally AOT-only. Source hot reload does not depend on executable dynamic libraries, JNI, or NFI.

Maven profiles:
- `mvn -Pnative-aot -DskipTests package`
- `mvn -Pnative-hybrid -DskipTests package`

## Capability-secure isolates

Security is layered. Oreslang uses a deny-by-default language capability policy **in addition to** Graal/Native Image isolation and the host OS/mobile sandbox.

An isolate policy can independently allow or deny:

`STDIN`, `STDOUT`, `PROCESS_INFO`, `ACTOR_SHARE_READONLY`, `NETWORK`, `FILESYSTEM_READ`, `FILESYSTEM_WRITE`, `ENVIRONMENT`, `HOT_CODE_LOAD`, `FFI`, `NATIVE`, `REFLECTION`, `CHILD_PROCESS`, `THREAD_CREATE`, and `POLYGLOT`.

The trusted compiler API can reject forbidden API usage before execution:

```java
OresCompiler.validateForIsolate(source, policy);
```

Runtime facades perform the same check again. A source file therefore cannot grant itself a capability. The launcher/supervisor chooses policy.

The strict FaaS baseline permits only stdout. Host reflection, native access, unrestricted polyglot access, environment access, guest-created threads, and host IO remain disabled at the Graal context boundary.

Actor cells may receive a policy stricter than their parent runtime. Their mailbox capacity is also bounded by that policy.

## Hot reload without FFI

`HotReloadManager` loads each code revision into a new versioned Polyglot context/generation:

1. source arrives as data;
2. syntax/type/capability checks run;
3. a fresh restricted guest context is created;
4. the validated generation is staged and atomically becomes active without executing guest code;
5. the supervisor explicitly starts the generation when its actor/request boundary is ready;
6. the previous generation may remain alive while requests/actors drain;
7. the supervisor explicitly retires it.

Each generation receives a monotonically increasing id and SHA-256 source digest.

This model does not require `dlopen`, `LoadLibrary`, JNI, or Truffle NFI. A production server may additionally map each context to a Graal polyglot/native isolate. On AOT-only targets the precompiled interpreter executes newly loaded Oreslang source; on JIT-capable targets the same source may warm into optimized machine code.

## Explicit structural calls

Structural compatibility is never silently enabled for a nominal parameter. These three spellings are equivalent:

```ores
pub interface Bar {
  marker: 'brand'
}

pub interface Foo extends Bar {
  markerBrand: 'marking/branding'
}

fnc a(@Structural Foo y) => void {
  return;
}

fnc b(y structural Foo) => void {
  return;
}

@AllowStructural(y)
fnc c(y Foo) => void {
  return;
}
```

All three may accept:

```ores
val branded = obj{
  marker: "brand",
  markerBrand: "marking/branding"
};

a(branded);
b(branded);
c(branded);
```

Without one of those explicit structural opt-ins, passing that object to a nominal `Foo` parameter is a compile-time error.

`structural` is a contextual keyword, so existing identifiers named `structural` remain legal elsewhere.

## Receiver identity and method values

`self` is injected by the compiler/runtime as an immutable receiver binding. It cannot be declared as a local parameter name or reassigned.

Direct method calls do not create per-instance closures:

```ores
box.get();
```

The runtime resolves the shared class method definition and passes the receiver as the hidden first argument.

When a method is extracted as a first-class value:

```ores
val Fnc<int> callback = box.get;
```

Oreslang creates a small bound-method value containing only the receiver plus method identity. The underlying method definition remains shared by every instance. Calling `callback()` always uses the original `box`; there is no JavaScript-style dynamic `this` rebinding.


## Incremental compilation and code units

Oreslang's canonical compiler output is **decomposable**. A monolithic native executable is a packaging choice, not the semantic compilation unit.

Each source file is a separately versioned **code unit**:

- source digest;
- checked AST / future serialized Ores IR;
- explicit import dependencies;
- package identity;
- zero or more flat modules;
- optional flat source namespace.

With no explicit namespace, the file/code-unit identity is its default package identity. An explicit namespace is written once at the top of the file:

```ores
namespace payments;

import fnc {authorize} from "./auth.ores";

pub fnc charge() => void {
  return;
}
```

Namespaces are flat. `namespace company.payments;` is illegal. Modules are also flat: a module name is one identifier and a module may not contain another module.

The incremental compiler uses separate **source** and **ABI** digests:

1. hash every source unit;
2. derive a deterministic exported ABI digest from public functions/bindings, class public members and static functions, interfaces, type aliases, inheritance, structural markers, and module adherence contracts;
3. rebuild a unit whenever its source or resolved dependency set changes;
4. when a dependency ABI digest changes, invalidate the transitive reverse-import closure conservatively;
5. reuse every importer artifact across implementation-only dependency edits;
6. keep unchanged/unaffected compiled-unit objects intact.

Public inferred bindings are fingerprinted conservatively from their initializer AST until the compiler materializes their inferred exported type in the unit manifest.

This separates code-generation dirtiness from public-contract dirtiness. An implementation edit such as changing a function body from `return 42;` to `return 43;` recompiles that file without recompiling importers when the exported signature is unchanged. Until the cross-unit linker records exact public imported-symbol dependencies, ABI changes intentionally propagate transitively for correctness.

Actors and isolates consume versioned code-unit generations. `HotReloadManager` tracks the active generation **per code-unit id**, so staging `worker.ores` does not replace the active `helper.ores` generation. A hot reload therefore does **not** require rebuilding or replacing every actor: changed units receive new generations, unchanged units remain active/shared, and supervisors migrate actors/requests according to policy.

A deployment may still aggregate many code units into one Native Image for startup/distribution reasons. That aggregate is never the only compiler artifact and must not erase per-unit identities or dependency metadata.

## Static class functions

Instance methods continue to omit `fnc`:

```ores
define class Counter as
  read() => int {
    return self.value;
  }
end
```

Class-level functions are not methods. They are declared with the explicit `static fnc` form:

```ores
define class Counter as
  pub static fnc twice(int value) => int {
    return value * 2;
  }
end

val doubled = Counter.twice(21);
```

A static class function:

- is resolved through the class namespace;
- has no implicit or explicit `self`;
- cannot be invoked through an instance;
- may be extracted as a function value from the class namespace;
- has one shared definition, just like any other named function.

Static data fields are intentionally not part of v0.5 yet; `static` on a class binding is rejected rather than silently acquiring Java-like global mutable state semantics.

## Function types, functors, and arrows

The arrows have distinct jobs:

- `=>` declares the return type of a **named callable**.
- `->` forms a **function type** or **lambda**.

Function aliases can use `typeof fnc`:

```ores
type F = typeof fnc() -> int;
type Predicate = typeof fnc(bool value) -> bool;
```

The shorter inline function type is also valid:

```ores
fnc sink() => ((bool foo) -> void) {
  return |foo| -> {
    stdio.println(foo);
    return;
  };
}
```

Parameter names inside function types are documentation-only; structural function compatibility is determined by parameter/result types.

The canonical lambda syntax is pipe-delimited and block-only:

```ores
fnc find(bool found) => F {
  return || -> {
    return found ? 5 : 6;
  };
}

fnc callback() => ((bool foo) -> void) {
  return |foo| -> {
    stdio.println(foo);
    return;
  };
}
```

Lambda parameters may be inferred from a contextual function type (`|foo|`) or typed explicitly (`|bool foo|`).

There are no expression-body lambdas. Every lambda has braces. When the contextual result type is non-void, every control-flow path must contain an explicit `return <value>;`. Void lambdas may use `return;`.

This means higher-order functions and functors do not introduce a second return convention: named functions, methods, static functions, and anonymous functions all use the same explicit `return` statement semantics.


## Lexical closures

Closures are lexical. A lambda resolves free variables from the scope where the lambda is created, not from the scope where it is called.

```ores
fnc makeCounter() => (() -> int) {
  let int count = 0;

  return || -> {
    count = count + 1;
    return count;
  };
}
```

The returned closure owns the captured lexical environment, so repeated calls observe the same captured `count`.

Capture rules are ownership-aware:

- immutable `Copy` captures are copied into the closure environment;
- non-`Copy` captures transfer ownership into the closure;
- a capture that the closure mutates also transfers the mutable lexical slot into the closure;
- after a move-only/mutable capture is transferred, the outer binding cannot be used;
- an already borrowed value may not be captured by an escaping closure; pass the borrow as a lambda parameter or capture the owner by value.

This makes returned closures safe without retaining raw stack references.

## Parameter ownership: borrow, `mut`, and `take`

Oreslang deliberately does **not** expose pointer/reference syntax such as `&T`, `&mut T`, or unary `&value`. JVM references remain an implementation detail. The compiler tracks ownership, aliasing, and lifetimes semantically.

An ordinary parameter is a read-only temporary borrow:

```ores
fnc inspect(Bar value) => void {
  stdio.println(value.foo);
  return;
}

fnc example() => void {
  let Bar b = new Bar();
  inspect(b);
  stdio.println(b.foo); // b was borrowed, not moved
  return;
}
```

A `mut` parameter is an exclusive temporary mutable borrow:

```ores
fnc change(mut Bar value) => void {
  value.foo = "changed";
  return;
}

fnc example() => void {
  let Bar b = new Bar();
  change(b);
  stdio.println(b.foo); // exclusive borrow ended at call boundary
  return;
}
```

A contextual `take` parameter transfers ownership:

```ores
fnc save(take Bar value) => void {
  return;
}

fnc example() => void {
  let Bar b = new Bar();
  save(b);
  // b.foo; // compile-time error: b was moved
  return;
}
```

`take` is contextual in parameter position rather than a globally reserved identifier. The compiler ABI nevertheless records whether each parameter is `borrow`, `mut`, or `take`, so changing ownership policy is an API/ABI change.

Parameter bindings themselves are immutable. `mut Bar value` grants exclusive mutation *through* `value`; it does not permit rebinding the parameter. If a function takes ownership and wants a mutable local owner, move it into a `let` binding:

```ores
fnc normalize(take Bar value) => Bar {
  let Bar owned = value;
  owned.foo = "normalized";
  return owned;
}
```

Local mutation continues to use `let`. `val` and `const` remain immutable. Class fields declared `val`/`const` cannot be assigned after construction; mutable object state must use a `let` field and mutable owner access.

## Ownership, moves, and borrows

Oreslang uses affine ownership and borrow checking while keeping source syntax Java-like.

The initial implicit `Copy` family is:

- integer types;
- floating/decimal/complex scalar types;
- booleans;
- immutable strings.

Class instances, arrays/lists, object records, and closures are move-only by default.

The compiler intrinsics are function-like syntax, not pointer operators:

- `borrow(x)` stores an immutable view tied to `x`'s lexical lifetime;
- `take(x)` explicitly moves an owned value;
- `copy(x)` creates another owned value only when the type is proven `Copy`;
- `share(x)` is reserved for explicit shared-capability types and cannot turn ordinary mutable state into shared state.

These four names are unshadowable in the value/callable namespace. They are not lexer keywords—so the parser can issue precise diagnostics and future syntax can evolve—but user bindings, parameters, imports, and top-level/module callables cannot redefine their meaning.

Borrowed values cannot currently be returned as owned values. A return of `self`, an ordinary non-`Copy` parameter, a `mut` parameter, or `borrow(x)` is rejected unless ownership is actually transferred. The planned provenance form is `T from owner`; until that contract exists, borrowed returns fail closed rather than manufacturing a second owner.

First-class `Fnc` values currently encode parameter types but not ownership modes. Therefore a callable or lambda containing a `mut` or `take` parameter—and a method with a `mut self` receiver—is direct-call-only in v0. Extraction is rejected until `Fnc` carries those modes explicitly.

For now, classes are **not** implicitly copyable. `copy(classValue)` is rejected until the class-copy contract proves how to create independent owned storage. This prevents a shallow JVM reference copy from pretending to satisfy language-level copy semantics.

Stored immutable views use `borrow(x)`:

```ores
fnc example() => void {
  let Bar b = new Bar();
  val first = borrow(b);
  val second = borrow(b);
  stdio.println(first.foo);
  stdio.println(second.foo);
  return;
}
```

Borrow rules are compiler guarantees:

- any number of read borrows may coexist;
- a `mut` borrow is exclusive;
- mutation or movement of the owner is forbidden while any stored borrow is active;
- reading the owner is forbidden while an exclusive mutable borrow is active;
- `mut` borrowing requires a mutable owner;
- a borrow of a local value may not escape the owner's lifetime;
- temporary parameter borrows end at the call boundary;
- multiple arguments in one call are checked as overlapping for the entire call, so `f(mut x, mut x)` and `f(mut x, x)` are rejected;
- stored `borrow(x)` bindings remain active until their lexical scope ends;
- structural parameters are read-only views and never consume the supplied value.

The checker is deliberately conservative around uncertain branch/loop lifetimes. Future non-lexical-lifetime analysis may accept more programs, but it must not weaken these invariants.

## Multi-threaded targets

Actors/isolate message passing remains the primary concurrency model, but the ownership contract is backend-independent.

The same compiled program can target a secondary multi-threaded runtime because:

- mutable state has one owner unless temporarily accessed through an exclusive `mut` parameter/receiver;
- shared aliases are immutable;
- move-only values cannot remain accessible from both sides of an ownership transfer;
- closures cannot smuggle an outstanding stack borrow into a longer-lived task;
- actor messages continue to cross actor boundaries only through the existing frozen/sendable contract.

When explicit thread/task spawning is added, cross-thread transfer will require move semantics and a `Send`-equivalent capability; shared cross-thread references will additionally require a `Sync`-equivalent guarantee. Those marker traits are intentionally a future surface feature—the current source language has no ambient raw-thread API, so there is no unchecked escape hatch to bypass ownership.


## Garbage collection, `actor.gc()`, `process.gc()`, and inferred lifetimes

Oreslang is ownership-first rather than tracing-GC-first. Most guest values
naturally become unreachable when their owner/lifetime ends; JVM/Graal GC
reclaims the backing heap automatically.

The initial language does not require explicit lifetime annotations. The
compiler infers lexical, actor, context, and process-singleton lifetimes and
rejects borrows that may escape their owners. Named lifetime syntax remains
reserved for future generic APIs where return-borrow provenance cannot be
inferred safely.

```ores
process.gc(); // context cleanup + actor self-clean signal + rate-limited host hint
actor.gc();   // current actor only
```

These calls cannot make an unsafe program safe and never bypass ownership,
borrow, sendability, or actor-isolation checks.

A private/isolate actor cannot receive `Shared<T>`; an ordinary message is
copied/frozen at its mailbox boundary. A shared actor may receive explicitly
deeply immutable shared values, but ordinary mutable actor state still has one
actor owner. "Shared actor" therefore means shared address-space/runtime
placement, not shared mutable object graphs.
