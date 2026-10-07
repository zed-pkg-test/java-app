# Untrusted actor code sharing

Shared/private actor code definitions may be reused across actors in one OS process. Untrusted actors may reuse **only** exact source identities and immutable content hashes approved by the trusted supervisor. Other untrusted programs remain isolated and are never attached to the process-wide program cache. Every destination still passes capability checks. Imports do not imply transitively approved dependencies. Source/AST reuse is not evidence of JIT machine-code sharing under `ContextPolicy.EXCLUSIVE`.

A real actor sharing proof should verify immutable code-image object identity, independent actor state and denied non-allowlisted code sharing. Cross-process sharing is out of scope.
