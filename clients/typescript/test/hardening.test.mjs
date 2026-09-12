import test from "node:test";
import assert from "node:assert/strict";
import {
  MAX_ARTIFACT_BYTES,
  MAX_PATH_SEGMENT_BYTES,
  ZedApiError,
  ZedClient,
  encodePathSegment,
  filePath,
  packagePath,
} from "../dist/index.js";

const publishMeta = {
  manifest: { package: { org: "acme", name: "kit", version: "1.2.0" } },
};

test("hostile and ambiguous route segments are rejected before transport", () => {
  for (const value of ["", "   ", ".", "..", "line\nbreak", "nul\0byte"]) {
    assert.throws(() => encodePathSegment(value), TypeError, value);
  }
  assert.throws(
    () => encodePathSegment("x".repeat(MAX_PATH_SEGMENT_BYTES + 1)),
    TypeError,
  );
  assert.throws(() => packagePath("..", "kit"), TypeError);
  assert.throws(() => filePath("acme", "kit", "1.2.0", "dist/../secret"), TypeError);
  assert.equal(encodePathSegment("release candidate/1"), "release%20candidate%2F1");
});

test("authenticated operations fail closed before invoking fetch", async () => {
  let calls = 0;
  const client = new ZedClient({
    registryUrl: "https://registry.test",
    fetchImpl: async () => {
      calls += 1;
      throw new Error("transport must not run");
    },
  });

  for (const operation of [
    () => client.claimOrg("acme"),
    () => client.yank("acme", "kit", "1.2.0"),
    () => client.restore("acme", "kit", "1.2.0"),
    () => client.publish(publishMeta, new Blob(["artifact"])),
  ]) {
    await assert.rejects(
      operation,
      (error) => error instanceof ZedApiError && error.code === "missing_token",
    );
  }
  assert.equal(calls, 0);
});

test("publish rejects oversized artifacts before invoking fetch", async () => {
  let calls = 0;
  const client = new ZedClient({
    registryUrl: "https://registry.test",
    token: "token",
    fetchImpl: async () => {
      calls += 1;
      throw new Error("transport must not run");
    },
  });
  await assert.rejects(
    () => client.publish(publishMeta, { size: MAX_ARTIFACT_BYTES + 1 }),
    (error) => error instanceof ZedApiError && error.code === "artifact_too_large",
  );
  assert.equal(calls, 0);
});

test("yank and restore share the canonical authenticated endpoint", async () => {
  const bodies = [];
  const client = new ZedClient({
    registryUrl: "https://registry.test",
    token: " token ",
    fetchImpl: async (_url, init) => {
      bodies.push({
        authorization: new Headers(init.headers).get("authorization"),
        body: JSON.parse(init.body),
      });
      return new Response(
        JSON.stringify({ org: "acme", name: "kit", version: "1.2.0", yanked: init.body.includes("true") }),
        { status: 200 },
      );
    },
  });
  assert.equal((await client.yank("acme", "kit", "1.2.0")).yanked, true);
  assert.equal((await client.restore("acme", "kit", "1.2.0")).yanked, false);
  assert.deepEqual(bodies, [
    { authorization: "Bearer token", body: { yanked: true } },
    { authorization: "Bearer token", body: { yanked: false } },
  ]);
});

test("the request deadline remains active while consuming a streamed body", async () => {
  const client = new ZedClient({
    registryUrl: "https://registry.test",
    timeoutMs: 10,
    fetchImpl: async (_url, init) => {
      const stream = new ReadableStream({
        start(controller) {
          init.signal.addEventListener(
            "abort",
            () => controller.error(new DOMException("aborted", "AbortError")),
            { once: true },
          );
        },
      });
      return new Response(stream, { status: 200 });
    },
  });
  await assert.rejects(
    () => client.search("never-completes"),
    (error) => error?.name === "AbortError" || /abort/i.test(String(error)),
  );
});

test("blank structured error codes fall back to the HTTP-derived code", async () => {
  const client = new ZedClient({
    registryUrl: "https://registry.test",
    fetchImpl: async () =>
      new Response(JSON.stringify({ code: "   ", message: "remote detail" }), { status: 409 }),
  });
  await assert.rejects(
    () => client.search("x"),
    (error) => error instanceof ZedApiError && error.code === "http_409",
  );
});
