import type { NixPackageIdentity } from "./nix-export-plan.js";

export const NIX_ADAPTER_SCHEMA_V1 = "zed.nix-adapter/v1" as const;

export type NixAdapterDirection = "zed-to-nix" | "nix-to-zed";
export type NixAdapterArtifactFormat = "tar.gz" | "zip";
export type NixAdapterPolicyProfile = "strict-v1" | "development";
export type NixAdapterBuilderNetwork = "disabled" | "preparation-only" | "allowed";

export interface NixAdapterArtifact {
  format: NixAdapterArtifactFormat;
  sha256: string;
  size: number;
}

export interface NixAdapterPolicyEvidence {
  profile: NixAdapterPolicyProfile;
  pure_evaluation: boolean;
  import_from_derivation: boolean;
  sandbox_required: boolean;
  builder_network: NixAdapterBuilderNetwork;
  dirty_source: boolean;
  publishable: boolean;
}

export interface NixAdapterExportIntent {
  mode: "artifact";
  attribute?: string;
  systems: string[];
  outputs: string[];
}

export interface ZedArtifactOrigin {
  registry: string;
  artifact: NixAdapterArtifact;
  vcs_tag: string;
  vcs_commit: string;
  lock_sha256?: string;
}

export interface NixStoreReference {
  store_path: string;
  nar_hash?: string;
  nar_size?: number;
}

export interface NixRealizedOutput {
  system: string;
  output: string;
  derivation_json_sha256: string;
  store_path: string;
  nar_hash: string;
  nar_size: number;
  references?: NixStoreReference[];
  signatures?: string[];
  nix_version: string;
  store_info_json_version: 1 | 2 | 3;
}

export interface NixOutputOrigin {
  locked_ref: string;
  flake_lock_sha256: string;
  attribute: string;
  realized: NixRealizedOutput;
}

export interface ZedToNixAdapterRecord {
  direction: "zed-to-nix";
  schema: typeof NIX_ADAPTER_SCHEMA_V1;
  package: NixPackageIdentity;
  source: ZedArtifactOrigin;
  intent: NixAdapterExportIntent;
  flake_bundle_sha256: string;
  outputs: NixRealizedOutput[];
  policy: NixAdapterPolicyEvidence;
}

export interface NixToZedAdapterRecord {
  direction: "nix-to-zed";
  schema: typeof NIX_ADAPTER_SCHEMA_V1;
  package: NixPackageIdentity;
  source: NixOutputOrigin;
  artifact: NixAdapterArtifact;
  policy: NixAdapterPolicyEvidence;
}

export type NixAdapterRecord = ZedToNixAdapterRecord | NixToZedAdapterRecord;

export interface VerifiedCanonicalNixAdapterRecord {
  record: NixAdapterRecord;
  canonical_json: string;
  sha256: string;
}

export interface VerifiedNixAdapterArtifact {
  record: NixAdapterRecord;
  sha256: string;
  size: number;
}

export class NixAdapterRecordError extends TypeError {
  constructor(message: string) {
    super(`invalid Nix adapter record: ${message}`);
    this.name = "NixAdapterRecordError";
  }
}

type JsonRecord = Record<string, unknown>;

const SHA256_RE = /^[0-9a-f]{64}$/;
const SHA256_SRI_RE = /^sha256-[A-Za-z0-9+/]{43}=$/;
const SLUG_RE = /^[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?$/;
const TARGET_RE = /^[a-z0-9](?:[a-z0-9._+-]{0,126}[a-z0-9])?$/;
const NIX_IDENTIFIER_RE = /^[A-Za-z_][A-Za-z0-9_'-]*$/;
const NIX_STORE_RE = /^\/nix\/store\/[0-9abcdfghijklmnpqrsvwxyz]{32}-[A-Za-z0-9+._?-]+$/;
const HEX_REVISION_RE = /^[0-9a-fA-F]{40}(?:[0-9a-fA-F]{24})?$/;

function plainRecord(value: unknown, label: string): JsonRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new NixAdapterRecordError(`${label} must be an object`);
  }
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) {
    throw new NixAdapterRecordError(`${label} must be a plain JSON object`);
  }
  return value as JsonRecord;
}

