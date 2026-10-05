import assert from "node:assert/strict";
import test from "node:test";

import worker, { readBoundedBody, routeProvider, sanitizeHeaders } from "./worker.mjs";

test("routes only reviewed provider callback paths", () => {
  assert.equal(routeProvider("/webhooks/gmail/pubsub"), "gmail");
  assert.equal(routeProvider("/webhooks/zendesk/tenant_123-zd"), "zendesk");
  assert.equal(routeProvider("/webhooks/slack/events"), "slack");
  assert.equal(routeProvider("/webhooks/zendesk/short"), null);
  assert.equal(routeProvider("/webhooks/zendesk/../../admin"), null);
  assert.equal(routeProvider("/admin"), null);
});

test("forwards only provider-specific authentication evidence", () => {
  const input = new Headers({
    authorization: "Bearer provider-oidc",
    cookie: "session=attacker-controlled",
    forwarded: "for=203.0.113.1",
    "x-forwarded-for": "203.0.113.1",
    "x-ores-ingress-signature": "forged",
    "x-ores-tenant-id": "forged",
    "x-slack-signature": "v0=abc",
    "x-slack-request-timestamp": "1800000000",
    "x-zendesk-webhook-signature": "zendesk-signature",
    "x-zendesk-webhook-signature-timestamp": "2026-09-30T22:00:00Z",
    "x-zendesk-webhook-id": "wh_123",
    "content-type": "application/json",
  });

  const gmail = sanitizeHeaders(input, "gmail");
  assert.equal(gmail.get("authorization"), "Bearer provider-oidc");
  assert.equal(gmail.get("content-type"), "application/json");
  assert.equal(gmail.get("x-slack-signature"), null);
  assert.equal(gmail.get("x-zendesk-webhook-signature"), null);
  assert.equal(gmail.get("cookie"), null);
  assert.equal(gmail.get("forwarded"), null);
  assert.equal(gmail.get("x-forwarded-for"), null);
  assert.equal(gmail.get("x-ores-ingress-signature"), null);

  const slack = sanitizeHeaders(input, "slack");
  assert.equal(slack.get("x-slack-signature"), "v0=abc");
  assert.equal(slack.get("x-slack-request-timestamp"), "1800000000");
  assert.equal(slack.get("authorization"), null);
  assert.equal(slack.get("x-zendesk-webhook-signature"), null);
  assert.equal(slack.get("cookie"), null);

  const zendesk = sanitizeHeaders(input, "zendesk");
  assert.equal(zendesk.get("x-zendesk-webhook-signature"), "zendesk-signature");
  assert.equal(
    zendesk.get("x-zendesk-webhook-signature-timestamp"),
    "2026-09-30T22:00:00Z",
  );
  assert.equal(zendesk.get("x-zendesk-webhook-id"), "wh_123");
  assert.equal(zendesk.get("authorization"), null);
  assert.equal(zendesk.get("x-slack-signature"), null);
  assert.equal(zendesk.get("x-ores-tenant-id"), null);

  assert.throws(() => sanitizeHeaders(input, "unknown"), TypeError);
});

test("bounded body reader rejects declared and streamed oversize bodies", async () => {
  const declared = new Request("https://edge.example/webhooks/slack/events", {
    method: "POST",
    headers: { "content-length": "20000" },
    body: new Uint8Array(1),
  });
  await assert.rejects(() => readBoundedBody(declared, 16384), RangeError);

  const streamed = new Request("https://edge.example/webhooks/slack/events", {
    method: "POST",
    body: new Uint8Array(16385),
  });
  await assert.rejects(() => readBoundedBody(streamed, 16384), RangeError);
});

test("edge forwards exact bytes through a private service binding with minimized headers", async () => {
  const original = new Uint8Array([0, 1, 2, 3, 254, 255]);
  let observed;
  const env = {
    MAX_BODY_BYTES: "16384",
    PROVIDER_VERIFIER: {
      async fetch(request) {
        observed = {
          url: request.url,
          signature: request.headers.get("x-slack-signature"),
          timestamp: request.headers.get("x-slack-request-timestamp"),
          internal: request.headers.get("x-ores-ingress-signature"),
          cookie: request.headers.get("cookie"),
          forwarded: request.headers.get("x-forwarded-for"),
          authorization: request.headers.get("authorization"),
          body: new Uint8Array(await request.arrayBuffer()),
        };
        return new Response("ok", { status: 202 });
      },
    },
  };

  const request = new Request("https://edge.example/webhooks/slack/events", {
    method: "POST",
    headers: {
      "content-type": "application/octet-stream",
      "x-slack-signature": "v0=abc",
      "x-slack-request-timestamp": "1800000000",
      "x-ores-ingress-signature": "forged",
      cookie: "session=attacker-controlled",
      "x-forwarded-for": "203.0.113.1",
      authorization: "Bearer should-not-reach-slack-verifier",
    },
    body: original,
  });

  const response = await worker.fetch(request, env);
  assert.equal(response.status, 202);
  assert.equal(observed.url, "https://edge.example/webhooks/slack/events");
  assert.equal(observed.signature, "v0=abc");
  assert.equal(observed.timestamp, "1800000000");
  assert.equal(observed.internal, null);
  assert.equal(observed.cookie, null);
  assert.equal(observed.forwarded, null);
  assert.equal(observed.authorization, null);
  assert.deepEqual(observed.body, original);
});

test("edge rejects query-bearing callback URLs as unreviewed authority", async () => {
  let called = false;
  const env = {
    PROVIDER_VERIFIER: {
      async fetch() {
        called = true;
        return new Response(null, { status: 204 });
      },
    },
  };
  const response = await worker.fetch(
    new Request("https://edge.example/webhooks/gmail/pubsub?tenant=forged", {
      method: "POST",
      body: "{}",
    }),
    env,
  );
  assert.equal(response.status, 404);
  assert.equal(called, false);
});

test("edge fails closed for unreviewed methods, paths, bad config and missing binding", async () => {
  assert.equal(
    (await worker.fetch(new Request("https://edge.example/webhooks/slack/events"), {})).status,
    405,
  );

  assert.equal(
    (
      await worker.fetch(
        new Request("https://edge.example/not-a-provider", { method: "POST", body: "x" }),
        {},
      )
    ).status,
    404,
  );

  assert.equal(
    (
      await worker.fetch(
        new Request("https://edge.example/webhooks/slack/events", { method: "POST", body: "x" }),
        {},
      )
    ).status,
    503,
  );
});
