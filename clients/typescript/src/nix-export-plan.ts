export const NIX_EXPORT_PLAN_SCHEMA_V1 = "zed.nix-export-plan/v1" as const;

export type NixExportPackageClass = "data" | "prebuilt-bin";
export type NixExportMode = "artifact";
export type NixPolicyProfile = "strict-v1";
export type NixBuilderNetwork = "disabled";
export type NixArtifactFormat = "tar.gz" | "zip";

export interface NixPackageIdentity {
  org: string;
  name: string;
  version: string;
  target?: string;
}

export interface ResolvedNixExportIntent {
  mode: NixExportMode;
  attribute: string;
  systems: string[];
  outputs: string[];
}

export interface NixInteropArtifact {
  format: NixArtifactFormat;
  sha256: string;
  size: number;
}

export interface PlannedZedExportArtifact {
  file_name: string;
  artifact: NixInteropArtifact;
  manifest_sha256: string;
  lock_sha256: string;
}

export interface PlannedNixExportDependency {
  org: string;
  name: string;
  version: string;
  sha256: string;
}

export interface NixPolicyEvidence {
  profile: NixPolicyProfile;
  pure_evaluation: true;
  import_from_derivation: false;
  sandbox_required: true;
  builder_network: NixBuilderNetwork;
  dirty_source: false;
  publishable: true;
}

export interface NixExportPlan {
  schema: typeof NIX_EXPORT_PLAN_SCHEMA_V1;
  package: NixPackageIdentity;
  package_class: NixExportPackageClass;
  intent: ResolvedNixExportIntent;
  source: PlannedZedExportArtifact;
  bins: Record<string, string>;
  dependencies: PlannedNixExportDependency[];
  policy: NixPolicyEvidence;
}

export class NixExportPlanError extends TypeError {
  constructor(message: string) {
    super(`invalid Nix export plan: ${message}`);
    this.name = "NixExportPlanError";
  }
}

type JsonRecord = Record<string, unknown>;

const SHA256_RE = /^[0-9a-f]{64}$/;
const SLUG_RE = /^[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?$/;
const TARGET_RE = /^[a-z0-9](?:[a-z0-9._+-]{0,126}[a-z0-9])?$/;
const NIX_IDENTIFIER_RE = /^[A-Za-z_][A-Za-z0-9_'-]*$/;
const NIX_SYSTEM_RE = /^[a-z0-9_]+(?:-[a-z0-9_]+)+$/;
const BIN_NAME_RE = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,126}[A-Za-z0-9])?$/;

const ROOT_KEYS = [
  "schema",
  "package",
  "package_class",
  "intent",
  "source",
  "bins",
  "dependencies",
  "policy",
] as const;

function record(value: unknown, label: string): JsonRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new NixExportPlanError(`${label} must be an object`);
  }
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) {
    throw new NixExportPlanError(`${label} must be a plain JSON object`);
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
      throw new NixExportPlanError(`${label} contains unknown field ${JSON.stringify(key)}`);
    }
  }
  for (const key of required) {
    if (!Object.prototype.hasOwnProperty.call(value, key)) {
      throw new NixExportPlanError(`${label} is missing field ${JSON.stringify(key)}`);
    }
  }
}

function string(value: unknown, label: string): string {
  if (typeof value !== "string") {
    throw new NixExportPlanError(`${label} must be a string`);
  }
  return value;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[], label: string): T {
  const parsed = string(value, label);
  if (!allowed.includes(parsed as T)) {
    throw new NixExportPlanError(
      `${label} must be one of ${allowed.map((item) => JSON.stringify(item)).join(", ")}`,
    );
  }
  return parsed as T;
}

function boolLiteral<T extends boolean>(value: unknown, expected: T, label: string): T {
  if (value !== expected) {
    throw new NixExportPlanError(`${label} must be ${String(expected)}`);
  }
  return expected;
}

function positiveSafeInteger(value: unknown, label: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
    throw new NixExportPlanError(`${label} must be a positive safe integer`);
  }
  return value;
}

function sha256(value: unknown, label: string): string {
  const parsed = string(value, label);
  if (!SHA256_RE.test(parsed)) {
    throw new NixExportPlanError(`${label} must be 64 lowercase hexadecimal characters`);
  }
  return parsed;
}

