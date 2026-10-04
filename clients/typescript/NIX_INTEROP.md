# TypeScript Nix interoperability verification

The TypeScript client exposes strict, zero-runtime-dependency readers for the
canonical Nix interoperability records published by `zed-interfaces`:

```ts
import {
  parseNixAdapterRecord,
  parseNixAdapterRecordJson,
  canonicalNixAdapterRecordJson,
  nixAdapterRecordSha256,
  verifyCanonicalNixAdapterRecordJson,
  verifyNixAdapterArtifactBytes,
} from "@zed-pkg/client";
```

The supported schema is exactly:

```text
zed.nix-adapter/v1
```

Unknown directions, schema versions, fields, policy values, store-info JSON
versions, and numeric values outside JavaScript's safe integer range fail
closed.

## Structural verification

`parseNixAdapterRecord` validates and normalizes either direction:

- `zed-to-nix`: immutable Zed artifact origin, explicit Nix intent, at least one
  realized output, system/output agreement, and strict policy evidence;
- `nix-to-zed`: immutable Nix selector and realization evidence, a translated
  Zed artifact, strict policy evidence, and a closure-free selected output.

The parser validates public package identity, exact SHA-256 and SHA-256 SRI
syntax, Nix attributes, systems, outputs, store paths, signatures, references,
artifact sizes, and supported store-info JSON versions. Runtime references in a
version-1 Nix-to-Zed record are rejected rather than hidden in a wrapper.

Input objects are never returned directly. The parser creates a fresh normalized
object, sorts arrays whose order is non-semantic, removes default-empty optional
arrays, and rejects duplicate references, signatures, or system/output pairs.
Unknown fields cannot be used to smuggle credentials or private cache keys into
the contract.

## Canonical bytes and digest

Rust emits compact JSON with all object keys recursively sorted. The TypeScript
helper reproduces those bytes:

```ts
const canonical = canonicalNixAdapterRecordJson(untrustedValue);
const digest = await nixAdapterRecordSha256(untrustedValue);
```

`verifyCanonicalNixAdapterRecordJson` additionally requires the supplied string
to already be canonical; a trailing newline, pretty printing, different object
key order, or omitted/default field drift is rejected. An optional expected
lowercase SHA-256 can bind the exact adapter bytes.

Digest helpers use the standard Web Crypto API available in browsers and Node
22. They import no Node-only runtime module and add no package dependency.

## Artifact-byte binding

`verifyNixAdapterArtifactBytes` hashes exact Zed artifact bytes and checks both
SHA-256 and byte size:

- for `nix-to-zed`, it verifies the translated ordinary Zed artifact;
- for `zed-to-nix`, it verifies the source Zed artifact recorded by the export.

This catches archive tampering without requiring Nix or registry access.

## What offline verification does not prove

These helpers intentionally do **not**:

- evaluate a flake or arbitrary Nix expression;
- realize a derivation;
- query a Nix store or binary cache;
- verify that a diagnostic `/nix/store/...` path currently exists;
- replay a derivation JSON digest, NAR hash, signature, or closure;
- verify the contents of a tar/zip archive beyond its exact outer hash and size;
- publish a Zed package, Nix overlay, or binary-cache object; or
- grant trust to a cache signing key.

Those claims require the pinned `zed` CLI, Nix/store commands, explicit trust
policy, and the clean-room conformance workflows. The SDK distinguishes
structural and byte-level verification from realization replay so callers do not
mistake a well-formed provenance claim for proof that a build was reproduced.

## Error safety

Malformed JSON produces a generic syntax error. Unknown fields report their
field name but never their value. Contract fields contain no token, password,
private key, cache credential, or secret-delivery slot, so normalized records
cannot serialize those values.
