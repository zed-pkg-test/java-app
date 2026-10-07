# OresVM: one-process shared code, actor-owned mutable state

**Scope (2026-10-06): All actor kinds run in the same OS process.** The
current objective is to load one immutable code/import definition image and
reuse it across shared, private/isolated, and untrusted actors **inside that
process**. Sharing native executable pages across multiple OS processes,
`mmap`, `memfd`, and relocation design are explicitly deferred.

## Required invariants

- Load/parse/check one code unit *per source identity and version*, not once
  per actor. All actor kinds can reference the same process-owned, immutable
  definition image.
- Each actor retains its own mailbox, mutable fields, closure environments,
  module initialization results, async frames, execution lease, GC roots and
  capability policy. Do not duplicate imports as executable source/AST
  definitions just because a guest has a private or untrusted execution context.
- Shared code is not shared mutable data. Actors must not directly write
  another actor's heap. For `shared actor`, a capability to read approved
  frozen data is not permission to write shared mutable memory.
- Importing a definition never grants access to unrelated module globals or
  transitive host capabilities. Admission is repeated for each destination
  actor/context, even when the underlying AST is cached.
- Untrusted actors receive no Java/Truffle host object, raw function
  pointer, mutable AST handle, classloader, cross-actor closure, or supervisor
  capability merely because the immutable code is process-owned.
- Hot reload pins old versioned images until the last live actor/continuation
  has finished with them; a new image does not mutate an old actor's code.

## What the current branch actually shares

The draft `SharedCodeRegistry` is supervisor-owned and refcounted. It
reuses the exact `org.graalvm.polyglot.Source` object and the same checked
immutable Oreslang `Ast.Program` for all live contexts with identical
`(codeUnitId, SHA-256(sourceText))`. It checks source bytes/text even
after a digest hit; different versions or source identities do not alias.

`HotReloadManager.load` checks a cached immutable AST against the
*destination* `IsolatePolicy`, then creates an independent Graal
`Context` and pins a lease. `OresLanguage.parse` looks up that pinned
AST rather than reparsing on every actor load. The new `Evaluator` and its
mutable module/closure state remain context-local.

**Important limitation:** `OresLanguage` uses
`TruffleLanguage.ContextPolicy.EXCLUSIVE`, so a fresh Truffle
`RootCallTarget` and per-context evaluator are still created during
`Context.eval`. This branch shares the immutable **source and checked
program definition tree** but does not establish that Graal JIT machine
code or executable call targets are shared among private/untrusted
Truffle contexts. Those are potential follow-on same-process optimizations,
subject to no mutable JIT cache or guest-context leakage.

## Actor construction and source-level rules

- Actor `constructor(...)` remains **rejected** (ordinary classes can
  still have constructors). `spawn Worker()` returns `ActorRef<Worker>`;
  the runtime, not the caller, constructs actor-owned state.
- A future `on_start(): void` lifecycle hook runs inside the established
  actor domain before `w.ready` resolves. Actor start-up failure rejects
  readiness instead of leaking an uninitialized object.
- Lambda bodies use the **slim arrow**:
  `const create = async || -> { const w = spawn Worker(); await w.ready; return w; };`
  The fat arrow `=>` remains reserved for other declaration semantics.
- Classes, actors, and modules may resolve only their own members,
  activation-local lexical bindings, explicitly imported definitions,
  and capability-approved built-ins. Qualified names such as
  `OtherModule.helper` must not bypass explicit import rules.
  The initial branch only guards implicit unqualified fallbacks;
  complete type/runtime binding enforcement is a separate gate.

## Same-process acceptance tests

1. Spawn at least 1,000 actors of each kind with the same imports. Show
   stable process-owned immutable code identity and that the amount of
   cached definitions does **not** grow linearly with actor count.
2. Show mutable actor/instance/module states remain independent, with no
   cross-actor writable aliases, including through `rt proxy` or imports.
3. Check the same image against trusted and untrusted policies: a privileged
   builtin may be accepted by one but must be rejected by the other.
4. Concurrency-test first-load compilation, reference counting, version
   replacement, cancellation/failure cleanup, and final image eviction.
5. Verify explicit-import-only lexical boundaries on class/actor/module
   references including qualified access; preserve valid explicit imports.
6. Run exact-head Maven/GraalVM tests before merge. When source Actions
   cannot allocate runners, use a funded organization with an **exact-tree
   verified** temporary mirror and no cross-org token/secrets.

## Deferred, outside the current scope

Cross-OS-process read-only page mapping, shared native executable pages,
`memfd`, multi-process loader/ABI relocation and PSS/RSS comparison.
Do not treat these as prerequisites for this same-process design.