function slug(value: unknown, label: string): string {
  const parsed = string(value, label);
  if (!SLUG_RE.test(parsed)) {
    throw new NixExportPlanError(`${label} is not a valid public Zed slug`);
  }
  return parsed;
}

function version(value: unknown, label: string): string {
  const parsed = string(value, label);
  if (
    parsed.length === 0 ||
    parsed.length > 256 ||
    parsed.trim() !== parsed ||
    /[\s/\\\u0000-\u001f\u007f]/u.test(parsed)
  ) {
    throw new NixExportPlanError(`${label} must be non-empty and filename-safe`);
  }
  return parsed;
}

function target(value: unknown, label: string): string {
  const parsed = string(value, label);
  if (!TARGET_RE.test(parsed)) {
    throw new NixExportPlanError(`${label} is not a valid target name`);
  }
  return parsed;
}

function sortedUniqueStrings(
  value: unknown,
  label: string,
  validate: (item: string, itemLabel: string) => string,
): string[] {
  if (!Array.isArray(value) || value.length === 0) {
    throw new NixExportPlanError(`${label} must be a non-empty array`);
  }
  const parsed = value.map((item, index) => validate(string(item, `${label}[${index}]`), `${label}[${index}]`));
  for (let index = 1; index < parsed.length; index += 1) {
    if (parsed[index - 1] >= parsed[index]) {
      throw new NixExportPlanError(`${label} must be sorted and unique`);
    }
  }
  return parsed;
}

function nixSystem(value: string, label: string): string {
  if (!NIX_SYSTEM_RE.test(value)) {
    throw new NixExportPlanError(`${label} must be a lowercase Nix system`);
  }
  return value;
}

function nixIdentifier(value: string, label: string): string {
  if (!NIX_IDENTIFIER_RE.test(value)) {
    throw new NixExportPlanError(`${label} must be a Nix identifier`);
  }
  return value;
}

function nixAttributePath(value: string, label: string): string {
  if (value.length === 0 || !value.split(".").every((part) => NIX_IDENTIFIER_RE.test(part))) {
    throw new NixExportPlanError(`${label} must be a dot-separated Nix attribute path`);
  }
  return value;
}

function artifactRelativePath(value: unknown, label: string): string {
  const parsed = string(value, label);
  if (
    parsed.length === 0 ||
    parsed.startsWith("/") ||
    parsed.endsWith("/") ||
    parsed.includes("\\") ||
    parsed.split("/").some((part) => part.length === 0 || part === "." || part === ".." || /[\u0000-\u001f\u007f]/u.test(part))
  ) {
    throw new NixExportPlanError(`${label} must be a safe forward-slash artifact-relative path`);
  }
  return parsed;
}

function parsePackage(value: unknown): NixPackageIdentity {
  const parsed = record(value, "package");
  exactKeys(parsed, ["org", "name", "version"], ["target"], "package");
  const result: NixPackageIdentity = {
    org: slug(parsed.org, "package.org"),
    name: slug(parsed.name, "package.name"),
    version: version(parsed.version, "package.version"),
  };
  if (parsed.target !== undefined) {
    result.target = target(parsed.target, "package.target");
  }
  return result;
}

function parseIntent(value: unknown): ResolvedNixExportIntent {
  const parsed = record(value, "intent");
  exactKeys(parsed, ["mode", "attribute", "systems", "outputs"], [], "intent");
  return {
    mode: oneOf(parsed.mode, ["artifact"], "intent.mode"),
    attribute: nixAttributePath(string(parsed.attribute, "intent.attribute"), "intent.attribute"),
    systems: sortedUniqueStrings(parsed.systems, "intent.systems", nixSystem),
    outputs: sortedUniqueStrings(parsed.outputs, "intent.outputs", nixIdentifier),
  };
}

function parseArtifact(value: unknown): NixInteropArtifact {
  const parsed = record(value, "source.artifact");
  exactKeys(parsed, ["format", "sha256", "size"], [], "source.artifact");
  return {
    format: oneOf(parsed.format, ["tar.gz", "zip"], "source.artifact.format"),
    sha256: sha256(parsed.sha256, "source.artifact.sha256"),
    size: positiveSafeInteger(parsed.size, "source.artifact.size"),
  };
}

