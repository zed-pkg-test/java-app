# ores-zen-infra

Infrastructure and provider-ingress security boundary for ORES Zen.

## Current implementation status

This repository now contains executable, CI-tested ingress components; it is no longer documentation-only. It is **not yet a complete production deployment**.

Implemented and tested:

- `edge/worker.mjs`: thin public callback edge with reviewed route allowlisting, bounded streaming body reads, exact-byte forwarding, fail-closed private service binding, and removal of every caller-supplied `x-ores-*` header.
- `src/lib.rs`: provider-verification core for Slack raw-body `v0` HMAC verification, Zendesk timestamp + raw-body HMAC verification, replay-window checks, Google OIDC verified-claim policy, route-bound private-hop v2 HMAC minting, and canonical internal-header checks.
- committed `Cargo.lock`, strict rustfmt/check/Clippy/tests/RustSec gates, pinned GitHub Actions, and Node edge tests.
- database-side routing authority in `ores-zen-orm-core.integration_routes`, which makes provider route identities globally unambiguous and provider/integration constrained.

Still required before production callback traffic:

1. a private verifier service adapter that resolves `integration_routes` plus per-integration secret references and calls the verification core;
2. cryptographic Google JWT/OIDC signature verification against current Google keys/JWKS **before** `validate_verified_google_oidc_claims` is called;
3. secret-store/broker integration with current + previous private-hop key rotation and provider-secret rotation;
4. private API service binding/network policy proving the verifier and API are not directly Internet-routable;
5. deployment configuration for public edge + private verifier, with environment separation and least-privilege identities;
6. end-to-end fixtures proving wrong account/audience/body/provider/route, stale/replayed requests, forged/duplicate internal headers and oversized requests fail closed;
7. `ores-otel` integration that records only safe route templates/digests/outcomes and never tokens, signatures, raw bodies or provider secrets.

The API-side route-bound v2 assertion consumer is implemented in `ores-zen-api-server.rs`. API issue #4 tracks the remaining defense-in-depth work to reject duplicate/unknown private `x-ores-*` headers and remove Slack `api_app_id` as a routing fallback.

See [SECURITY.md](SECURITY.md) for security invariants and [TOPOLOGY.md](TOPOLOGY.md) for deployment boundaries.
