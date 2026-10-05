const DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

const PROVIDER_HEADER_ALLOWLIST = Object.freeze({
  gmail: new Set(["authorization", "content-type"]),
  slack: new Set([
    "content-type",
    "x-slack-request-timestamp",
    "x-slack-retry-num",
    "x-slack-retry-reason",
    "x-slack-signature",
  ]),
  zendesk: new Set([
    "content-type",
    "x-zendesk-account-id",
    "x-zendesk-webhook-id",
    "x-zendesk-webhook-invocation-id",
    "x-zendesk-webhook-signature",
    "x-zendesk-webhook-signature-timestamp",
  ]),
});

export function routeProvider(pathname) {
  if (pathname === "/webhooks/gmail/pubsub") return "gmail";
  if (/^\/webhooks\/zendesk\/[A-Za-z0-9_-]{8,128}$/.test(pathname)) return "zendesk";
  if (pathname === "/webhooks/slack/events") return "slack";
  return null;
}

export function sanitizeHeaders(input, provider) {
  const allowed = PROVIDER_HEADER_ALLOWLIST[provider];
  if (!allowed) throw new TypeError("unknown provider header policy");

  const output = new Headers();
  for (const [name, value] of input.entries()) {
    const normalized = name.toLowerCase();
    if (normalized.startsWith("x-ores-") || !allowed.has(normalized)) continue;
    output.append(normalized, value);
  }
  return output;
}

export async function readBoundedBody(request, maxBodyBytes = DEFAULT_MAX_BODY_BYTES) {
  if (!Number.isSafeInteger(maxBodyBytes) || maxBodyBytes < 16 * 1024 || maxBodyBytes > 16 * 1024 * 1024) {
    throw new TypeError("maxBodyBytes outside reviewed bounds");
  }

  const declared = request.headers.get("content-length");
  if (declared !== null) {
    if (!/^\d+$/.test(declared)) throw new RangeError("invalid content-length");
    const declaredBytes = Number(declared);
    if (!Number.isSafeInteger(declaredBytes) || declaredBytes > maxBodyBytes) {
      throw new RangeError("request body too large");
    }
  }

  if (request.body === null) return new Uint8Array();

  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBodyBytes) {
        await reader.cancel("body limit exceeded");
        throw new RangeError("request body too large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const joined = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    joined.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return joined;
}

function boundedMaxBody(env) {
  if (env.MAX_BODY_BYTES === undefined) return DEFAULT_MAX_BODY_BYTES;
  if (!/^\d+$/.test(String(env.MAX_BODY_BYTES))) return null;
  const parsed = Number(env.MAX_BODY_BYTES);
  return Number.isSafeInteger(parsed) && parsed >= 16 * 1024 && parsed <= 16 * 1024 * 1024
    ? parsed
    : null;
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response(null, { status: 405, headers: { allow: "POST" } });
    }

    const url = new URL(request.url);
    const provider = routeProvider(url.pathname);
    if (provider === null || url.search !== "") {
      return new Response(null, { status: 404 });
    }

    if (!env.PROVIDER_VERIFIER || typeof env.PROVIDER_VERIFIER.fetch !== "function") {
      return new Response(null, { status: 503 });
    }

    const maxBodyBytes = boundedMaxBody(env);
    if (maxBodyBytes === null) {
      return new Response(null, { status: 503 });
    }

    let body;
    try {
      body = await readBoundedBody(request, maxBodyBytes);
    } catch (error) {
      if (error instanceof RangeError) return new Response(null, { status: 413 });
      return new Response(null, { status: 400 });
    }

    const headers = sanitizeHeaders(request.headers, provider);
    headers.delete("content-length");

    const forwarded = new Request(request.url, {
      method: "POST",
      headers,
      body,
      redirect: "manual",
    });

    // PROVIDER_VERIFIER is a private service binding. This edge intentionally has
    // no database, AI, admin, tenant OAuth, Slack, or Zendesk signing secrets.
    return env.PROVIDER_VERIFIER.fetch(forwarded);
  },
};
