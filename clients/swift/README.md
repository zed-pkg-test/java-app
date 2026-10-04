# Zed Registry Swift client

Swift 5.9 client for the stable zed-pkg registry lifecycle on Apple platforms
and Linux:

- package and version metadata lookup;
- text search;
- organization claim;
- yank and restore;
- SHA-256-verified artifact download;
- multipart publication using JSON `meta` plus raw archive bytes.

```swift
import ZedClient

let client = try ZedClient(
    registryURL: "https://registry.zpkg.tech",
    token: ProcessInfo.processInfo.environment["ZED_TOKEN"]
)
let metadata = try await client.getPackage(org: "acme", name: "http-kit")
let version = try await client.getVersion(
    org: "acme",
    name: "http-kit",
    version: "1.2.3"
)
let artifact = try await client.downloadArtifact(version)
_ = try await client.yank(org: "acme", name: "http-kit", version: "1.2.3")
_ = try await client.restore(org: "acme", name: "http-kit", version: "1.2.3")
```

The package uses `URLSession` with a redirect-blocking, bounded streaming
delegate. It validates credential-free HTTP(S) registry URLs, preserves gateway
prefixes, applies 30-second request/resource deadlines, percent-encodes path
segments, bounds JSON and error bodies, and keeps bearer credentials and remote
response text out of default diagnostics.

Artifact requests never carry the registry bearer because `download_url` may be
a third-party presigned URL. HTTPS is required for remote artifact hosts; HTTP
is accepted only for loopback or when the configured development registry is
itself HTTP. Downloads are size-capped while streaming and verified with the
package's dependency-free SHA-256 implementation before bytes are returned.

```sh
swift test --parallel
```

Newer package-listing, semantic-search, embedding, and organization-audit routes
are intentionally outside this core package and tracked separately.
