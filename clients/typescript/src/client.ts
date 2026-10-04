import type {
  ApiErrorBody,
  ClaimOrgResponse,
  PackageMetadata,
  PublishResponse,
  SearchResponse,
  VersionMetadata,
  YankResponse,
} from "./types.js";

export const DEFAULT_REGISTRY_URL = "https://registry.zpkg.tech";

/** Bounds every request, including streamed response-body consumption. */
export const DEFAULT_TIMEOUT_MS = 30_000;

/** Successful JSON documents are never allowed to grow without bound. */
export const MAX_JSON_RESPONSE_BYTES = 16 * 1024 * 1024;

/** Remote error text is retained only through this bounded explicit field. */
export const MAX_ERROR_BODY_BYTES = 16 * 1024;

/** Maximum UTF-8 size of one opaque registry route segment. */
export const MAX_PATH_SEGMENT_BYTES = 256;

/**
 * Hard ceiling on artifact downloads, matching the server's MAX_ARTIFACT_BYTES
 * default (100 MiB); plus the slack added to a version's declared size.
 */
export const MAX_ARTIFACT_BYTES = 100 * 1024 * 1024;
const DOWNLOAD_SLACK = 1024 * 1024;
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f]/u;

/** The declared size (when sane) plus slack, capped by the ceiling. */
function downloadLimit(size: number): number {
  if (Number.isFinite(size) && size > 0) {
    return Math.min(size + DOWNLOAD_SLACK, MAX_ARTIFACT_BYTES);
  }
  return MAX_ARTIFACT_BYTES;
}

interface BoundedBytes {
  bytes: Uint8Array<ArrayBuffer>;
  truncated: boolean;
}

/** Stream no more than `limit` bytes, cancelling once the bound is exceeded. */
async function readAtMost(response: Response, limit: number): Promise<BoundedBytes> {
  const body = response.body;
  if (!body) {
    const source = new Uint8Array(await response.arrayBuffer());
    return {
      bytes: source.slice(0, limit),
      truncated: source.byteLength > limit,
    };
  }

  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  let truncated = false;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (!value) continue;
    const remaining = limit - total;
    if (value.byteLength > remaining) {
      if (remaining > 0) chunks.push(value.slice(0, remaining));
      total = limit;
      truncated = true;
      await reader.cancel();
      break;
    }
    chunks.push(value);
    total += value.byteLength;
  }

  const output = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    output.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return { bytes: output, truncated };
}

async function readCapped(
  response: Response,
  limit: number,
  code: string,
  description: string,
): Promise<Uint8Array<ArrayBuffer>> {
  const declared = response.headers.get("content-length");
  if (declared !== null && Number(declared) > limit) {
    throw new ZedApiError(0, code, `${description} exceeded ${limit} bytes; refusing`);
  }
  const bounded = await readAtMost(response, limit);
  if (bounded.truncated) {
    throw new ZedApiError(0, code, `${description} exceeded ${limit} bytes; refusing`);
  }
  return bounded.bytes;
}

/**
 * Registry failures keep bounded remote text in `registryMessage`, while the
 * ordinary Error message exposes only status and stable machine-readable code.
 */
export class ZedApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly registryMessage: string,
  ) {
    super(`registry error ${status}: ${code}`);
    this.name = "ZedApiError";
  }
}

export interface ClientOptions {
  registryUrl?: string;
  token?: string;
  fetchImpl?: typeof fetch;
  /** Per-request timeout in milliseconds (default {@link DEFAULT_TIMEOUT_MS}). */
  timeoutMs?: number;
}

/** Validate and normalize one registry base URL while preserving a path prefix. */
export function normalizeRegistryUrl(raw: string): string {
  let url: URL;
  try {
    url = new URL(raw.trim());
  } catch {
    throw new TypeError("registryUrl must be an absolute HTTP(S) URL");
  }
  if (
    !["http:", "https:"].includes(url.protocol) ||
    url.hostname === "" ||
    url.username !== "" ||
    url.password !== "" ||
    url.search !== "" ||
    url.hash !== ""
  ) {
    throw new TypeError(
      "registryUrl must be a credential-free absolute HTTP(S) URL without query or fragment",
    );
  }
  url.pathname = url.pathname.replace(/\/+$/, "");
  return url.toString().replace(/\/$/, "");
}

