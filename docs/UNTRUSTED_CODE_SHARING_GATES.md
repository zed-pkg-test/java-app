# Untrusted actor code sharing

Private/isolated actor contexts may share immutable code through the process
`SharedCodeRegistry` and `ProcessCodeEngine`. Untrusted actors are
**private by default** and may reuse a host process code image only after the
trusted supervisor approves the exact `(codeUnitId, SHA-256(sourceText))`.

Approval is fail-closed:

- the approving policy must be trusted and hold `HOT_CODE_LOAD`;
- the exact source must pass `IsolatePolicy.untrustedActor()` capability
  admission before the grant is installed;
- renamed units, edited source, and imported dependencies require separate
  grants;
- revocation removes the grant for future loads;
- unapproved adversarial source is constructed with `Source.cached(false)`.

The grant authorizes reuse only of immutable `Source` and checked program
metadata. It does not grant guest capabilities or join the untrusted sandbox to
the trusted/private Graal Engine.

Private/isolated sharing is proven with a shared Engine, identical Source
identity, distinct Contexts, and a parse counter showing the second context
reuses the Engine-cached parsed call target. Untrusted sharing is proven
separately at `HotReloadManager`: unapproved loads have distinct private
Source identities; exact-approved loads report `sharedCodeImage() == true`
and reuse the same process-owned Source; changed/renamed loads remain unshared.

Cross-process code sharing and a claim of shared JIT machine-code pages across
the untrusted trust boundary remain out of scope.
