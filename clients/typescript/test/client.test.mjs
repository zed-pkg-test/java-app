import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
  ZedClient,
  ZedApiError,
  MAX_ARTIFACT_BYTES,
  MAX_ERROR_BODY_BYTES,
  packagePath,
  versionPath,
  yankPath,
  filePath,
  normalizeRegistryUrl,
} from "../dist/index.js";

function makeVersion(overrides = {}) {
  return {
    org: "acme",
    name: "kit",
    version: "1.2.0",
    sha256: "",
    size: 0,
    format: "tar.gz",
    vcs_tag: "v1.2.0",
    download_url: "",
    published_at: "2024-01-01T00:00:00Z",
    yanked: false,
    ...overrides,
  };
}

const sha256Hex = (bytes) => createHash("sha256").update(bytes).digest("hex");

test("url helpers match and encode the core contract", () => {
  assert.equal(packagePath("acme", "kit"), "/v1/packages/acme/kit");
  assert.equal(versionPath("acme", "kit", "1.2.0"), "/v1/packages/acme/kit/versions/1.2.0");
  assert.equal(yankPath("acme", "kit", "1.2.0"), "/v1/packages/acme/kit/versions/1.2.0/yank");
  assert.equal(filePath("acme", "kit", "1.2.0", "dist/x.css"), "/v1/files/acme/kit/1.2.0/dist/x.css");
  assert.equal(
    versionPath("acme", "kit", "release candidate/1"),
    "/v1/packages/acme/kit/versions/release%20candidate%2F1",
  );
  assert.equal(packagePath("a?b", "c#d"), "/v1/packages/a%3Fb/c%23d");
});

test("registry base URLs are validated and gateway prefixes are preserved", () => {
  assert.equal(normalizeRegistryUrl(" https://registry.test/gateway/// "), "https://registry.test/gateway");
  for (const invalid of [
    "relative/path",
    "ftp://registry.test",
    "https://user:secret@registry.test",
    "https://registry.test?tenant=one",
    "https://registry.test#fragment",
  ]) {
    assert.throws(() => new ZedClient({ registryUrl: invalid }), TypeError, invalid);
  }
});

test("complete structured errors preserve their stable registry code", async () => {
  const remote = "provider-secret";
  const fakeFetch = async () =>
    new Response(JSON.stringify({ code: "org_taken", message: remote }), { status: 409 });
  const client = new ZedClient({
    registryUrl: "https://x.test///",
    token: "token",
    fetchImpl: fakeFetch,
  });
  await assert.rejects(
    () => client.claimOrg("acme"),
    (error) => {
      assert.ok(error instanceof ZedApiError);
      assert.equal(error.code, "org_taken");
      assert.equal(error.status, 409);
      assert.equal(error.message, "registry error 409: org_taken");
      assert.equal(error.registryMessage, remote);
      assert.ok(!error.message.includes("provider-secret"));
      return true;
    },
  );
});

test("truncated JSON errors fall back to a stable HTTP-derived code", async () => {
  const remote = "provider-secret".repeat(MAX_ERROR_BODY_BYTES);
  const fakeFetch = async () =>
    new Response(JSON.stringify({ code: "org_taken", message: remote }), { status: 409 });
  const client = new ZedClient({
    registryUrl: "https://x.test///",
    token: "token",
    fetchImpl: fakeFetch,
  });
  await assert.rejects(
    () => client.claimOrg("acme"),
    (error) => {
      assert.ok(error instanceof ZedApiError);
      assert.equal(error.code, "http_409");
      assert.equal(error.status, 409);
      assert.equal(error.message, "registry error 409: http_409");
      assert.ok(!error.message.includes("provider-secret"));
      assert.ok(error.registryMessage.length <= MAX_ERROR_BODY_BYTES + 1);
      return true;
    },
  );
});

test("error code falls back to http status and non-JSON text stays explicit only", async () => {
  const fakeFetch = async () => new Response("bad gateway", { status: 502 });
  const client = new ZedClient({ registryUrl: "https://x.test", fetchImpl: fakeFetch });
  await assert.rejects(
    () => client.search("x"),
    (error) =>
      error instanceof ZedApiError &&
      error.code === "http_502" &&
      error.message === "registry error 502: http_502" &&
      error.registryMessage === "bad gateway",
  );
});

test("public reads omit bearer auth, authenticated writes attach it, and redirects are refused", async () => {
  const seen = [];
  const fakeFetch = async (url, init) => {
    seen.push({
      url: String(url),
      authorization: new Headers(init.headers).get("authorization"),
      redirect: init.redirect,
      body: init.body,
    });
    if (String(url).endsWith("/v1/orgs")) {
      return new Response(JSON.stringify({ slug: "acme", created: true }), { status: 200 });
    }
    return new Response(JSON.stringify({ query: "http", items: [] }), { status: 200 });
  };
  const client = new ZedClient({
    registryUrl: "https://x.test/gateway",
    token: " zpkg_t ",
    fetchImpl: fakeFetch,
  });
  await client.search("http");
  await client.claimOrg("acme");
  assert.equal(seen[0].url, "https://x.test/gateway/v1/search?q=http");
  assert.equal(seen[0].authorization, null);
  assert.equal(seen[0].redirect, "error");
  assert.equal(seen[1].authorization, "Bearer zpkg_t");
  assert.equal(seen[1].redirect, "error");
});

