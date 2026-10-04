# Oreslang JIT + AOT compilation contract

Oreslang is designed so the same source-level declaration and type graph feeds
both the Truffle/Graal JIT backend and a true native AOT backend.

Core rule:

> Runtime values are dynamic. Runtime type, module, namespace, struct, trait,
> and interface definitions are not.

GraalVM Native Image uses a closed-world model, so Oreslang makes the closed
world a language property rather than relying on runtime class generation.

## Host AOT is not guest AOT

Keep three concepts separate:

1. OresVM host deployment: JVM, Native Image, or hybrid.
2. Guest execution: interpreted or Truffle/Graal JIT.
3. Oreslang compilation target: interpreted, JIT, or AOT.

A Native Image containing the Oreslang interpreter is not automatically an
AOT-compiled Oreslang application. ExecutionProfile.Mode.AOT describes host
deployment. OresCompiler.CompilationMode.AOT describes the Oreslang source
compilation target.

## Static declaration graph

The complete declaration graph must be discoverable after parse/type/link time.

Static declarations include modules, namespaces, classes, structs, traits,
interfaces, type aliases, module functions/bindings, fields, methods, and
interface/trait requirements.

Instances and ordinary data remain dynamic.

The language must not permit runtime class/module/namespace/interface/trait/
struct definition, monkey patching, adding fields or methods after compilation,
runtime subclass synthesis, or attaching traits to already-created runtime
objects.

OresCompiler.declarationManifest(...) records the same static graph for JIT and
AOT compilation.

## Namespace

A namespace is a static type-only container. It may contain type aliases,
interfaces, classes, abstract classes, structs, and traits. It cannot contain
runtime functions/routines, actors, mutable runtime state, init hooks, nested
modules, or nested namespaces.

There is no runtime namespace constructor or namespace-table mutation.

## Module

A module is a statically declared runtime/value scope. It may own functions,
bindings/state, and types. Runtime module state is allowed; runtime module
definition is not.

## Class

A class is a static nominal identity/reference type. Runtime allocation and
dynamic dispatch are allowed, but inheritance, field layout, method sets, and
dispatch slots are fixed before backend code generation.

No runtime subclass generation or bytecode-generated class proxies are required
by core Oreslang semantics.

## Struct

A struct is a static nominal value type with a fixed field set and layout.

The existing four-way-type direction remains authoritative: structs have value
semantics, do not use class identity/inheritance, may satisfy interfaces and
compose traits, and can derive copy behavior when their fields permit it.

DynamicStruct<T> is dynamic data, not a dynamically created type. Runtime keys
do not create declarations and therefore do not violate the closed-world model.

## Interface

An interface is a static storage-free contract. Requirements and implementation
relationships are known before code generation. Runtime interface values and
indirect dispatch remain allowed through prebuilt dispatch/witness tables.

Explicit structural typing can match a value to an already-known interface
shape without manufacturing a new interface type.

## Trait

Oreslang's existing stateful-trait direction is AOT-compatible because traits
have no independent runtime identity.

Traits may provide required behavior, methods, and initialized reusable state,
but composition is resolved at compile/link time and flattened before type,
ownership, capability, layout, and backend passes. Trait collisions and
diamonds are resolved statically. Traits cannot be attached to or detached from
runtime instances.

## Dynamic dispatch

AOT does not require every call target to be singular. Virtual methods,
interface values, trait-composed methods, closures, function values, and actor
behavior dispatch remain valid as long as the definitions and table shapes are
known for the artifact.

## Generics

Generics must not synthesize new nominal types at runtime. The backend can
monomorphize the reachable instantiations at build time or use shared generic
code with statically generated dictionaries/witness tables.

## Reflection and typeof

Reflection/typeof may inspect metadata emitted for statically known
declarations. They must not define new types, add members, arbitrarily load new
classes, or generate runtime proxies/types.

## Hot loading

Hot loading and AOT are separate axes.

- JIT generations may accept source, compile a new static generation, then
  activate it.
- Native-host plus interpreted-guest mode may accept source at runtime, but
  that is not guest AOT.
- A true AOT guest artifact cannot accept arbitrary new source without external
  compilation/rebuild.

A future precompiled plugin ABI can load an already-built generation where the
platform allows it, but the plugin arrives with a fixed declaration/ABI
manifest. The runtime never manufactures its types.

## Compiler invariant

Any future AST node that permits namespace/module/class/struct/trait/interface
definition as a runtime statement or expression must fail AOT validation unless
it is first lowered into a statically known declaration before backend code
generation.

The intended pipeline is:

    source
      -> parse
      -> type/capability/ownership checks
      -> static declaration + ABI graph
      -> tree shaking / specialization
      -> JIT backend OR AOT backend

JIT and AOT differ in code generation and deployment, not in source-language
meaning or declaration shape.
