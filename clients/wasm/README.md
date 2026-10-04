# zed-client-wasm

WebAssembly SDK for the [zed-pkg](https://zpkg.tech) registry, compiled from
Rust with [`wasm-pack`](https://rustwasm.github.io/wasm-pack/). Runs in
browsers and workers via the global `fetch`; reuses the `zed-interfaces` DTOs
so it cannot drift from the contract.

```js
import init, { ZedClient } from "@zed-pkg/client-wasm";

await init();
const client = new ZedClient("https://registry.zpkg.tech");
const pkg = await client.getPackage("acme", "kit");
const version = await client.getVersion("acme", "kit", pkg.latest);
const bytes = await client.downloadArtifact(version); // sha256-verified
```

Authenticated calls (`claimOrg`, `yank`, `publish`) need a bearer token:

```js
client.withToken("zpkg_...");
await client.claimOrg("acme");
await client.publish(JSON.stringify(publishMeta), artifactBytes);
```

Build and verify:

```
npm run build   # wasm-pack build --target web --release
npm run check   # cargo check --target wasm32-unknown-unknown
npm test        # host-target unit tests for the pure logic
```
