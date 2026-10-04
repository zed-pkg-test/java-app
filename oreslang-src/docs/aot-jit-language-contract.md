# Oreslang AOT/JIT declaration contract

Oreslang uses one source-language semantics for AOT, JIT, and hybrid execution.

The compatibility rule is:

> AOT defines the semantic floor. JIT may optimize or specialize a declaration
> that the compiler already knows, but JIT may not create a new language-level
> declaration identity that would not exist in a closed-world AOT compilation.

This is deliberately stricter than a dynamic JVM language. Oreslang does not
use runtime bytecode generation, runtime class loaders, metaclasses, `eval`-as-
a-type-definition mechanism, or mutable runtime type registries as part of the
language semantics.

## Static declaration identities

The following identities are compile-time/static:

- namespace
- module
- class
- trait
- trait implementation / impl
- interface
- struct
- type alias
- actor type

Loading a source/code generation may install a compiler-produced set of those
identities, but executing ordinary Oreslang code cannot manufacture another
identity.

Hot loading is therefore code-generation loading, not dynamic declaration
construction. Each generation has a finite declaration table before guest
execution begins.

## Namespace

A namespace is file metadata used for qualification and collision avoidance.

- declaration site: source-file header only;
- no nesting/dotted runtime namespaces;
- no namespace values;
- no runtime namespace creation or mutation.

## Module

A module is a static symbol/declaration container.

- declaration site: file/top level;
- its exported member set is known before execution;
- importing/linking a module does not execute module initialization code;
- modules are not first-class mutable namespace objects;
- no `new Module`, module factories, or runtime module injection.

Module constants may exist *inside* the module declaration because they belong
to that statically known module. Arbitrary declarations after a closed module
remain subject to ordinary file-scope rules and never become import-time code.

## Class

A class is a nominal, compiler-known reference type.

- declaration site: file/module declaration scope;
- no class declaration inside executable blocks;
- no runtime subclass generation, metaclasses, proxy-class synthesis, or
  ClassLoader-style definition;
- vtables/interface tables are build-time/link-time data;
- JIT may devirtualize, inline, clone, or specialize methods, but these
  optimizations do not create a new Oreslang class identity.

Framework behavior that Java often implements with Byte Buddy/CGLIB-style
runtime classes must be implemented with static generation, composition,
explicit wrappers, interfaces/traits, or build-time compiler transforms.

## Struct

A struct is a closed-shape value type.

Oreslang should support two declaration placements:

1. file/module declaration scope; and
2. lexical block/function/method scope.

A lexical struct is still **compile-time**, not runtime. The compiler assigns it
a stable lexical identity such as `<code-unit>::<callable>::<block-site>::R`
(or an equivalent internal symbol), computes its complete field layout before
execution, and may lower/name-mangle/hoist it for AOT.

Example:

```ores
pub fnc foo() : R {
  struct R {
    foo: string;
    bar: string;
  }

  return R{foo: "x", bar: "y"};
}
```

The declaration executes zero code. Calling `foo()` one million times creates
values of one statically declared `R`; it does not create one million types.

Struct field sets are closed. Open-ended key insertion belongs to
`Map<K,V>`, not to a dynamic struct facility.

## Interface

An interface is a compiler-known contract with no mutable runtime declaration
object.

Interfaces may be:

1. file/module scoped; or
2. lexical/local declarations inside functions/methods/blocks.

A lexical interface is visible only in its lexical scope and is resolved during
type checking. AOT may erase it completely or emit a fixed dispatch/shape
descriptor. Runtime execution does not create an interface object or register a
new interface.

Structural checking remains explicit where Oreslang permits it; structural
matching does not imply dynamic interface creation.

## Trait

Traits should be more restrictive than interfaces initially.

A trait may define reusable behavior/default methods and may participate in
implementation selection/coherence. Therefore:

- trait declarations are file/module declaration-scope only;
- `impl` declarations are file/module declaration-scope only;
- the complete trait/impl relation for a code generation is frozen before guest
  execution;
- no runtime trait creation or runtime impl registration;
- no monkey-patching an implementation table.

This makes trait coherence, method-table generation, AOT reachability, and
cross-code-unit diagnostics deterministic.