/** Validate one opaque path segment before URL construction. */
export function encodePathSegment(value: string, name = "path segment"): string {
  const size = new TextEncoder().encode(value).byteLength;
  if (
    value.trim() === "" ||
    value === "." ||
    value === ".." ||
    size > MAX_PATH_SEGMENT_BYTES ||
    CONTROL_CHARACTER.test(value)
  ) {
    throw new TypeError(
      `${name} must be nonblank, at most ${MAX_PATH_SEGMENT_BYTES} UTF-8 bytes, and must not be a dot segment or contain control characters`,
    );
  }
  return encodeURIComponent(value);
}

/**
 * Enforce the download-url scheme policy: https is always allowed; http only
 * for loopback hosts or when the registry base is itself http. Userinfo is
 * rejected. Query strings remain allowed because S3/R2 presigned URLs need
 * them. No registry bearer token is ever attached to this URL.
 */
export function allowedDownloadUrl(raw: string, base: string): string {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new ZedApiError(0, "bad_download_url", `bad download url ${raw}`);
  }
  if (url.username !== "" || url.password !== "" || url.hash !== "") {
    throw new ZedApiError(0, "bad_download_url", "download URL contains credentials or fragment");
  }
  const host = url.hostname;
  const loopback =
    host === "localhost" || host === "127.0.0.1" || host === "[::1]" || host === "::1";
  if (url.protocol === "https:") return url.toString();
  if (url.protocol === "http:" && (loopback || base.startsWith("http://"))) {
    return url.toString();
  }
  throw new ZedApiError(
    0,
    "insecure_download_url",
    `refusing artifact download over ${url.protocol} from ${raw}`,
  );
}

export function packagePath(org: string, name: string): string {
  return `/v1/packages/${encodePathSegment(org, "org")}/${encodePathSegment(name, "name")}`;
}

export function versionPath(org: string, name: string, version: string): string {
  return `${packagePath(org, name)}/versions/${encodePathSegment(version, "version")}`;
}

export function yankPath(org: string, name: string, version: string): string {
  return `${versionPath(org, name, version)}/yank`;
}

export function artifactPath(sha256: string): string {
  return `/v1/artifacts/${encodePathSegment(sha256, "sha256")}`;
}

export function filePath(org: string, name: string, version: string, path: string): string {
  const encodedPath = path
    .split("/")
    .map((segment, index) => encodePathSegment(segment, `path segment ${index + 1}`))
    .join("/");
  return `/v1/files/${encodePathSegment(org, "org")}/${encodePathSegment(name, "name")}/${encodePathSegment(version, "version")}/${encodedPath}`;
}

export class ZedClient {
  private readonly base: string;
  private readonly token: string | undefined;
  private readonly fetchImpl: typeof fetch;
  private readonly timeoutMs: number;

  constructor(options: ClientOptions = {}) {
    this.base = normalizeRegistryUrl(options.registryUrl ?? DEFAULT_REGISTRY_URL);
    this.token = options.token?.trim() || undefined;
    this.fetchImpl = options.fetchImpl ?? fetch;
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    if (!Number.isFinite(this.timeoutMs) || this.timeoutMs <= 0) {
      throw new TypeError("timeoutMs must be a positive finite number");
    }
  }

  private requireToken(): string {
    if (!this.token) {
      throw new ZedApiError(
        0,
        "missing_token",
        "authenticated registry operation requires a nonblank bearer token",
      );
    }
    return this.token;
  }

  private async request<T>(
    path: string,
    init: RequestInit = {},
    authorized = false,
  ): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set("accept", "application/json");
    if (authorized) headers.set("authorization", `Bearer ${this.requireToken()}`);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await this.fetchImpl(`${this.base}${path}`, {
        ...init,
        headers,
        redirect: "error",
        signal: controller.signal,
      });

      if (!response.ok) {
        const bounded = await readAtMost(response, MAX_ERROR_BODY_BYTES);
        const text = new TextDecoder().decode(bounded.bytes);
        let body: ApiErrorBody = {
          code: `http_${response.status}`,
          message: text,
        };
        try {
          const parsed = JSON.parse(text) as Partial<ApiErrorBody> | null;
          const parsedCode = typeof parsed?.code === "string" ? parsed.code.trim() : "";
          body = {
            code: parsedCode || `http_${response.status}`,
            message: typeof parsed?.message === "string" ? parsed.message : text,
          };
        } catch {
          // Non-JSON error body remains available only through registryMessage.
        }
        if (bounded.truncated) body.message += "…";
        throw new ZedApiError(response.status, body.code, body.message);
      }

