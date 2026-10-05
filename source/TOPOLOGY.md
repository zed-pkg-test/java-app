# ORES Zen infrastructure topology

Environments: `dev`, `stage`, `prod`.

Core modules:
- Cloudflare tenant-domain routing and certificates
- provider callback edge with raw-body preservation, body/rate limits, and `x-ores-*` stripping
- private provider-verifier service for Gmail/Google OIDC, per-integration Zendesk HMAC, and Slack `v0` signatures
- Google Cloud Pub/Sub for Gmail push delivery and dead-lettering
- Postgres with pgvector and PITR/backups
- durable queues for ingestion, embeddings, relations, alerts and connector work
- shared-auth identity
- ores-otel observability
- ores-sops encrypted configuration / runtime secret references

## Provider callback path

```text
Internet provider
  -> Cloudflare public callback edge
     - preserve exact body bytes
     - remove client x-ores-* headers
     - enforce body/rate limits
  -> private provider verifier
     - Gmail: Google OIDC service-account + exact audience
     - Zendesk: resolve integration key server-side, then verify timestamp + raw-body signature
     - Slack: verify v0 timestamp + raw-body signature
  -> mint short-lived private-hop HMAC
  -> ores-zen-api-server.rs private origin
  -> durable ingress_spool commit
  -> provider HTTP success
  -> background workers / reconciliation
```

The private API origin is not directly Internet-routable. A callback is never acknowledged merely because the edge received it: provider authenticity must be verified and the API must durably persist the event first.

Per-integration Zendesk signing material is **not** bundled into one public Worker or selected from request payload fields. The verifier resolves the integration by the server-owned route key and reads the corresponding runtime secret reference. This keeps tenant secrets out of browser/public-edge configuration and allows independent rotation.

Cloudflare service bindings/private networking should connect the edge to the verifier and verifier to the API where available; no public URL should be required between those tiers.

Public web/read, tenant API/write, admin API, and admin web deploy as separate services. Admin services use separate credentials and network policy. Gmail Pub/Sub targets the authenticated callback edge. Tenant hostnames must be verified before routing is enabled.