function parseSource(value: unknown, pkg: NixPackageIdentity): PlannedZedExportArtifact {
  const parsed = record(value, "source");
  exactKeys(
    parsed,
    ["file_name", "artifact", "manifest_sha256", "lock_sha256"],
    [],
    "source",
  );
  const artifact = parseArtifact(parsed.artifact);
  const expectedName = `${pkg.org}-${pkg.name}-${pkg.version}.${artifact.format}`;
  const fileName = string(parsed.file_name, "source.file_name");
  if (fileName !== expectedName || fileName.startsWith(".") || fileName.includes("/") || fileName.includes("\\") || /\s/u.test(fileName)) {
    throw new NixExportPlanError(
      `source.file_name must be the canonical safe basename ${JSON.stringify(expectedName)}`,
    );
  }
  return {
    file_name: fileName,
    artifact,
    manifest_sha256: sha256(parsed.manifest_sha256, "source.manifest_sha256"),
    lock_sha256: sha256(parsed.lock_sha256, "source.lock_sha256"),
  };
}

function parseBins(value: unknown): Record<string, string> {
  const parsed = record(value, "bins");
  const result: Record<string, string> = {};
  for (const name of Object.keys(parsed).sort()) {
    if (!BIN_NAME_RE.test(name)) {
      throw new NixExportPlanError(`bins contains invalid executable name ${JSON.stringify(name)}`);
    }
    result[name] = artifactRelativePath(parsed[name], `bins.${name}`);
  }
  return result;
}

function parseDependencies(value: unknown): PlannedNixExportDependency[] {
  if (!Array.isArray(value)) {
    throw new NixExportPlanError("dependencies must be an array");
  }
  if (value.length !== 0) {
    throw new NixExportPlanError("contract v1 accepts dependency-free packages only");
  }
  return [];
}

function parsePolicy(value: unknown): NixPolicyEvidence {
  const parsed = record(value, "policy");
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
  return {
    profile: oneOf(parsed.profile, ["strict-v1"], "policy.profile"),
    pure_evaluation: boolLiteral(parsed.pure_evaluation, true, "policy.pure_evaluation"),
    import_from_derivation: boolLiteral(
      parsed.import_from_derivation,
      false,
      "policy.import_from_derivation",
    ),
    sandbox_required: boolLiteral(parsed.sandbox_required, true, "policy.sandbox_required"),
    builder_network: oneOf(parsed.builder_network, ["disabled"], "policy.builder_network"),
    dirty_source: boolLiteral(parsed.dirty_source, false, "policy.dirty_source"),
    publishable: boolLiteral(parsed.publishable, true, "policy.publishable"),
  };
}

/** Parse and normalize one strict `zed.nix-export-plan/v1` JSON value. */
export function parseNixExportPlan(value: unknown): NixExportPlan {
  const parsed = record(value, "plan");
  exactKeys(
    parsed,
    ["schema", "package", "package_class", "intent", "source", "policy"],
    ["bins", "dependencies"],
    "plan",
  );
  const schema = oneOf(parsed.schema, [NIX_EXPORT_PLAN_SCHEMA_V1], "schema");
  const pkg = parsePackage(parsed.package);
  const packageClass = oneOf(
    parsed.package_class,
    ["data", "prebuilt-bin"],
    "package_class",
  );
  const bins = parseBins(parsed.bins ?? {});
  if (packageClass === "data" && Object.keys(bins).length !== 0) {
    throw new NixExportPlanError("data packages must have an empty bins object");
  }
  if (packageClass === "prebuilt-bin" && Object.keys(bins).length === 0) {
    throw new NixExportPlanError("prebuilt-bin packages must declare at least one executable");
  }
  return {
    schema,
    package: pkg,
    package_class: packageClass,
    intent: parseIntent(parsed.intent),
    source: parseSource(parsed.source, pkg),
    bins,
    dependencies: parseDependencies(parsed.dependencies ?? []),
    policy: parsePolicy(parsed.policy),
  };
}

/** Parse untrusted JSON text without exposing parser internals or source values. */
export function parseNixExportPlanJson(json: string): NixExportPlan {
  let value: unknown;
  try {
    value = JSON.parse(json) as unknown;
  } catch {
    throw new NixExportPlanError("input is not valid JSON");
  }
  return parseNixExportPlan(value);
}

/** Stable compact JSON using the canonical schema field order and sorted bins. */
export function canonicalNixExportPlanJson(value: unknown): string {
  return JSON.stringify(parseNixExportPlan(value));
}
