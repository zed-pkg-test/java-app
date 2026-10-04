# Oreslang JIT + AOT compilation contract

Oreslang is designed so the same source-level declaration/type model can feed
both a Truffle JIT backend and a native AOT backend.

The key rule is:

> Runtime values are dynamic. Runtime type/namespace/module definitions are not.

This matches the closed-world requirement of native AOT compilation without
making ordinary object allocation, actor spawning, polymorphism, generics, or
dynamic dispatch illegal.

## Three separate concepts

Do not conflate these:

1. **OresVM host deployment**
   - JVM-hosted runtime;
   - GraalVM Native Image host/runtime;
   - hybrid host/runtime.

2. **Oreslang guest execution**
   - interpreted;
   - Truffle/Graal JIT.

3. **Oreslang guest compilation target**
   - interpreted;
   - JIT;
   - true AOT machine-code/object-code output.

A Native Image containing the Oreslang interpreter is not, by itself, an
AOT-compiled Oreslang application. The compiler front end therefore exposes
`OresCompiler.CompilationMode.AOT` separately from `ExecutionProfile.Mode.AOT`.

## Static declaration rule

The complete declaration graph must be knowable after parse/type/link time.

The following are static declarations:

- namespace;
- module;
- class;
- struct;
- trait;
- interface;
- type alias;
- top-level/module function;
- module binding;
- class/struct fields;
- class/struct/trait methods;
- interface/trait requirements.

They may be selected, instantiated, referenced, dispatched through, or optimized
at runtime, but they may not themselves be manufactured at runtime.

Forbidden language features include:

- `define class` inside a function, lambda, actor turn, loop, branch or callback;
- runtime module/namespace creation;
- runtime interface/trait/struct definition;
- adding/removing fields or methods after compilation;
- monkey-patching a class/module/trait;
- applying a trait/mixin to an already-compiled type at runtime;
- evaluating source text to introduce new type definitions into a true AOT
  application;
- bytecode/class generation as a required production language feature.

JIT mode may hot-load a new **code generation**, but that generation is compiled
as a new statically declared unit before activation. It is not mutation of an
existing class/module definition.

True AOT mode cannot accept arbitrary new source after the artifact was built.
An update requires one of:

- rebuild/redeploy;
- process replacement;
- activation of a generation that was already compiled and linked into the
  artifact;
- a future explicitly defined precompiled plugin ABI.

The current `NATIVE_HOST_INTERPRETED_GUEST` hot-reload path remains useful, but
it is interpreted guest source running inside an AOT-built host, not true guest
AOT.

## Namespace

A namespace is compile-time naming only.

- flat/file-level declaration today;
- no namespace object constructor;
- no runtime namespace table mutation;
- imports may alias a namespace view, but do not create a new semantic namespace;
- namespace identity is fixed before backend code generation.

## Module

A module is a static declaration container with static identity.

It may contain functions, bindings and type declarations. Runtime code may hold
an internal facade/handle to a module, but that handle refers to a module already
present in the declaration manifest.

There is no:

- `new Module(...)`;
- runtime `define module`;
- adding exports after startup;
- import-time code generation that changes the module shape.

Module bindings can still be initialized at runtime where semantics require it;
their *names and storage slots* are statically known.

## Class

A class is a statically declared nominal reference type.

Allowed:

- runtime object allocation;
- inheritance fixed at build/link time;
- virtual/interface dispatch;
- generic instantiation according to compiler strategy;
- actor classes and ordinary classes.

Not allowed:

- runtime subclass creation;
- adding/removing fields;
- adding/replacing methods;
- metaclass mutation;
- runtime bytecode generation as part of normal Oreslang semantics.

For AOT, class layouts, inheritance edges and dispatch slots are fixed before
machine-code emission.

## Struct

A struct should be a statically declared nominal value/product type.

Recommended semantics:

- fixed ordered field set;
- fixed field types;
- no subclassing;
- value semantics by default;
- methods/associated functions allowed;
- may implement interfaces and traits;
- layout is fully computable at build time;
- no dynamic fields/property bags.

This gives the AOT backend a predictable layout and also gives the JIT backend a
very optimizable shape.

## Interface

An interface is a pure statically declared contract.

Recommended semantics:

- method/property requirements only;
- no mutable instance storage;
- no runtime creation of new interfaces;
- implementation edges are fixed at compile/link time;
- dynamic dispatch is implemented with prebuilt interface/vtable/witness data.

Structural matching can still exist when explicitly requested by Oreslang
syntax, but the structural shape itself must be known statically. Structural
typing does not imply runtime type creation.

## Trait

A trait should be static reusable behavior plus requirements.

To keep traits AOT-friendly and distinct from interfaces:

- traits may declare required methods;
- traits may provide default method bodies;
- traits may not introduce mutable per-instance storage;
- trait application is resolved at compile/link time;
- conflicts/diamond resolution are compiler errors or require explicit
  disambiguation;
- no runtime mixins;
- no attaching/detaching traits from an object/class after compilation.

A class or struct can implement an interface and use one or more traits. The
compiler lowers trait methods into ordinary statically known methods/witness
tables before JIT/AOT backend code generation.

## Dynamic dispatch is still allowed

AOT does not require every call target to be statically singular.

Oreslang may support:

- virtual class methods;
- interface values;
- trait values;
- function values/closures;
- actor behavior dispatch.

The restriction is that the set of *definitions* is closed for an AOT artifact.
The backend can emit vtables, interface tables, witness tables or indirect-call
stubs for those known definitions.

## Generics

Generics must not synthesize brand-new runtime types.

Two AOT-safe implementation strategies are valid:

1. monomorphize the reachable set of generic instantiations at build time; or
2. use shared generic code with statically generated dictionaries/witness tables.

Oreslang can choose per type/backend, but runtime generic type-definition
generation is not part of the language contract.

## Reflection and typeof

`typeof`/reflection may inspect metadata emitted for statically known
declarations.

They must not become an escape hatch for:

- defining a new class;
- adding methods/fields;
- arbitrary class loading;
- generating proxy classes.

Reflection metadata for an AOT artifact is produced by the compiler/linker.

## Hot loading

Hot loading and AOT are separate axes:

| Host/runtime | Guest generation | Arbitrary source hot load |
| --- | --- | --- |
| JVM/Truffle | JIT | yes |
| Native Image host | interpreted guest | yes |
| Native Image host | true AOT guest | no |
| native AOT executable | true AOT guest | no |

A future server-only precompiled plugin ABI can permit loading externally built
native generations, but that would still load already-compiled code conforming
to a fixed ABI. It would not dynamically manufacture language types.

## Compiler invariant

`OresCompiler.declarationManifest(...)` inventories the closed-world symbol
graph shared by JIT and AOT compilation.

Any future AST node capable of introducing a namespace/module/class/struct/
trait/interface definition from a `Stmt` or `Expr` must be rejected by the
AOT compatibility pass unless it is first lowered into a statically known
declaration before backend compilation.