function exactKeys(
  value: JsonRecord,
  required: readonly string[],
  optional: readonly string[],
  label: string,
): void {
  const allowed = new Set([...required, ...optional]);
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) {
      throw new NixAdapterRecordError(`${label} contains unknown field ${JSON.stringify(key)}`);
    }
  }
  for (const key of required) {
    if (!Object.prototype.hasOwnProperty.call(value, key)) {
      throw new NixAdapterRecordError(`${label} is missing field ${JSON.stringify(key)}`);
    }
  }
}

function text(value: unknown, label: string): string {
  if (typeof value !== "string") {
    throw new NixAdapterRecordError(`${label} must be a string`);
  }
  return value;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[], label: string): T {
  const parsed = text(value, label);
  if (!allowed.includes(parsed as T)) {
    throw new NixAdapterRecordError(
      `${label} must be one of ${allowed.map((item) => JSON.stringify(item)).join(", ")}`,
    );
  }
  return parsed as T;
}

function boolean(value: unknown, label: string): boolean {
  if (typeof value !== "boolean") {
    throw new NixAdapterRecordError(`${label} must be a boolean`);
  }
  return value;
}

function positiveSafeInteger(value: unknown, label: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
    throw new NixAdapterRecordError(`${label} must be a positive safe integer`);
  }
  return value;
}

function supportedStoreInfoVersion(value: unknown, label: string): 1 | 2 | 3 {
  if (value !== 1 && value !== 2 && value !== 3) {
    throw new NixAdapterRecordError(`${label} must be 1, 2, or 3`);
  }
  return value;
}

function hexSha256(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (!SHA256_RE.test(parsed)) {
    throw new NixAdapterRecordError(`${label} must be 64 lowercase hexadecimal characters`);
  }
  return parsed;
}

function sriSha256(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (!SHA256_SRI_RE.test(parsed)) {
    throw new NixAdapterRecordError(`${label} must be a SHA-256 SRI value`);
  }
  return parsed;
}

function token(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (parsed.length === 0 || parsed.trim() !== parsed || /\s/u.test(parsed)) {
    throw new NixAdapterRecordError(`${label} must be a non-empty token without whitespace`);
  }
  return parsed;
}

function slug(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (!SLUG_RE.test(parsed)) {
    throw new NixAdapterRecordError(`${label} is not a valid public Zed slug`);
  }
  return parsed;
}

function packageVersion(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (parsed.length === 0 || parsed.trim() !== parsed || /\s/u.test(parsed)) {
    throw new NixAdapterRecordError(`${label} must be non-empty and contain no whitespace`);
  }
  return parsed;
}

function target(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (!TARGET_RE.test(parsed)) {
    throw new NixAdapterRecordError(`${label} is not a valid target name`);
  }
  return parsed;
}

function nixIdentifier(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (!NIX_IDENTIFIER_RE.test(parsed)) {
    throw new NixAdapterRecordError(`${label} must be a Nix identifier`);
  }
  return parsed;
}

function nixAttributePath(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (parsed.length === 0 || !parsed.split(".").every((part) => NIX_IDENTIFIER_RE.test(part))) {
    throw new NixAdapterRecordError(`${label} must be a dot-separated Nix attribute path`);
  }
  return parsed;
}

function nixSystem(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (
    parsed.length === 0 ||
    !parsed.includes("-") ||
    parsed.startsWith("-") ||
    parsed.endsWith("-") ||
    !/^[a-z0-9_-]+$/u.test(parsed)
  ) {
    throw new NixAdapterRecordError(`${label} must be a lowercase Nix system`);
  }
  return parsed;
}

function nixStorePath(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (!NIX_STORE_RE.test(parsed)) {
    throw new NixAdapterRecordError(`${label} must be a valid /nix/store path`);
  }
  return parsed;
}

function immutableNixRef(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (
    parsed.length === 0 ||
    parsed.trim() !== parsed ||
    /\s/u.test(parsed) ||
    parsed.includes("<") ||
    parsed.includes(">")
  ) {
    throw new NixAdapterRecordError(`${label} must be an immutable Nix reference`);
  }
  if (
    parsed.startsWith("/nix/store/") ||
    parsed.startsWith("path:/nix/store/") ||
    parsed.includes("narHash=sha256-")
  ) {
    return parsed;
  }
  const queryRevision = parsed
    .split(/[?&]/u)
    .map((part) => part.startsWith("rev=") ? part.slice(4) : "")
    .find((part) => HEX_REVISION_RE.test(part));
  if (queryRevision !== undefined) {
    return parsed;
  }
  if (parsed.split(/[^0-9a-fA-F]+/u).some((part) => HEX_REVISION_RE.test(part))) {
    return parsed;
  }
  throw new NixAdapterRecordError(`${label} must contain immutable revision or NAR-hash evidence`);
}

