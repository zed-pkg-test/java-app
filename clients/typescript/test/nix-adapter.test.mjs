import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";

import {
  NIX_ADAPTER_SCHEMA_V1,
  NixAdapterRecordError,
  canonicalNixAdapterRecordJson,
  nixAdapterRecordSha256,
  parseNixAdapterRecord,
  parseNixAdapterRecordJson,
  verifyCanonicalNixAdapterRecordJson,
  verifyNixAdapterArtifactBytes,
} from "../dist/index.js";

const HEX_A = "a".repeat(64);
const HEX_B = "b".repeat(64);
const HEX_C = "c".repeat(64);
const NAR_A = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
const STORE_A = "/nix/store/00000000000000000000000000000000-tool-1.2.3";
const STORE_B = "/nix/store/11111111111111111111111111111111-runtime-1.0.0";
const STORE_C = "/nix/store/22222222222222222222222222222222-data-1.0.0";

const STRICT_POLICY = {
  profile: "strict-v1",
  pure_evaluation: true,
  import_from_derivation: false,
  sandbox_required: true,
  builder_network: "disabled",
  dirty_source: false,
  publishable: true,
};

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function realized(overrides = {}) {
  return {
    system: "x86_64-linux",
    output: "out",
    derivation_json_sha256: HEX_B,
    store_path: STORE_A,
    nar_hash: NAR_A,
    nar_size: 128,
    nix_version: "2.35.2",
    store_info_json_version: 3,
    ...overrides,
  };
}

function nixToZed(overrides = {}) {
  return {
    direction: "nix-to-zed",
    schema: NIX_ADAPTER_SCHEMA_V1,
    package: {
      org: "acme",
      name: "tool",
      version: "1.2.3",
    },
    source: {
      locked_ref: `github:acme/tool/${HEX_A.slice(0, 40)}`,
      flake_lock_sha256: HEX_A,
      attribute: "packages.x86_64-linux.tool",
      realized: realized(),
    },
    artifact: {
      format: "tar.gz",
      sha256: HEX_C,
      size: 512,
    },
    policy: STRICT_POLICY,
    ...overrides,
  };
}

function zedToNix(overrides = {}) {
  return {
    direction: "zed-to-nix",
    schema: NIX_ADAPTER_SCHEMA_V1,
    package: {
      org: "acme",
      name: "tool",
      version: "1.2.3",
    },
    source: {
      registry: "https://zpkg.example",
      artifact: {
        format: "tar.gz",
        sha256: HEX_C,
        size: 512,
      },
      vcs_tag: "v1.2.3",
      vcs_commit: HEX_A.slice(0, 40),
      lock_sha256: HEX_A,
    },
    intent: {
      mode: "artifact",
      attribute: "tool",
      systems: ["x86_64-linux", "aarch64-linux"],
      outputs: ["out", "dev"],
    },
    flake_bundle_sha256: HEX_B,
    outputs: [
      realized({
        system: "x86_64-linux",
        output: "out",
        references: [
          { store_path: STORE_C, nar_hash: NAR_A, nar_size: 64 },
          { store_path: STORE_B },
        ],
        signatures: ["cache-z:signature", "cache-a:signature"],
      }),
      realized({
        system: "aarch64-linux",
        output: "dev",
        store_path: STORE_C,
      }),
    ],
    policy: STRICT_POLICY,
    ...overrides,
  };
}

test("Nix-to-Zed canonical bytes and SHA-256 match the Rust golden vector", async () => {
  const expected = "{\"artifact\":{\"format\":\"tar.gz\",\"sha256\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\",\"size\":512},\"direction\":\"nix-to-zed\",\"package\":{\"name\":\"tool\",\"org\":\"acme\",\"version\":\"1.2.3\"},\"policy\":{\"builder_network\":\"disabled\",\"dirty_source\":false,\"import_from_derivation\":false,\"profile\":\"strict-v1\",\"publishable\":true,\"pure_evaluation\":true,\"sandbox_required\":true},\"schema\":\"zed.nix-adapter/v1\",\"source\":{\"attribute\":\"packages.x86_64-linux.tool\",\"flake_lock_sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"locked_ref\":\"github:acme/tool/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"realized\":{\"derivation_json_sha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"nar_hash\":\"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\",\"nar_size\":128,\"nix_version\":\"2.35.2\",\"output\":\"out\",\"store_info_json_version\":3,\"store_path\":\"/nix/store/00000000000000000000000000000000-tool-1.2.3\",\"system\":\"x86_64-linux\"}}}";
  const expectedDigest = "dd61b8bea180140a0ec564a8cf6144d4e71ce47339c182b67bcb5848b14efe50";

  assert.equal(canonicalNixAdapterRecordJson(nixToZed()), expected);
  assert.equal(await nixAdapterRecordSha256(nixToZed()), expectedDigest);
  const verified = await verifyCanonicalNixAdapterRecordJson(expected, expectedDigest);
  assert.equal(verified.canonical_json, expected);
  assert.equal(verified.sha256, expectedDigest);
  assert.equal(verified.record.direction, "nix-to-zed");
});

test("canonical verification rejects formatting drift and digest drift", async () => {
  const canonical = canonicalNixAdapterRecordJson(nixToZed());
  await assert.rejects(
    verifyCanonicalNixAdapterRecordJson(`${canonical}\n`),
    /not canonical compact JSON/,
  );
  await assert.rejects(
    verifyCanonicalNixAdapterRecordJson(canonical, "0".repeat(64)),
    /SHA-256 mismatch/,
  );
});

