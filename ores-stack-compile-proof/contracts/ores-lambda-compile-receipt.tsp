import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresLambdaCompileReceipt;

@pattern("^[0-9a-f]{64}$")
scalar Sha256Hex extends string;

enum LambdaBuildProvider {
  aws,
  gcp,
}

enum LambdaFunctionKind {
  web,
  api,
}

enum LambdaCarrier {
  http_ingress,
  direct_invoke,
}

enum LambdaBuildArchitecture {
  x86_64,
  arm64,
}

enum LambdaCompileTarget {
  x86_64: "x86_64-unknown-linux-gnu",
  arm64: "aarch64-unknown-linux-gnu",
}

@id("LambdaCompileReceiptV1")
@extension(
  "allOf",
  #[
    #{
      `if`: #{
        properties: #{ provider: #{ `const`: "gcp" } },
        required: #["provider"],
      },
      `then`: #{
        properties: #{ architecture: #{ `const`: "x86_64" } },
      },
    },
  ]
)
model LambdaCompileReceiptV1 {
  schema_version: "ores.lambda.compile.receipt.v1";
  generated_by: "ores-stack";
  compile_unit_sha256: Sha256Hex;
  build_unit_sha256: Sha256Hex;
  provider: LambdaBuildProvider;
  kind: LambdaFunctionKind;
  carrier: LambdaCarrier;
  architecture: LambdaBuildArchitecture;
  target_triple: LambdaCompileTarget;

  @minLength(1)
  rustc_version: string;

  @minLength(1)
  cargo_version: string;

  cargo_lock_sha256: Sha256Hex;
  executable_sha256: Sha256Hex;

  @pattern("^build/lambda/(aws|gcp)/funcs/(web|api)/(http_ingress|direct_invoke)/.+/compiled/(x86_64-unknown-linux-gnu|aarch64-unknown-linux-gnu)/release/ores-lambda-function$")
  executable_path: string;

  @doc("Compilation never packages artifacts and never mutates provider/cloud deployment state.")
  deploy_mutation_performed: false;
}

// The executor must derive this receipt only after validating the compile unit,
// using the exact Cargo.lock bytes and executable bytes it produced. Packaging
// and deployment remain separate later phases.