function registryUrl(value: unknown, label: string): string {
  const parsed = text(value, label);
  if (
    parsed.trim() !== parsed ||
    /\s/u.test(parsed) ||
    !["https://", "http://", "file://"].some((prefix) => parsed.startsWith(prefix))
  ) {
    throw new NixAdapterRecordError(`${label} must be an HTTP(S) or file URL without whitespace`);
  }
  return parsed;
}

function unique<T>(values: T[], key: (value: T) => string, label: string): void {
  const seen = new Set<string>();
  for (const value of values) {
    const identity = key(value);
    if (seen.has(identity)) {
      throw new NixAdapterRecordError(`${label} contains duplicate values`);
    }
    seen.add(identity);
  }
}

function stringArray(value: unknown, label: string): string[] {
  if (!Array.isArray(value)) {
    throw new NixAdapterRecordError(`${label} must be an array`);
  }
  return value.map((item, index) => text(item, `${label}[${index}]`));
}

function parsePackage(value: unknown): NixPackageIdentity {
  const parsed = plainRecord(value, "package");
  exactKeys(parsed, ["org", "name", "version"], ["target"], "package");
  const result: NixPackageIdentity = {
    org: slug(parsed.org, "package.org"),
    name: slug(parsed.name, "package.name"),
    version: packageVersion(parsed.version, "package.version"),
  };
  if (parsed.target !== undefined && parsed.target !== null) {
    result.target = target(parsed.target, "package.target");
  }
  return result;
}

function parseArtifact(value: unknown, label: string): NixAdapterArtifact {
  const parsed = plainRecord(value, label);
  exactKeys(parsed, ["sha256", "size"], ["format"], label);
  return {
    format: parsed.format === undefined
      ? "tar.gz"
      : oneOf(parsed.format, ["tar.gz", "zip"], `${label}.format`),
    sha256: hexSha256(parsed.sha256, `${label}.sha256`),
    size: positiveSafeInteger(parsed.size, `${label}.size`),
  };
}

function parsePolicy(value: unknown): NixAdapterPolicyEvidence {
  const parsed = plainRecord(value, "policy");
  exactKeys(
    parsed,
    [
      "profile",
      "pure_evaluation",
      "import_from_derivation",
      "sandbox_required",
      "builder_network",
      "dirty_source",
      "publishable",
    ],
    [],
    "policy",
  );
  const result: NixAdapterPolicyEvidence = {
    profile: oneOf(parsed.profile, ["strict-v1", "development"], "policy.profile"),
    pure_evaluation: boolean(parsed.pure_evaluation, "policy.pure_evaluation"),
    import_from_derivation: boolean(
      parsed.import_from_derivation,
      "policy.import_from_derivation",
    ),
    sandbox_required: boolean(parsed.sandbox_required, "policy.sandbox_required"),
    builder_network: oneOf(
      parsed.builder_network,
      ["disabled", "preparation-only", "allowed"],
      "policy.builder_network",
    ),
    dirty_source: boolean(parsed.dirty_source, "policy.dirty_source"),
    publishable: boolean(parsed.publishable, "policy.publishable"),
  };
  if (result.profile === "strict-v1") {
    if (
      !result.pure_evaluation ||
      result.import_from_derivation ||
      !result.sandbox_required ||
      result.builder_network !== "disabled" ||
      result.dirty_source ||
      !result.publishable
    ) {
      throw new NixAdapterRecordError("strict-v1 policy evidence is inconsistent");
    }
  } else if (result.publishable) {
    throw new NixAdapterRecordError("development policy records are never publishable");
  }
  return result;
}

