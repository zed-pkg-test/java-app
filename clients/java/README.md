# Zed Registry Java client

Java 17+ client for the stable zed-pkg registry lifecycle:

- package and version metadata lookup;
- text search;
- organization claim;
- yank and restore;
- SHA-256-verified artifact download;
- multipart publication using JSON `meta` plus raw archive bytes.

```java
import tech.zpkg.client.ZedClient;

var client = new ZedClient(
    "https://registry.zpkg.tech",
    System.getenv("ZED_TOKEN")
);
var metadata = client.getPackage("acme", "http-kit");
var version = client.getVersion("acme", "http-kit", "1.2.3");
byte[] artifact = client.downloadArtifact(version);
client.yank("acme", "http-kit", "1.2.3");
client.restore("acme", "http-kit", "1.2.3");
```

The client uses `java.net.http`, refuses redirects, validates credential-free
HTTP(S) registry URLs, preserves gateway prefixes, applies 30-second deadlines,
percent-encodes path segments, bounds JSON and error bodies, and redacts bearer
credentials and remote response text from default diagnostics.

Artifact requests never carry the registry bearer because `download_url` may be
a third-party presigned URL. HTTPS is required for remote artifact hosts; HTTP
is accepted only for loopback or when the configured development registry is
itself HTTP. Downloads are size-capped and SHA-256 verified before bytes are
returned.

```sh
mvn --batch-mode --no-transfer-progress verify
```

Newer package-listing, semantic-search, embedding, and organization-audit routes
are intentionally outside this core package and tracked separately.