test("Zed-to-Nix arrays normalize exactly like the Rust record", () => {
  const parsed = parseNixAdapterRecord(zedToNix());
  assert.deepEqual(parsed.intent.systems, ["aarch64-linux", "x86_64-linux"]);
  assert.deepEqual(parsed.intent.outputs, ["dev", "out"]);
  assert.deepEqual(
    parsed.outputs.map((output) => `${output.system}/${output.output}`),
    ["aarch64-linux/dev", "x86_64-linux/out"],
  );
  const x86 = parsed.outputs[1];
  assert.deepEqual(
    x86.references.map((reference) => reference.store_path),
    [STORE_B, STORE_C],
  );
  assert.deepEqual(x86.signatures, ["cache-a:signature", "cache-z:signature"]);
});

test("Nix-to-Zed contract v1 rejects any runtime store reference", () => {
  const record = nixToZed();
  record.source.realized.references = [{ store_path: STORE_B }];
  assert.throws(
    () => parseNixAdapterRecord(record),
    /must be closure-free/,
  );
});

test("immutable selector and realization evidence fail closed", () => {
  const mutations = [
    ["mutable locked ref", (record) => { record.source.locked_ref = "github:acme/tool/main"; }],
    ["invalid store path", (record) => { record.source.realized.store_path = "/tmp/tool"; }],
    ["invalid NAR hash", (record) => { record.source.realized.nar_hash = "sha256-not-base64"; }],
    ["unknown JSON version", (record) => { record.source.realized.store_info_json_version = 4; }],
    ["unsafe numeric size", (record) => { record.source.realized.nar_size = Number.MAX_SAFE_INTEGER + 1; }],
  ];
  for (const [label, mutate] of mutations) {
    const record = nixToZed();
    mutate(record);
    assert.throws(
      () => parseNixAdapterRecord(record),
      NixAdapterRecordError,
      label,
    );
  }
});

test("strict policy cannot downgrade and development cannot publish", () => {
  for (const [field, value] of [
    ["pure_evaluation", false],
    ["import_from_derivation", true],
    ["sandbox_required", false],
    ["builder_network", "allowed"],
    ["dirty_source", true],
    ["publishable", false],
  ]) {
    const record = nixToZed();
    record.policy = { ...record.policy, [field]: value };
    assert.throws(() => parseNixAdapterRecord(record), /strict-v1 policy/);
  }

  const development = nixToZed({
    policy: {
      ...STRICT_POLICY,
      profile: "development",
      publishable: true,
    },
  });
  assert.throws(() => parseNixAdapterRecord(development), /never publishable/);
});

test("duplicate and intent-inconsistent realization evidence is rejected", () => {
  const duplicateReference = zedToNix();
  duplicateReference.outputs[0].references = [
    { store_path: STORE_B },
    { store_path: STORE_B },
  ];
  assert.throws(() => parseNixAdapterRecord(duplicateReference), /duplicate values/);

  const duplicateSignature = zedToNix();
  duplicateSignature.outputs[0].signatures = ["cache:signature", "cache:signature"];
  assert.throws(() => parseNixAdapterRecord(duplicateSignature), /duplicate values/);

  const duplicateOutput = zedToNix();
  duplicateOutput.outputs.push(clone(duplicateOutput.outputs[0]));
  assert.throws(() => parseNixAdapterRecord(duplicateOutput), /duplicate values/);

  const undeclared = zedToNix();
  undeclared.outputs[0].output = "doc";
  assert.throws(() => parseNixAdapterRecord(undeclared), /outside declared intent/);

  const missingSystem = zedToNix();
  missingSystem.outputs = missingSystem.outputs.filter((output) => output.system !== "aarch64-linux");
  assert.throws(() => parseNixAdapterRecord(missingSystem), /lacks realized evidence/);
});

test("unknown fields and malformed JSON never echo secret values", () => {
  const secret = "private-cache-key-must-not-appear";
  const record = nixToZed({ private_cache_key: secret });
  assert.throws(
    () => parseNixAdapterRecord(record),
    (error) => error instanceof NixAdapterRecordError && !error.message.includes(secret),
  );
  assert.throws(
    () => parseNixAdapterRecordJson(`{\"secret\":\"${secret}\"`),
    (error) =>
      error instanceof NixAdapterRecordError &&
      error.message === "invalid Nix adapter record: input is not valid JSON" &&
      !error.message.includes(secret),
  );
});

test("artifact bytes bind to the direction-specific Zed artifact identity", async () => {
  const bytes = new TextEncoder().encode("ordinary deterministic Zed artifact");
  const digest = createHash("sha256").update(bytes).digest("hex");

  const imported = nixToZed({
    artifact: { format: "tar.gz", sha256: digest, size: bytes.byteLength },
  });
  const verifiedImport = await verifyNixAdapterArtifactBytes(imported, bytes);
  assert.equal(verifiedImport.sha256, digest);
  assert.equal(verifiedImport.size, bytes.byteLength);

  const exported = zedToNix();
  exported.source.artifact = {
    format: "tar.gz",
    sha256: digest,
    size: bytes.byteLength,
  };
  const verifiedExport = await verifyNixAdapterArtifactBytes(exported, bytes);
  assert.equal(verifiedExport.record.direction, "zed-to-nix");

  await assert.rejects(
    verifyNixAdapterArtifactBytes(imported, new TextEncoder().encode("tampered")),
    /artifact SHA-256 mismatch/,
  );
});

test("parsing returns fresh normalized objects without mutating callers", () => {
  const input = zedToNix();
  const before = clone(input);
  const parsed = parseNixAdapterRecord(input);
  parsed.package.name = "changed";
  parsed.outputs[0].system = "riscv64-linux";
  assert.deepEqual(input, before);
});