test("yank is an authenticated core operation with the canonical body", async () => {
  let captured;
  const fakeFetch = async (url, init) => {
    captured = { url: String(url), init };
    return new Response(
      JSON.stringify({ org: "acme", name: "kit", version: "1.2.0", yanked: true }),
      { status: 200 },
    );
  };
  const client = new ZedClient({ registryUrl: "https://x.test", token: "token", fetchImpl: fakeFetch });
  const result = await client.yank("acme", "kit", "1.2.0", true);
  assert.equal(result.yanked, true);
  assert.equal(captured.url, "https://x.test/v1/packages/acme/kit/versions/1.2.0/yank");
  assert.equal(captured.init.method, "POST");
  assert.equal(new Headers(captured.init.headers).get("authorization"), "Bearer token");
  assert.deepEqual(JSON.parse(captured.init.body), { yanked: true });
});

test("download omits auth, refuses redirects, and verifies sha256", async () => {
  const body = new TextEncoder().encode("artifact-bytes");
  let captured;
  const fakeFetch = async (url, init) => {
    captured = { url: String(url), init };
    return new Response(body, { status: 200 });
  };
  const client = new ZedClient({
    registryUrl: "https://x.test",
    token: "zpkg_t",
    fetchImpl: fakeFetch,
  });
  const version = makeVersion({
    sha256: sha256Hex(body),
    size: body.length,
    download_url: "https://cdn.example/artifact?signature=one",
  });
  const out = await client.downloadArtifact(version);
  assert.equal(new Headers(captured.init?.headers).get("authorization"), null);
  assert.equal(captured.init.redirect, "error");
  assert.equal(captured.url, "https://cdn.example/artifact?signature=one");
  assert.deepEqual(out, body);
});

test("download resolves registry-relative URLs and rejects insecure schemes", async () => {
  const body = new TextEncoder().encode("ok");
  let seenUrl;
  const fakeFetch = async (url) => {
    seenUrl = String(url);
    return new Response(body, { status: 200 });
  };
  const client = new ZedClient({ registryUrl: "https://x.test/gateway", fetchImpl: fakeFetch });
  await client.downloadArtifact(
    makeVersion({
      sha256: sha256Hex(body),
      size: body.length,
      download_url: "artifacts/hash",
    }),
  );
  assert.equal(seenUrl, "https://x.test/gateway/artifacts/hash");

  for (const url of ["http://evil.example/artifact", "file:///etc/passwd"]) {
    await assert.rejects(
      () => client.downloadArtifact(makeVersion({ download_url: url })),
      (error) => error instanceof ZedApiError && error.code === "insecure_download_url",
    );
  }
});

test("explicit HTTP development registries may use another HTTP artifact host", async () => {
  const body = new TextEncoder().encode("development-artifact");
  let seenUrl;
  const client = new ZedClient({
    registryUrl: "http://registry.dev/gateway",
    fetchImpl: async (url) => {
      seenUrl = String(url);
      return new Response(body, { status: 200 });
    },
  });
  const out = await client.downloadArtifact(
    makeVersion({
      sha256: sha256Hex(body),
      size: body.length,
      download_url: "http://artifacts.dev/artifact",
    }),
  );
  assert.equal(seenUrl, "http://artifacts.dev/artifact");
  assert.deepEqual(out, body);
});

test("download allows loopback HTTP and enforces streamed and declared caps", async () => {
  const body = new TextEncoder().encode("ok");
  const client = new ZedClient({
    registryUrl: "https://x.test",
    fetchImpl: async () => new Response(body, { status: 200 }),
  });
  const out = await client.downloadArtifact(
    makeVersion({
      sha256: sha256Hex(body),
      size: body.length,
      download_url: "http://127.0.0.1:8080/artifact",
    }),
  );
  assert.deepEqual(out, body);

  const limit = 1 + 1024 * 1024;
  const big = new Uint8Array(limit + 64);
  const streamed = new ZedClient({
    registryUrl: "https://x.test",
    fetchImpl: async () => new Response(big, { status: 200 }),
  });
  await assert.rejects(
    () =>
      streamed.downloadArtifact(
        makeVersion({ sha256: sha256Hex(big), size: 1, download_url: "https://cdn.example/a" }),
      ),
    (error) => error instanceof ZedApiError && error.code === "artifact_too_large",
  );

  const declared = new ZedClient({
    registryUrl: "https://x.test",
    fetchImpl: async () =>
      new Response(body, {
        status: 200,
        headers: { "content-length": String(MAX_ARTIFACT_BYTES + 1) },
      }),
  });
  await assert.rejects(
    () =>
      declared.downloadArtifact(
        makeVersion({ sha256: sha256Hex(body), download_url: "https://cdn.example/a" }),
      ),
    (error) => error instanceof ZedApiError && error.code === "artifact_too_large",
  );
});