A future *local sealed trait* feature could be considered, but only if every
implementation is lexical, closed, non-exporting, and compiler-enumerable. It
must not weaken the closed-world rule.

## Dynamic dispatch is still allowed

AOT compatibility does **not** require every call to be statically dispatched.

Oreslang may use:

- class virtual dispatch;
- interface dispatch;
- trait-object/vtable dispatch;
- actor/message dispatch;
- function values/closures.

The requirement is that the possible type/implementation identities come from
compiler-known declarations. JIT may optimize dispatch using profiling; AOT may
emit conservative dispatch tables. The observable language semantics remain the
same.

## Method overloading by arity

Class callable overloading is closed-world and based **only on arity**.

The canonical selector inside one owner class is:

```text
(dispatch_kind, method_name, parameter_count)
```

where `dispatch_kind` is either instance or static.

Examples:

```text
instance$read$arity0
instance$read$arity1
instance$read$arity2
static$read$arity0
```

These are distinct compile/link slots. Parameter runtime types are never used
to select an overload. Therefore these declarations are illegal because they
occupy the same slot:

```ores
pub parse(int value) => int { ... }
pub parse(String value) => String { ... } // illegal: same name + arity
```

Likewise, changing only generic arity does not create another overload:

```ores
pub parse<T>(T value) => T { ... }
pub parse<T, U>(T value) => T { ... } // illegal: same runtime call arity
```

Static and instance callables have separate selector namespaces, so a class may
have both an instance `read()` and a `static fnc read()`.

Interfaces use the same name+arity slot model. A class implementing an
interface with `read()`, `read(x)`, and `read(x, y)` must provide those
three statically enumerable slots.

Inheritance preserves the slot. A child declaration with the same selector
overrides/hides the inherited target; a different arity adds a different slot.
With multiple inheritance, if two distinct parent implementations contribute
the same selector and the child does not provide an explicit local definition,
the class is rejected as ambiguous. An ordinary diamond that reaches the exact
same original method declaration through two paths remains one slot.

This is directly AOT-compatible:

- the call-site arity is syntax-known;
- an AOT compiler can lower a direct/static call to one symbol immediately;
- virtual calls can use a precomputed vtable slot keyed by the selector;
- a JIT can inline/cache that same slot without changing resolution semantics;
- no runtime type inspection, reflection, dynamic class generation, or overload
  search by parameter value type is required.

An overloaded bound method cannot be extracted as an untyped first-class value,
because doing so would discard the arity needed to select the slot. It must be
called directly (or later be explicitly disambiguated by a function type if the
language adds that feature).

## JIT versus AOT

JIT-only optimizations may include:

- speculative inlining;
- polymorphic inline caches;
- profile-guided specialization;
- escape analysis;
- devirtualization;
- optimized object/struct layouts when ABI-equivalent;
- compiled hot paths replacing interpreted paths.

They must be deoptimizable or semantically equivalent and may not expose new
syntax/behavior unavailable under AOT.

AOT may:

- tree-shake unreachable declarations;
- precompute layouts/vtables;
- monomorphize generics where appropriate;
- emit fixed metadata needed for reflection explicitly supported by Oreslang;
- interpret or load separately compiled guest generations when a deployment
  profile permits hot loading.

## Reflection and code generation

Reflection, where supported, observes compiler-produced metadata. It does not
grant mutation of the declaration universe.

Production runtime dependencies must not rely on runtime Java bytecode/class
generation for Oreslang semantics. Java/Graal internals may use JIT machinery
inside the implementation; guest Oreslang code does not receive that authority.

## Required parity tests

Every language feature that affects declaration/type identity should be tested
against both execution families:

1. JVM/Graal JIT: parse, type-check, execute.
2. GraalVM Native Image AOT: build the native launcher and execute representative
   source with the same result.

At minimum the suite must prove:

- namespace/module/class/trait identities cannot be declared from runtime
  statements;
- local struct/interface declarations, once implemented, are lowered as static
  lexical declarations;
- Map remains the dynamic-key facility;
- JIT and AOT produce the same observable output for declaration-heavy fixtures;
- no source feature silently falls back to runtime class generation under JIT.