function parseExportIntent(value: unknown, packageName: string): NixAdapterExportIntent {
  const parsed = plainRecord(value, "intent");
  exactKeys(parsed, [], ["mode", "attribute", "systems", "outputs"], "intent");
  const attribute = parsed.attribute === undefined || parsed.attribute === null
    ? undefined
    : nixIdentifier(parsed.attribute, "intent.attribute");
  const resolvedAttribute = attribute ?? packageName;
  if (resolvedAttribute === "default") {
    throw new NixAdapterRecordError("intent.attribute must not use reserved `default`");
  }
  const systems = (parsed.systems === undefined ? [] : stringArray(parsed.systems, "intent.systems"))
    .map((item, index) => nixSystem(item, `intent.systems[${index}]`));
  const outputs = (parsed.outputs === undefined ? [] : stringArray(parsed.outputs, "intent.outputs"))
    .map((item, index) => nixIdentifier(item, `intent.outputs[${index}]`));
  if (systems.length === 0 || outputs.length === 0) {
    throw new NixAdapterRecordError("intent requires explicit systems and outputs");
  }
  unique(systems, (item) => item, "intent.systems");
  unique(outputs, (item) => item, "intent.outputs");
  const result: NixAdapterExportIntent = {
    mode: parsed.mode === undefined
      ? "artifact"
      : oneOf(parsed.mode, ["artifact"], "intent.mode"),
    systems: [...systems].sort(),
    outputs: [...outputs].sort(),
  };
  if (attribute !== undefined) {
    result.attribute = attribute;
  }
  return result;
}

function parseZedOrigin(value: unknown): ZedArtifactOrigin {
  const parsed = plainRecord(value, "source");
  exactKeys(
    parsed,
    ["registry", "artifact", "vcs_tag", "vcs_commit"],
    ["lock_sha256"],
    "source",
  );
  const vcsCommit = token(parsed.vcs_commit, "source.vcs_commit");
  if (vcsCommit.length < 7) {
    throw new NixAdapterRecordError("source.vcs_commit must be an immutable identifier");
  }
  const result: ZedArtifactOrigin = {
    registry: registryUrl(parsed.registry, "source.registry"),
    artifact: parseArtifact(parsed.artifact, "source.artifact"),
    vcs_tag: token(parsed.vcs_tag, "source.vcs_tag"),
    vcs_commit: vcsCommit,
  };
  if (parsed.lock_sha256 !== undefined && parsed.lock_sha256 !== null) {
    result.lock_sha256 = hexSha256(parsed.lock_sha256, "source.lock_sha256");
  }
  return result;
}

function parseStoreReference(value: unknown, label: string): NixStoreReference {
  const parsed = plainRecord(value, label);
  exactKeys(parsed, ["store_path"], ["nar_hash", "nar_size"], label);
  const result: NixStoreReference = {
    store_path: nixStorePath(parsed.store_path, `${label}.store_path`),
  };
  if (parsed.nar_hash !== undefined && parsed.nar_hash !== null) {
    result.nar_hash = sriSha256(parsed.nar_hash, `${label}.nar_hash`);
  }
  if (parsed.nar_size !== undefined && parsed.nar_size !== null) {
    result.nar_size = positiveSafeInteger(parsed.nar_size, `${label}.nar_size`);
  }
  return result;
}

function parseRealizedOutput(value: unknown, label: string): NixRealizedOutput {
  const parsed = plainRecord(value, label);
  exactKeys(
    parsed,
    [
      "system",
      "output",
      "derivation_json_sha256",
      "store_path",
      "nar_hash",
      "nar_size",
      "nix_version",
      "store_info_json_version",
    ],
    ["references", "signatures"],
    label,
  );
  const references = parsed.references === undefined
    ? []
    : Array.isArray(parsed.references)
      ? parsed.references.map((item, index) => parseStoreReference(item, `${label}.references[${index}]`))
      : (() => {
          throw new NixAdapterRecordError(`${label}.references must be an array`);
        })();
  unique(references, (item) => item.store_path, `${label}.references`);

  const signatures = parsed.signatures === undefined
    ? []
    : stringArray(parsed.signatures, `${label}.signatures`)
      .map((item, index) => token(item, `${label}.signatures[${index}]`));
  unique(signatures, (item) => item, `${label}.signatures`);

  const nixVersion = text(parsed.nix_version, `${label}.nix_version`);
  if (nixVersion.trim().length === 0) {
    throw new NixAdapterRecordError(`${label}.nix_version must be recorded`);
  }

  const result: NixRealizedOutput = {
    system: nixSystem(parsed.system, `${label}.system`),
    output: nixIdentifier(parsed.output, `${label}.output`),
    derivation_json_sha256: hexSha256(
      parsed.derivation_json_sha256,
      `${label}.derivation_json_sha256`,
    ),
    store_path: nixStorePath(parsed.store_path, `${label}.store_path`),
    nar_hash: sriSha256(parsed.nar_hash, `${label}.nar_hash`),
    nar_size: positiveSafeInteger(parsed.nar_size, `${label}.nar_size`),
    nix_version: nixVersion,
    store_info_json_version: supportedStoreInfoVersion(
      parsed.store_info_json_version,
      `${label}.store_info_json_version`,
    ),
  };
  if (references.length !== 0) {
    result.references = [...references]
      .sort((left, right) => left.store_path.localeCompare(right.store_path));
  }
  if (signatures.length !== 0) {
    result.signatures = [...signatures].sort();
  }
  return result;
}

