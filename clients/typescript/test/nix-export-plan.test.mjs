import test from "node:test";
import assert from "node:assert/strict";
import {
  NIX_EXPORT_PLAN_SCHEMA_V1,
  NixExportPlanError,
  canonicalNixExportPlanJson,
  parseNixExportPlan,
  parseNixExportPlanJson,
} from "../dist/index.js";

const digest = (character) => character.repeat(64);

function validPlan(overrides = {}) {
  return {
    schema: NIX_EXPORT_PLAN_SCHEMA_V1,
    package: {
      org: "acme",
      name: "dataset",
      version: "1.2.3",
    },
    package_class: "data",
    intent: {
      mode: "artifact",
      attribute: "packages.dataset",
      systems: ["aarch64-linux", "x86_64-linux"],
      outputs: ["out"],
    },
    source: {
      file_name: "acme-dataset-1.2.3.tar.gz",
      artifact: {
        format: "tar.gz",
        sha256: digest("a"),
        size: 1234,
      },
      manifest_sha256: digest("b"),
      lock_sha256: digest("c"),
    },
    bins: {},
    dependencies: [],
    policy: {
      profile: "strict-v1",
      pure_evaluation: true,
      import_from_derivation: false,
      sandbox_required: true,
      builder_network: "disabled",
      dirty_source: false,
      publishable: true,
    },
    ...overrides,
  };
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

test("strict data plans parse and retain explicit empty collections", () => {
  const parsed = parseNixExportPlan(validPlan());
  assert.equal(parsed.schema, NIX_EXPORT_PLAN_SCHEMA_V1);
  assert.deepEqual(parsed.bins, {});
  assert.deepEqual(parsed.dependencies, []);
  assert.deepEqual(parsed.intent.systems, ["aarch64-linux", "x86_64-linux"]);
  assert.equal(parsed.intent.attribute, "packages.dataset");
  assert.equal(parsed.policy.builder_network, "disabled");
});

test("prebuilt-bin plans sort executable maps and use forward-slash paths", () => {
  const plan = validPlan({
    package: { org: "acme", name: "tool", version: "2.0.0", target: "nodejs" },
    package_class: "prebuilt-bin",
    source: {
      file_name: "acme-tool-2.0.0.zip",
      artifact: { format: "zip", sha256: digest("d"), size: 77 },
      manifest_sha256: digest("e"),
      lock_sha256: digest("f"),
    },
    bins: {
      zeta: "bin/zeta",
      alpha: "dist/bin/alpha",
    },
  });
  const parsed = parseNixExportPlan(plan);
  assert.deepEqual(Object.keys(parsed.bins), ["alpha", "zeta"]);
  assert.equal(parsed.package.target, "nodejs");
});

test("canonical JSON is stable across input object key order", () => {
  const normal = validPlan();
  const shuffled = {
    policy: normal.policy,
    dependencies: normal.dependencies,
    bins: normal.bins,
    source: normal.source,
    intent: normal.intent,
    package_class: normal.package_class,
    package: normal.package,
    schema: normal.schema,
  };
  const canonical = canonicalNixExportPlanJson(normal);
  assert.equal(canonicalNixExportPlanJson(shuffled), canonical);
  assert.equal(JSON.stringify(JSON.parse(canonical)), canonical);
  assert.match(canonical, /"bins":\{\},"dependencies":\[\]/);
});

test("untrusted JSON syntax errors do not echo source text", () => {
  const secret = "credential-that-must-not-appear";
  assert.throws(
    () => parseNixExportPlanJson(`{"schema":"${secret}"`),
    (error) =>
      error instanceof NixExportPlanError &&
      error.message === "invalid Nix export plan: input is not valid JSON" &&
      !error.message.includes(secret),
  );
});

test("unknown root and nested fields fail closed without echoing values", () => {
  const root = validPlan({ registry: "https://person:secret@example.invalid" });
  assert.throws(
    () => parseNixExportPlan(root),
    (error) =>
      error instanceof NixExportPlanError &&
      error.message.includes("unknown field") &&
      !error.message.includes("person:secret"),
  );

  const nested = validPlan();
  nested.source.registry_url = "https://person:secret@example.invalid";
  assert.throws(
    () => parseNixExportPlan(nested),
    (error) =>
      error instanceof NixExportPlanError &&
      error.message.includes("source contains unknown field") &&
      !error.message.includes("person:secret"),
  );
});

test("omitted empty collections normalize to explicit canonical values", () => {
  const omitted = validPlan();
  delete omitted.bins;
  delete omitted.dependencies;
  const parsed = parseNixExportPlan(omitted);
  assert.deepEqual(parsed.bins, {});
  assert.deepEqual(parsed.dependencies, []);
  assert.match(
    canonicalNixExportPlanJson(omitted),
    /"bins":\{\},"dependencies":\[\]/,
  );
});

test("systems and outputs must already be canonical and unique", () => {
  for (const systems of [
    ["x86_64-linux", "aarch64-linux"],
    ["x86_64-linux", "x86_64-linux"],
  ]) {
    const plan = validPlan();
    plan.intent.systems = systems;
    assert.throws(() => parseNixExportPlan(plan), /systems must be sorted and unique/);
  }

  const multiSegment = validPlan();
  multiSegment.intent.systems = ["aarch64-linux-gnu", "x86_64-linux"];
  assert.doesNotThrow(() => parseNixExportPlan(multiSegment));

  const duplicateOutputs = validPlan();
  duplicateOutputs.intent.outputs = ["out", "out"];
  assert.throws(() => parseNixExportPlan(duplicateOutputs), /outputs must be sorted and unique/);
});

test("artifact identity and exact input digests fail closed", () => {
  const wrongName = validPlan();
  wrongName.source.file_name = "other.tar.gz";
  assert.throws(() => parseNixExportPlan(wrongName), /canonical safe basename/);

  for (const mutate of [
    (plan) => {
      plan.source.artifact.sha256 = "A".repeat(64);
    },
    (plan) => {
      plan.source.manifest_sha256 = "short";
    },
    (plan) => {
      plan.source.lock_sha256 = "0".repeat(63);
    },
    (plan) => {
      plan.source.artifact.size = 0;
    },
    (plan) => {
      plan.source.artifact.size = Number.MAX_SAFE_INTEGER + 1;
    },
  ]) {
    const plan = validPlan();
    mutate(plan);
    assert.throws(() => parseNixExportPlan(plan), NixExportPlanError);
  }
});

test("class and executable inventory must agree", () => {
  const dataWithBin = validPlan({ bins: { tool: "bin/tool" } });
  assert.throws(() => parseNixExportPlan(dataWithBin), /data packages must have an empty bins/);

  const emptyPrebuilt = validPlan({ package_class: "prebuilt-bin" });
  assert.throws(() => parseNixExportPlan(emptyPrebuilt), /must declare at least one executable/);
});

test("artifact-relative executable paths reject traversal and host separators", () => {
  for (const path of ["../outside", "/absolute", "bin\\tool", "bin//tool", "bin/./tool", "bin/tool/"]) {
    const plan = validPlan({
      package: { org: "acme", name: "tool", version: "1.0.0" },
      package_class: "prebuilt-bin",
      source: {
        file_name: "acme-tool-1.0.0.tar.gz",
        artifact: { format: "tar.gz", sha256: digest("d"), size: 10 },
        manifest_sha256: digest("e"),
        lock_sha256: digest("f"),
      },
      bins: { tool: path },
    });
    assert.throws(() => parseNixExportPlan(plan), /artifact-relative path/);
  }
});

test("contract v1 rejects dependency edges and non-strict policy", () => {
  const dependency = validPlan();
  dependency.dependencies = [
    { org: "acme", name: "other", version: "1.0.0", sha256: digest("d") },
  ];
  assert.throws(() => parseNixExportPlan(dependency), /dependency-free packages only/);

  for (const [field, value] of [
    ["pure_evaluation", false],
    ["import_from_derivation", true],
    ["sandbox_required", false],
    ["builder_network", "enabled"],
    ["dirty_source", true],
    ["publishable", false],
  ]) {
    const plan = validPlan();
    plan.policy[field] = value;
    assert.throws(() => parseNixExportPlan(plan), NixExportPlanError, field);
  }
});

test("input objects are normalized into fresh output objects", () => {
  const input = validPlan();
  const original = clone(input);
  const parsed = parseNixExportPlan(input);
  parsed.package.name = "changed";
  parsed.intent.systems.push("riscv64-linux");
  assert.deepEqual(input, original);
});