      const bytes = await readCapped(
        response,
        MAX_JSON_RESPONSE_BYTES,
        "response_too_large",
        "registry JSON response",
      );
      try {
        return JSON.parse(new TextDecoder().decode(bytes)) as T;
      } catch (error) {
        throw new ZedApiError(0, "invalid_response", `invalid registry JSON: ${String(error)}`);
      }
    } finally {
      clearTimeout(timer);
    }
  }

  getPackage(org: string, name: string): Promise<PackageMetadata> {
    return this.request(packagePath(org, name));
  }

  getVersion(org: string, name: string, version: string): Promise<VersionMetadata> {
    return this.request(versionPath(org, name, version));
  }

  search(query: string): Promise<SearchResponse> {
    return this.request(`/v1/search?q=${encodeURIComponent(query)}`);
  }

  claimOrg(slug: string): Promise<ClaimOrgResponse> {
    const checkedSlug = encodePathSegment(slug, "slug");
    return this.request(
      "/v1/orgs",
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ slug: decodeURIComponent(checkedSlug) }),
      },
      true,
    );
  }

  setYanked(org: string, name: string, version: string, yanked: boolean): Promise<YankResponse> {
    return this.request(
      yankPath(org, name, version),
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ yanked }),
      },
      true,
    );
  }

  /** Compatibility form: omitting `yanked` means yank the version. */
  yank(org: string, name: string, version: string, yanked = true): Promise<YankResponse> {
    return this.setYanked(org, name, version, yanked);
  }

  restore(org: string, name: string, version: string): Promise<YankResponse> {
    return this.setYanked(org, name, version, false);
  }

  /** Download an artifact and verify its sha256 (WebCrypto). */
  async downloadArtifact(version: VersionMetadata): Promise<Uint8Array<ArrayBuffer>> {
    const raw = version.download_url.trim();
    let url: string;
    if (raw === "") {
      url = `${this.base}${artifactPath(version.sha256)}`;
    } else if (raw.includes("://")) {
      url = allowedDownloadUrl(raw, this.base);
    } else {
      url = allowedDownloadUrl(new URL(raw, `${this.base}/`).toString(), this.base);
    }

    const limit = downloadLimit(version.size);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      // Deliberately no auth header: download_url may point at a third-party
      // host (e.g. a presigned S3/R2 URL), and the token must not leak there.
      const response = await this.fetchImpl(url, {
        redirect: "error",
        signal: controller.signal,
      });
      if (!response.ok) {
        const bounded = await readAtMost(response, MAX_ERROR_BODY_BYTES);
        throw new ZedApiError(
          response.status,
          "download_failed",
          new TextDecoder().decode(bounded.bytes),
        );
      }
      const bytes = await readCapped(response, limit, "artifact_too_large", "artifact");
      const digest = await crypto.subtle.digest("SHA-256", bytes);
      const actual = [...new Uint8Array(digest)]
        .map((byte) => byte.toString(16).padStart(2, "0"))
        .join("");
      if (actual !== version.sha256) {
        throw new ZedApiError(0, "sha256_mismatch", `expected ${version.sha256}, got ${actual}`);
      }
      return bytes;
    } finally {
      clearTimeout(timer);
    }
  }

  /** Publish: multipart meta (PublishMeta JSON) + artifact bytes. */
  async publish(
    meta: { manifest: { package: { org: string; name: string; version: string } } } & Record<
      string,
      unknown
    >,
    artifact: Blob,
  ): Promise<PublishResponse> {
    if (artifact.size > MAX_ARTIFACT_BYTES) {
      throw new ZedApiError(
        0,
        "artifact_too_large",
        `artifact exceeded ${MAX_ARTIFACT_BYTES} bytes; refusing`,
      );
    }
    const pkg = meta.manifest.package;
    const org = decodeURIComponent(encodePathSegment(pkg.org, "meta.manifest.package.org"));
    const name = decodeURIComponent(encodePathSegment(pkg.name, "meta.manifest.package.name"));
    const version = decodeURIComponent(
      encodePathSegment(pkg.version, "meta.manifest.package.version"),
    );
    const form = new FormData();
    form.set("meta", JSON.stringify(meta));
    form.set("artifact", artifact, `${org}-${name}-${version}.tar.gz`);
    return this.request(
      versionPath(org, name, version),
      {
        method: "PUT",
        body: form,
      },
      true,
    );
  }
}