function parseNixOrigin(value: unknown): NixOutputOrigin {
  const parsed = plainRecord(value, "source");
  exactKeys(
    parsed,
    ["locked_ref", "flake_lock_sha256", "attribute", "realized"],
    [],
    "source",
  );
  return {
    locked_ref: immutableNixRef(parsed.locked_ref, "source.locked_ref"),
    flake_lock_sha256: hexSha256(parsed.flake_lock_sha256, "source.flake_lock_sha256"),
    attribute: nixAttributePath(parsed.attribute, "source.attribute"),
    realized: parseRealizedOutput(parsed.realized, "source.realized"),
  };
}

function parseZedToNix(parsed: JsonRecord): ZedToNixAdapterRecord {
  exactKeys(
    parsed,
    [
      "direction",
      "schema",
      "package",
      "source",
      "intent",
      "flake_bundle_sha256",
      "outputs",
      "policy",
    ],
    [],
    "record",
  );
  const pkg = parsePackage(parsed.package);
  const intent = parseExportIntent(parsed.intent, pkg.name);
  if (!Array.isArray(parsed.outputs) || parsed.outputs.length === 0) {
    throw new NixAdapterRecordError("outputs must contain realized evidence");
  }
  const outputs = parsed.outputs
    .map((item, index) => parseRealizedOutput(item, `outputs[${index}]`));
  unique(outputs, (item) => `${item.system}\u0000${item.output}`, "outputs");
  const declaredSystems = new Set(intent.systems);
  const declaredOutputs = new Set(intent.outputs);
  const realizedSystems = new Set<string>();
  for (const output of outputs) {
    if (!declaredSystems.has(output.system) || !declaredOutputs.has(output.output)) {
      throw new NixAdapterRecordError("realized output is outside declared intent");
    }
    realizedSystems.add(output.system);
  }
  for (const system of declaredSystems) {
    if (!realizedSystems.has(system)) {
      throw new NixAdapterRecordError("a declared system lacks realized evidence");
    }
  }
  outputs.sort((left, right) => {
    const bySystem = left.system.localeCompare(right.system);
    return bySystem === 0 ? left.output.localeCompare(right.output) : bySystem;
  });
  return {
    direction: oneOf(parsed.direction, ["zed-to-nix"], "direction"),
    schema: oneOf(parsed.schema, [NIX_ADAPTER_SCHEMA_V1], "schema"),
    package: pkg,
    source: parseZedOrigin(parsed.source),
    intent,
    flake_bundle_sha256: hexSha256(parsed.flake_bundle_sha256, "flake_bundle_sha256"),
    outputs,
    policy: parsePolicy(parsed.policy),
  };
}

function parseNixToZed(parsed: JsonRecord): NixToZedAdapterRecord {
  exactKeys(
    parsed,
    ["direction", "schema", "package", "source", "artifact", "policy"],
    [],
    "record",
  );
  const source = parseNixOrigin(parsed.source);
  if ((source.realized.references?.length ?? 0) !== 0) {
    throw new NixAdapterRecordError(
      "contract v1 Nix-to-Zed imports must be closure-free",
    );
  }
  return {
    direction: oneOf(parsed.direction, ["nix-to-zed"], "direction"),
    schema: oneOf(parsed.schema, [NIX_ADAPTER_SCHEMA_V1], "schema"),
    package: parsePackage(parsed.package),
    source,
    artifact: parseArtifact(parsed.artifact, "artifact"),
    policy: parsePolicy(parsed.policy),
  };
}

