# Infrastructure security invariants

## Public callback edge, private verifier, and private API

The Internet-facing edge is exposed to Gmail Pub/Sub, Zendesk, and Slack callbacks. It is intentionally thin: it preserves the exact body bytes, strips internal headers, enforces coarse request/rate limits, derives the provider from the route, and forwards only through a private service binding/path to the provider verifier.

The private provider verifier resolves server-owned integration context and authenticates provider-native evidence. Only after successful provider verification and routing-identity resolution may it mint the short-lived private-hop HMAC accepted by `ores-zen-api-server.rs`.

The public edge MUST:

1. remove every client-supplied `x-ores-*` header before forwarding;
2. preserve exact raw request bytes and provider signature/timestamp headers required for verification;
3. enforce coarse request-size and rate limits before expensive verification;
4. derive the provider name from the configured route rather than arbitrary request headers/body fields;
5. forward to the verifier only over a private service binding/network path;
6. never possess database credentials, AI credentials, admin credentials, or tenant application/provider secrets.

The private provider verifier MUST:

1. resolve tenant/integration context server-side from authenticated provider evidence plus configured routing policy;
2. resolve provider verification secrets through approved runtime secret references;
3. enforce provider-native signature/token verification and freshness limits against the exact raw body;
4. reject unknown/disabled integrations before minting an internal assertion;
5. derive and normalize the routing identity itself: Zendesk integration key, Gmail mailbox, or configured Slack workspace/team key;
6. sign `v2\n<provider>\n<routing-key>\n<unix-seconds>\n<sha256(raw-body)>` with the current ingress HMAC secret;
7. send `x-ores-ingress-routing-key`, `x-ores-ingress-timestamp`, and `x-ores-ingress-signature: v2=<base64url-no-pad>` only over the private API path;
8. never return provider signing secrets or the private-hop key to the public edge, browser/client code, logs, or persistence.

`ores-zen-api-server.rs` is a private origin. It rejects provider callback endpoints without the replay-bounded, route-bound private-hop HMAC and durably commits accepted events to `ingress_spool` before success is returned upstream. Production does not accept a `v1` downgrade path.

Provider-specific admission:

- Gmail/Pub/Sub: require an authenticated Google OIDC push token for the configured service-account identity and exact expected audience. Validate signature, issuer, audience, subject/email identity, expiration/not-before/freshness as applicable. Decode the authenticated notification, normalize the mailbox, map it to an enabled integration, and bind that mailbox into the v2 routing assertion. The Pub/Sub payload is only a history notification; workers retrieve changes with Gmail History.
- Zendesk: select the integration from the server-owned URL route key, resolve that integration's signing secret, and verify Zendesk timestamp + raw-body HMAC. Bind the same validated integration key into the v2 routing assertion. Never bind every tenant Zendesk signing secret into one public Worker.
- Slack: verify Slack's `v0:<timestamp>:<raw-body>` signature and reject stale timestamps. Only after signature verification may workspace/team identifiers be mapped to an enabled integration and bound into the v2 routing assertion. Display names or arbitrary payload strings are never tenant authority.

A verifier failure returns non-success and creates no private-hop assertion. A downstream API persistence failure likewise returns a retryable non-success; it is never converted to 2xx merely to quiet provider retries.

Required negative tests include wrong body, wrong provider, wrong routing key, stale timestamp, forged `x-ores-*` headers, wrong Google audience/account, and route confusion (for example, a valid assertion for Zendesk integration A replayed against integration B).

## Network segmentation

- Public callback edge, private provider verifier, tenant web/read server, tenant API/write server, admin web server and admin API server have separate deployment identities and least-privilege bindings.
- Admin origins are not routable through tenant custom domains.
- Provider verifier and API/database origins accept traffic only from the expected edge/workload identities; security groups/firewall/service bindings are deny-by-default.
- Background workers do not expose public ingress.
- Non-admin MCP and admin MCP use distinct auth audiences and deployment identities.

## Custom domains

A custom hostname is active only after `tenant_domain_challenges` proves ownership and certificate provisioning succeeds. Domain verification/revocation is an audited write operation.

- Hostnames are canonicalized before uniqueness/route publication.
- Wildcard routing must not let an unverified hostname select a tenant by subdomain text alone.
- Certificate issuance/renewal failure removes or quarantines the mapping rather than falling back to another tenant.
- Domain mapping publication is versioned and cache invalidation propagates to all edge locations.
- Origin routing passes a signed/private tenant mapping assertion; applications still verify that the resolved host belongs to that tenant.

## Secrets

Use `ores-sops` + SOPS/age for repository-owned encrypted configuration and approved runtime secret stores/bindings for deployed secret values. Plaintext `.env`, decrypted env directories, OAuth credentials, webhook signing secrets, ingress HMAC keys, database passwords and AI API keys never enter git.

Per-integration secret references are resolved in the private verifier/worker tier that needs them. Do not aggregate all tenant Zendesk/Slack/provider secrets into browser code, public edge variables, one JSON secret map, CLI arguments, or database plaintext merely for routing convenience.

Runtime secrets are exposed only to the workload that needs them. AI provider keys are not available to the web UI or generated clients; admin credentials are not available to tenant workloads. Rotation supports current + previous private-hop keys only for a short, explicitly bounded overlap.

## Data stores

- Postgres/pgvector is private-network only and encrypted in transit.
- Use different DB roles for migrations, tenant API, tenant read plane, workers and admin services.
- Composite tenant and provider foreign keys are the minimum integrity layer. Add Row Level Security only with reviewed role/BYPASSRLS semantics and integration tests; never enable a cosmetic RLS policy that service roles bypass silently.
- Object storage for attachments is private. Application endpoints issue short-lived authorized downloads only after malware scan state is `clean` and scan evidence is present.
- Backups are encrypted and restoration is exercised. Retention/deletion applies to primary rows, object blobs, vector representations and backups according to the documented retention policy.

## Queues and jobs

Use durable queues/outbox/spool records. In-memory queues are not acceptable for acknowledged provider events. Queue messages contain opaque IDs/digests where possible rather than full sensitive bodies. DLQ replay follows the same authorization/idempotency path as normal processing.

## Observability

Standardize through `ores-otel`. Trace/log attributes may include opaque tenant/ticket/event IDs, provider, job type, attempt, provider verification outcome, latency and digests. Explicitly redact Authorization/Cookie headers, Google OIDC tokens, webhook signatures, OAuth/AI/database secrets, email bodies, raw headers, attachment contents, and secret-bearing route tokens. Prefer matched route templates over raw URIs where routes contain integration identifiers.

## Environment layout

Follow the common `modules/` + `environments/{dev,stage,prod}` pattern. Cloudflare/custom-host routing, provider-verifier bindings, Postgres/pgvector, shared-auth, Pub/Sub, Slack/Zendesk connector resources, queues, object storage and observability are independent modules with environment-specific composition. Production resources are never selected by an unreviewed local `.env` file.
