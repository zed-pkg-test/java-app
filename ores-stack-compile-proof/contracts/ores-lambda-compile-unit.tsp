import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresLambdaCompileUnit;

@pattern("^[0-9a-f]{64}$")
scalar Sha256Hex extends string;

@pattern("^[0-9a-f]{40}$")
scalar GitSha extends string;

@pattern("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
scalar RepositoryIdentity extends string;

@pattern("^[a-z_][a-z0-9_]*$")
scalar CargoAlias extends string;

@pattern("^[A-Za-z0-9_-]+$")
scalar CargoPackage extends string;

@pattern("^[A-Za-z0-9_.:/+-]+$")
scalar CargoFeature extends string;

@pattern("^[A-Za-z0-9_-]+$")
scalar CargoRegistry extends string;

@pattern("^[0-9][A-Za-z0-9.+-]*\\.[A-Za-z0-9.+-]+(\\.[A-Za-z0-9.+-]+)*$")
scalar ExactCargoVersion extends string;

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

enum LambdaCompileDependencyKind {
  git,
  registry,
}

enum LambdaCompileResolver {
  zed_pkg,
  cargo_registry,
}

enum LambdaCargoPolicy {
  locked,
  frozen,
}

enum LambdaCompileProfile {
  release,
}

enum LambdaRuntimeProfile {
  aws_web_http,
  gcp_web_http,
  aws_api_http,
  gcp_api_http,
  aws_api_direct,
}

enum LambdaCompileTarget {
  x86_64: "x86_64-unknown-linux-gnu",
  arm64: "aarch64-unknown-linux-gnu",
}

@id("LambdaCompileDependencyV1")
@extension(
  "allOf",
  #[
    #{
      `if`: #{
        properties: #{ kind: #{ `const`: "git" } },
        required: #["kind"],
      },
      `then`: #{
        required: #["repository", "rev"],
        properties: #{ resolver: #{ `const`: "zed_pkg" } },
        `not`: #{ anyOf: #[#{ required: #["registry"] }, #{ required: #["version"] }] },
      },
    },
    #{
      `if`: #{
        properties: #{ kind: #{ `const`: "registry" } },
        required: #["kind"],
      },
      `then`: #{
        required: #["registry", "version"],
        properties: #{ resolver: #{ `const`: "cargo_registry" } },
        `not`: #{ anyOf: #[#{ required: #["repository"] }, #{ required: #["rev"] }] },
      },
    },
  ]
)
model LambdaCompileDependencyV1 {
  `alias`: CargoAlias;
  package: CargoPackage;
  kind: LambdaCompileDependencyKind;
  resolver: LambdaCompileResolver;
  repository?: RepositoryIdentity;
  rev?: GitSha;
  registry?: CargoRegistry;
  version?: ExactCargoVersion;
  default_features: boolean;
  @uniqueItems
  features: CargoFeature[];
}

@id("LambdaCompileUnitV1")
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
    #{
      `if`: #{
        properties: #{ provider: #{ `const`: "aws" }, kind: #{ `const`: "web" }, carrier: #{ `const`: "http_ingress" } },
        required: #["provider", "kind", "carrier"],
      },
      `then`: #{ properties: #{ runtime_profile: #{ `const`: "aws_web_http" } } },
    },
    #{
      `if`: #{
        properties: #{ provider: #{ `const`: "gcp" }, kind: #{ `const`: "web" }, carrier: #{ `const`: "http_ingress" } },
        required: #["provider", "kind", "carrier"],
      },
      `then`: #{ properties: #{ runtime_profile: #{ `const`: "gcp_web_http" } } },
    },
    #{
      `if`: #{
        properties: #{ provider: #{ `const`: "aws" }, kind: #{ `const`: "api" }, carrier: #{ `const`: "http_ingress" } },
        required: #["provider", "kind", "carrier"],
      },
      `then`: #{ properties: #{ runtime_profile: #{ `const`: "aws_api_http" } } },
    },
    #{
      `if`: #{
        properties: #{ provider: #{ `const`: "gcp" }, kind: #{ `const`: "api" }, carrier: #{ `const`: "http_ingress" } },
        required: #["provider", "kind", "carrier"],
      },
      `then`: #{ properties: #{ runtime_profile: #{ `const`: "gcp_api_http" } } },
    },
    #{
      `if`: #{
        properties: #{ provider: #{ `const`: "aws" }, kind: #{ `const`: "api" }, carrier: #{ `const`: "direct_invoke" } },
        required: #["provider", "kind", "carrier"],
      },
      `then`: #{ properties: #{ runtime_profile: #{ `const`: "aws_api_direct" } } },
    },
  ]
)
model LambdaCompileUnitV1 {
  schema_version: "ores.lambda.compile.unit.v1";
  generated_by: "ores-stack";
  build_schema_version: "ores.lambda.build.v2";
  build_unit_sha256: Sha256Hex;
  @minLength(1)
  function_id: string;
  provider: LambdaBuildProvider;
  kind: LambdaFunctionKind;
  carrier: LambdaCarrier;
  architecture: LambdaBuildArchitecture;
  runtime_profile: LambdaRuntimeProfile;
  target_triple: LambdaCompileTarget;
  rust_version: "1.88";
  profile: LambdaCompileProfile;
  cargo_policy: LambdaCargoPolicy;

  @pattern("^build/lambda/(aws|gcp)/funcs/(web|api)/(http_ingress|direct_invoke)/.+/Cargo\\.toml$")
  cargo_manifest: string;

  @pattern("^build/lambda/(aws|gcp)/funcs/(web|api)/(http_ingress|direct_invoke)/.+/Cargo\\.lock$")
  cargo_lock: string;

  @pattern("^build/lambda/(aws|gcp)/funcs/(web|api)/(http_ingress|direct_invoke)/.+/compiled$")
  target_dir: string;

  @pattern("^build/lambda/(aws|gcp)/funcs/(web|api)/(http_ingress|direct_invoke)/.+/compiled/(x86_64-unknown-linux-gnu|aarch64-unknown-linux-gnu)/release/ores-lambda-function$")
  executable_path: string;

  @minItems(1)
  dependencies: LambdaCompileDependencyV1[];
}

// Tooling additionally enforces the closed direct dependency aliases:
// web = ores_web_app + ores_api_docs_client + ores_lambda_runtime + tokio;
// api = ores_api_app + ores_api_docs + ores_fn_adapters + ores_lambda_runtime + tokio.
// Repository dependencies require immutable 40-hex Git provenance and zed-pkg
// handoff. tokio is an exact registry dependency. Runtime Cargo features are
// derived from provider/kind/carrier and cannot be selected by source sniffing.
// Release v1 intentionally has no local-path dependency variant.
