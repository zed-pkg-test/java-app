# zed_pkg_client (Gleam)

Gleam SDK for the [zed-pkg](https://zpkg.tech) registry (Erlang target,
`gleam_httpc` transport). Types mirror the JSON Schemas in
`zed-interfaces/schemas/`; the transport is injectable so tests run without a
network.

```gleam
import zed_pkg_client as zed

let client = zed.new("https://registry.zpkg.tech")
let assert Ok(pkg) = zed.get_package(client, "acme", "kit")
let assert Ok(version) = zed.get_version(client, "acme", "kit", "1.2.0")
let assert Ok(bytes) = zed.download_artifact(client, version) // sha256-verified

let authed = zed.with_token(client, "zpkg_...")
let assert Ok(_) = zed.claim_org(authed, "acme")
```

Verify with `gleam test`.