/** Parse and normalize one strict `zed.nix-adapter/v1` record. */
export function parseNixAdapterRecord(value: unknown): NixAdapterRecord {
  const parsed = plainRecord(value, "record");
  const direction = text(parsed.direction, "direction");
  if (direction === "zed-to-nix") {
    return parseZedToNix(parsed);
  }
  if (direction === "nix-to-zed") {
    return parseNixToZed(parsed);
  }
  throw new NixAdapterRecordError("direction is unsupported");
}

/** Parse untrusted adapter JSON without echoing the source document on error. */
export function parseNixAdapterRecordJson(json: string): NixAdapterRecord {
  let value: unknown;
  try {
    value = JSON.parse(json) as unknown;
  } catch {
    throw new NixAdapterRecordError("input is not valid JSON");
  }
  return parseNixAdapterRecord(value);
}

function canonicalizeJson(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(canonicalizeJson);
  }
  if (typeof value === "object" && value !== null) {
    const record = value as JsonRecord;
    const result: JsonRecord = {};
    for (const key of Object.keys(record).sort()) {
      result[key] = canonicalizeJson(record[key]);
    }
    return result;
  }
  return value;
}

/** Rust-compatible compact JSON with recursively lexicographic object keys. */
export function canonicalNixAdapterRecordJson(value: unknown): string {
  return JSON.stringify(canonicalizeJson(parseNixAdapterRecord(value)));
}

function subtleCrypto(): SubtleCrypto {
  const subtle = globalThis.crypto?.subtle;
  if (subtle === undefined) {
    throw new NixAdapterRecordError("Web Crypto SHA-256 is unavailable");
  }
  return subtle;
}

async function sha256Hex(value: string | Uint8Array): Promise<string> {
  const bytes = typeof value === "string"
    ? new TextEncoder().encode(value)
    : Uint8Array.from(value);
  const digest = await subtleCrypto().digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

/** SHA-256 hex of the canonical adapter bytes. */
export async function nixAdapterRecordSha256(value: unknown): Promise<string> {
  return sha256Hex(canonicalNixAdapterRecordJson(value));
}

/**
 * Verify that external adapter text is already canonical and optionally bound
 * to an expected digest. This is structural/offline verification only; it does
 * not replay Nix realization or prove that a store path currently exists.
 */
export async function verifyCanonicalNixAdapterRecordJson(
  json: string,
  expectedSha256?: string,
): Promise<VerifiedCanonicalNixAdapterRecord> {
  const record = parseNixAdapterRecordJson(json);
  const canonicalJson = canonicalNixAdapterRecordJson(record);
  if (json !== canonicalJson) {
    throw new NixAdapterRecordError("input bytes are not canonical compact JSON");
  }
  const digest = await sha256Hex(canonicalJson);
  if (expectedSha256 !== undefined) {
    const expected = hexSha256(expectedSha256, "expected adapter SHA-256");
    if (digest !== expected) {
      throw new NixAdapterRecordError("canonical adapter SHA-256 mismatch");
    }
  }
  return { record, canonical_json: canonicalJson, sha256: digest };
}

/**
 * Bind exact Zed artifact bytes to the artifact identity carried by the record.
 * Nix-to-Zed records verify the translated artifact; Zed-to-Nix records verify
 * the source Zed artifact. Nix realization claims still require CLI/store replay.
 */
export async function verifyNixAdapterArtifactBytes(
  value: unknown,
  bytes: Uint8Array,
): Promise<VerifiedNixAdapterArtifact> {
  const record = parseNixAdapterRecord(value);
  const artifact = record.direction === "nix-to-zed"
    ? record.artifact
    : record.source.artifact;
  const digest = await sha256Hex(bytes);
  if (digest !== artifact.sha256) {
    throw new NixAdapterRecordError("Zed artifact SHA-256 mismatch");
  }
  if (bytes.byteLength !== artifact.size) {
    throw new NixAdapterRecordError("Zed artifact size mismatch");
  }
  return { record, sha256: digest, size: bytes.byteLength };
}
