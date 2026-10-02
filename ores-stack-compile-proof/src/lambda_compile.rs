#![forbid(unsafe_code)]

//! Deterministic Rust compile authority for materialized Lambda function units.
//!
//! This module deliberately stops before process execution. It defines and
//! validates the complete compiler input, renders an isolated Cargo manifest,
//! and defines the receipt shape an executor must emit. No ambient workspace,
//! floating Git revision, local path dependency, package step, or cloud deploy
//! mutation is admitted by this v1 contract.

use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::{
    lambda_artifact::LambdaBuildArchitecture,
    lambda_build::{
        LAMBDA_BUILD_SCHEMA, LambdaBuildError, LambdaBuildProvider, LambdaCarrier,
        LambdaFunctionBuildUnit, LambdaFunctionKind,
    },
};

pub const LAMBDA_COMPILE_UNIT_SCHEMA: &str = "ores.lambda.compile.unit.v1";
pub const LAMBDA_COMPILE_RECEIPT_SCHEMA: &str = "ores.lambda.compile.receipt.v1";
pub const LAMBDA_COMPILE_GENERATOR: &str = "ores-stack";
pub const LAMBDA_COMPILE_RUST_VERSION: &str = "1.88";
pub const LAMBDA_COMPILE_BINARY: &str = "ores-lambda-function";

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LambdaCompileDependencyKind {
    Git,
    Registry,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LambdaCompileResolver {
    ZedPkg,
    CargoRegistry,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LambdaCargoPolicy {
    Locked,
    Frozen,
}

impl LambdaCargoPolicy {
    #[must_use]
    pub const fn cargo_flag(self) -> &'static str {
        match self {
            Self::Locked => "--locked",
            Self::Frozen => "--frozen",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LambdaCompileProfile {
    Release,
}

impl LambdaCompileProfile {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Release => "release",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LambdaRuntimeProfile {
    AwsWebHttp,
    GcpWebHttp,
    AwsApiHttp,
    GcpApiHttp,
    AwsApiDirect,
}

impl LambdaRuntimeProfile {
    #[must_use]
    pub const fn cargo_features(self) -> &'static [&'static str] {
        match self {
            Self::AwsWebHttp => &["page-aws"],
            Self::GcpWebHttp => &["page-gcp"],
            Self::AwsApiHttp | Self::AwsApiDirect => &["aws"],
            Self::GcpApiHttp => &["http"],
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LambdaCompileDependency {
    pub alias: String,
    pub package: String,
    pub kind: LambdaCompileDependencyKind,
    pub resolver: LambdaCompileResolver,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub repository: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rev: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub registry: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    pub default_features: bool,
    pub features: Vec<String>,
}

impl LambdaCompileDependency {
    #[must_use]
    pub fn git(
        alias: impl Into<String>,
        package: impl Into<String>,
        repository: impl Into<String>,
        rev: impl Into<String>,
        features: Vec<String>,
        default_features: bool,
    ) -> Self {
        let mut features = features;
        features.sort();
        Self {
            alias: alias.into(),
            package: package.into(),
            kind: LambdaCompileDependencyKind::Git,
            resolver: LambdaCompileResolver::ZedPkg,
            repository: Some(repository.into()),
            rev: Some(rev.into()),
            registry: None,
            version: None,
            default_features,
            features,
        }
    }

    #[must_use]
    pub fn registry(
        alias: impl Into<String>,
        package: impl Into<String>,
        registry: impl Into<String>,
        version: impl Into<String>,
        features: Vec<String>,
        default_features: bool,
    ) -> Self {
        let mut features = features;
        features.sort();
        Self {
            alias: alias.into(),
            package: package.into(),
            kind: LambdaCompileDependencyKind::Registry,
            resolver: LambdaCompileResolver::CargoRegistry,
            repository: None,
            rev: None,
            registry: Some(registry.into()),
            version: Some(version.into()),
            default_features,
            features,
        }
    }

    pub fn validate(&self) -> Result<(), LambdaCompileError> {
        if !valid_alias(&self.alias) {
            return Err(LambdaCompileError::InvalidDependencyAlias(
                self.alias.clone(),
            ));
        }
        if !valid_package(&self.package) {
            return Err(LambdaCompileError::InvalidDependencyPackage(
                self.package.clone(),
            ));
        }
        validate_features(&self.alias, &self.features)?;

        match self.kind {
            LambdaCompileDependencyKind::Git => {
                if self.resolver != LambdaCompileResolver::ZedPkg
                    || self.registry.is_some()
                    || self.version.is_some()
                {
                    return Err(LambdaCompileError::InvalidDependencyCoordinate(
                        self.alias.clone(),
                    ));
                }
                let repository = self.repository.as_deref().ok_or_else(|| {
                    LambdaCompileError::InvalidDependencyCoordinate(self.alias.clone())
                })?;
                let rev = self.rev.as_deref().ok_or_else(|| {
                    LambdaCompileError::InvalidDependencyCoordinate(self.alias.clone())
                })?;
                if !valid_repository(repository) || !is_lower_hex(rev, 40) {
                    return Err(LambdaCompileError::InvalidDependencyCoordinate(
                        self.alias.clone(),
                    ));
                }
            }
            LambdaCompileDependencyKind::Registry => {
                if self.resolver != LambdaCompileResolver::CargoRegistry
                    || self.repository.is_some()
                    || self.rev.is_some()
                {
                    return Err(LambdaCompileError::InvalidDependencyCoordinate(
                        self.alias.clone(),
                    ));
                }
                let registry = self.registry.as_deref().ok_or_else(|| {
                    LambdaCompileError::InvalidDependencyCoordinate(self.alias.clone())
                })?;
                let version = self.version.as_deref().ok_or_else(|| {
                    LambdaCompileError::InvalidDependencyCoordinate(self.alias.clone())
                })?;
                if !valid_registry(registry) || !valid_exact_version(version) {
                    return Err(LambdaCompileError::InvalidDependencyCoordinate(
                        self.alias.clone(),
                    ));
                }
            }
        }
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LambdaCompileUnit {
    pub schema_version: String,
    pub generated_by: String,
    pub build_schema_version: String,
    pub build_unit_sha256: String,
    pub function_id: String,
    pub provider: LambdaBuildProvider,
    pub kind: LambdaFunctionKind,
    pub carrier: LambdaCarrier,
    pub architecture: LambdaBuildArchitecture,
    pub runtime_profile: LambdaRuntimeProfile,
    pub target_triple: String,
    pub rust_version: String,
    pub profile: LambdaCompileProfile,
    pub cargo_policy: LambdaCargoPolicy,
    pub cargo_manifest: String,
    pub cargo_lock: String,
    pub target_dir: String,
    pub executable_path: String,
    pub dependencies: Vec<LambdaCompileDependency>,
}

impl LambdaCompileUnit {
    pub fn new(
        build_unit: &LambdaFunctionBuildUnit,
        architecture: LambdaBuildArchitecture,
        mut dependencies: Vec<LambdaCompileDependency>,
        cargo_policy: LambdaCargoPolicy,
    ) -> Result<Self, LambdaCompileError> {
        build_unit.validate()?;
        validate_architecture(build_unit.provider, architecture)?;
        dependencies.sort_by(|left, right| left.alias.cmp(&right.alias));

        let target_triple = target_triple(architecture).to_owned();
        let target_dir = format!("{}/compiled", build_unit.build_dir);
        let unit = Self {
            schema_version: LAMBDA_COMPILE_UNIT_SCHEMA.to_owned(),
            generated_by: LAMBDA_COMPILE_GENERATOR.to_owned(),
            build_schema_version: LAMBDA_BUILD_SCHEMA.to_owned(),
            build_unit_sha256: sha256_json(build_unit)?,
            function_id: build_unit.id.clone(),
            provider: build_unit.provider,
            kind: build_unit.kind,
            carrier: build_unit.carrier,
            architecture,
            runtime_profile: runtime_profile(build_unit)?,
            target_triple: target_triple.clone(),
            rust_version: LAMBDA_COMPILE_RUST_VERSION.to_owned(),
            profile: LambdaCompileProfile::Release,
            cargo_policy,
            cargo_manifest: format!("{}/Cargo.toml", build_unit.build_dir),
            cargo_lock: format!("{}/Cargo.lock", build_unit.build_dir),
            target_dir: target_dir.clone(),
            executable_path: format!(
                "{target_dir}/{target_triple}/release/{LAMBDA_COMPILE_BINARY}"
            ),
            dependencies,
        };
        unit.validate()?;
        Ok(unit)
    }

    pub fn validate(&self) -> Result<(), LambdaCompileError> {
        if self.schema_version != LAMBDA_COMPILE_UNIT_SCHEMA
            || self.generated_by != LAMBDA_COMPILE_GENERATOR
            || self.build_schema_version != LAMBDA_BUILD_SCHEMA
            || !is_lower_hex(&self.build_unit_sha256, 64)
            || self.rust_version != LAMBDA_COMPILE_RUST_VERSION
            || self.profile != LambdaCompileProfile::Release
        {
            return Err(LambdaCompileError::InvalidCompileIdentity);
        }

        validate_architecture(self.provider, self.architecture)?;
        let expected_target = target_triple(self.architecture);
        if self.target_triple != expected_target {
            return Err(LambdaCompileError::InvalidTargetTriple {
                architecture: self.architecture,
                target: self.target_triple.clone(),
            });
        }

        let expected_runtime = runtime_profile_parts(self.provider, self.kind, self.carrier)?;
        if self.runtime_profile != expected_runtime {
            return Err(LambdaCompileError::InvalidRuntimeProfile);
        }

        let expected_build_dir = build_dir_from_manifest_path(&self.cargo_manifest)
            .ok_or(LambdaCompileError::UnsafeCompilePath)?;
        if self.cargo_manifest != format!("{expected_build_dir}/Cargo.toml")
            || self.cargo_lock != format!("{expected_build_dir}/Cargo.lock")
            || self.target_dir != format!("{expected_build_dir}/compiled")
            || self.executable_path
                != format!(
                    "{}/compiled/{}/release/{}",
                    expected_build_dir, self.target_triple, LAMBDA_COMPILE_BINARY
                )
            || !expected_build_dir.ends_with(&self.function_id)
        {
            return Err(LambdaCompileError::UnsafeCompilePath);
        }

        let required = required_aliases(self.kind);
        let mut actual = BTreeSet::new();
        for dependency in &self.dependencies {
            dependency.validate()?;
            if !actual.insert(dependency.alias.as_str()) {
                return Err(LambdaCompileError::DuplicateDependencyAlias(
                    dependency.alias.clone(),
                ));
            }
        }
        if actual != required {
            return Err(LambdaCompileError::DependencyAliasSet {
                expected: required.into_iter().map(str::to_owned).collect(),
                actual: actual.into_iter().map(str::to_owned).collect(),
            });
        }

        for dependency in &self.dependencies {
            if dependency.alias == "tokio" {
                if dependency.kind != LambdaCompileDependencyKind::Registry
                    || dependency.features != ["macros".to_owned(), "rt-multi-thread".to_owned()]
                {
                    return Err(LambdaCompileError::InvalidTokioDependency);
                }
                continue;
            }

            if dependency.kind != LambdaCompileDependencyKind::Git
                || dependency.resolver != LambdaCompileResolver::ZedPkg
            {
                return Err(LambdaCompileError::RepositoryDependencyMustUseZedPkg(
                    dependency.alias.clone(),
                ));
            }
        }

        let runtime = self
            .dependencies
            .iter()
            .find(|dependency| dependency.alias == "ores_lambda_runtime")
            .ok_or(LambdaCompileError::InvalidRuntimeProfile)?;
        let expected_features = self
            .runtime_profile
            .cargo_features()
            .iter()
            .map(|feature| (*feature).to_owned())
            .collect::<Vec<_>>();
        if runtime.features != expected_features {
            return Err(LambdaCompileError::RuntimeFeatureMismatch {
                expected: expected_features,
                actual: runtime.features.clone(),
            });
        }

        Ok(())
    }

    pub fn to_pretty_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string_pretty(self).map(|mut json| {
            json.push('\n');
            json
        })
    }

    pub fn sha256(&self) -> Result<String, serde_json::Error> {
        sha256_json(self)
    }

    pub fn render_cargo_manifest(&self) -> Result<String, LambdaCompileError> {
        self.validate()?;

        let mut out = String::new();
        out.push_str("[package]\n");
        out.push_str("name = \"ores-lambda-function\"\n");
        out.push_str("version = \"0.0.0\"\n");
        out.push_str("edition = \"2024\"\n");
        out.push_str(&format!("rust-version = {:?}\n", self.rust_version));
        out.push_str("publish = false\n\n");
        out.push_str("[workspace]\nresolver = \"2\"\n\n");
        out.push_str("[[bin]]\n");
        out.push_str(&format!("name = {LAMBDA_COMPILE_BINARY:?}\n"));
        out.push_str("path = \"main.rs\"\n\n");
        out.push_str("[dependencies]\n");

        for dependency in &self.dependencies {
            out.push_str(&format!(
                "{} = {{ package = {:?}",
                dependency.alias, dependency.package
            ));
            match dependency.kind {
                LambdaCompileDependencyKind::Git => {
                    let repository = dependency.repository.as_deref().ok_or_else(|| {
                        LambdaCompileError::InvalidDependencyCoordinate(dependency.alias.clone())
                    })?;
                    let rev = dependency.rev.as_deref().ok_or_else(|| {
                        LambdaCompileError::InvalidDependencyCoordinate(dependency.alias.clone())
                    })?;
                    out.push_str(&format!(
                        ", git = {:?}, rev = {:?}",
                        format!("https://github.com/{repository}.git"),
                        rev
                    ));
                }
                LambdaCompileDependencyKind::Registry => {
                    let version = dependency.version.as_deref().ok_or_else(|| {
                        LambdaCompileError::InvalidDependencyCoordinate(dependency.alias.clone())
                    })?;
                    out.push_str(&format!(", version = {:?}", format!("={version}")));
                    if dependency.registry.as_deref() != Some("crates_io") {
                        let registry = dependency.registry.as_deref().ok_or_else(|| {
                            LambdaCompileError::InvalidDependencyCoordinate(
                                dependency.alias.clone(),
                            )
                        })?;
                        out.push_str(&format!(", registry = {registry:?}"));
                    }
                }
            }
            out.push_str(&format!(
                ", default-features = {}",
                dependency.default_features
            ));
            if !dependency.features.is_empty() {
                out.push_str(", features = [");
                for (index, feature) in dependency.features.iter().enumerate() {
                    if index > 0 {
                        out.push_str(", ");
                    }
                    out.push_str(&format!("{feature:?}"));
                }
                out.push(']');
            }
            out.push_str(" }\n");
        }

        Ok(out)
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LambdaCompileReceipt {
    pub schema_version: String,
    pub generated_by: String,
    pub compile_unit_sha256: String,
    pub build_unit_sha256: String,
    pub provider: LambdaBuildProvider,
    pub kind: LambdaFunctionKind,
    pub carrier: LambdaCarrier,
    pub architecture: LambdaBuildArchitecture,
    pub target_triple: String,
    pub rustc_version: String,
    pub cargo_version: String,
    pub cargo_lock_sha256: String,
    pub executable_sha256: String,
    pub executable_path: String,
    pub deploy_mutation_performed: bool,
}

impl LambdaCompileReceipt {
    pub fn new(
        unit: &LambdaCompileUnit,
        cargo_lock: &[u8],
        rustc_version: impl Into<String>,
        cargo_version: impl Into<String>,
        executable: &[u8],
    ) -> Result<Self, LambdaCompileError> {
        unit.validate()?;
        let receipt = Self {
            schema_version: LAMBDA_COMPILE_RECEIPT_SCHEMA.to_owned(),
            generated_by: LAMBDA_COMPILE_GENERATOR.to_owned(),
            compile_unit_sha256: unit.sha256()?,
            build_unit_sha256: unit.build_unit_sha256.clone(),
            provider: unit.provider,
            kind: unit.kind,
            carrier: unit.carrier,
            architecture: unit.architecture,
            target_triple: unit.target_triple.clone(),
            rustc_version: rustc_version.into(),
            cargo_version: cargo_version.into(),
            cargo_lock_sha256: sha256_hex(cargo_lock),
            executable_sha256: sha256_hex(executable),
            executable_path: unit.executable_path.clone(),
            deploy_mutation_performed: false,
        };
        receipt.validate()?;
        Ok(receipt)
    }

    pub fn validate(&self) -> Result<(), LambdaCompileError> {
        if self.schema_version != LAMBDA_COMPILE_RECEIPT_SCHEMA
            || self.generated_by != LAMBDA_COMPILE_GENERATOR
            || !is_lower_hex(&self.compile_unit_sha256, 64)
            || !is_lower_hex(&self.build_unit_sha256, 64)
            || !is_lower_hex(&self.cargo_lock_sha256, 64)
            || !is_lower_hex(&self.executable_sha256, 64)
            || self.rustc_version.trim().is_empty()
            || self.cargo_version.trim().is_empty()
            || self.deploy_mutation_performed
        {
            return Err(LambdaCompileError::InvalidCompileReceipt);
        }
        validate_architecture(self.provider, self.architecture)?;
        if self.target_triple != target_triple(self.architecture)
            || !self.executable_path.ends_with(&format!(
                "/{}/release/{LAMBDA_COMPILE_BINARY}",
                self.target_triple
            ))
        {
            return Err(LambdaCompileError::InvalidCompileReceipt);
        }
        Ok(())
    }
}

#[derive(Debug, Error)]
pub enum LambdaCompileError {
    #[error(transparent)]
    Build(#[from] LambdaBuildError),
    #[error("serialize lambda compile authority: {0}")]
    Serialize(#[from] serde_json::Error),
    #[error("lambda compile unit identity is invalid or stale")]
    InvalidCompileIdentity,
    #[error("dependency alias is not a safe Rust/Cargo alias: {0:?}")]
    InvalidDependencyAlias(String),
    #[error("dependency package name is invalid: {0:?}")]
    InvalidDependencyPackage(String),
    #[error("dependency {0:?} has an invalid or non-immutable source coordinate")]
    InvalidDependencyCoordinate(String),
    #[error("dependency {alias:?} contains invalid or duplicate Cargo feature {feature:?}")]
    InvalidDependencyFeature { alias: String, feature: String },
    #[error("duplicate dependency alias in compile unit: {0}")]
    DuplicateDependencyAlias(String),
    #[error(
        "compile unit dependency aliases differ from the closed ABI set; expected={expected:?}, actual={actual:?}"
    )]
    DependencyAliasSet {
        expected: Vec<String>,
        actual: Vec<String>,
    },
    #[error("repository dependency {0:?} must use immutable Git provenance through zed-pkg")]
    RepositoryDependencyMustUseZedPkg(String),
    #[error(
        "tokio must be an exact registry dependency with only macros + rt-multi-thread features"
    )]
    InvalidTokioDependency,
    #[error("runtime profile is incompatible with provider/kind/carrier")]
    InvalidRuntimeProfile,
    #[error("runtime dependency feature set mismatch; expected={expected:?}, actual={actual:?}")]
    RuntimeFeatureMismatch {
        expected: Vec<String>,
        actual: Vec<String>,
    },
    #[error("architecture {architecture:?} is unsupported for provider {provider:?}")]
    UnsupportedArchitecture {
        provider: LambdaBuildProvider,
        architecture: LambdaBuildArchitecture,
    },
    #[error("target triple {target:?} is invalid for architecture {architecture:?}")]
    InvalidTargetTriple {
        architecture: LambdaBuildArchitecture,
        target: String,
    },
    #[error("compile output path is not derived from the materialized Lambda build unit")]
    UnsafeCompilePath,
    #[error("lambda compile receipt is invalid")]
    InvalidCompileReceipt,
}

fn runtime_profile(
    unit: &LambdaFunctionBuildUnit,
) -> Result<LambdaRuntimeProfile, LambdaCompileError> {
    runtime_profile_parts(unit.provider, unit.kind, unit.carrier)
}

fn runtime_profile_parts(
    provider: LambdaBuildProvider,
    kind: LambdaFunctionKind,
    carrier: LambdaCarrier,
) -> Result<LambdaRuntimeProfile, LambdaCompileError> {
    match (provider, kind, carrier) {
        (LambdaBuildProvider::Aws, LambdaFunctionKind::Web, LambdaCarrier::HttpIngress) => {
            Ok(LambdaRuntimeProfile::AwsWebHttp)
        }
        (LambdaBuildProvider::Gcp, LambdaFunctionKind::Web, LambdaCarrier::HttpIngress) => {
            Ok(LambdaRuntimeProfile::GcpWebHttp)
        }
        (LambdaBuildProvider::Aws, LambdaFunctionKind::Api, LambdaCarrier::HttpIngress) => {
            Ok(LambdaRuntimeProfile::AwsApiHttp)
        }
        (LambdaBuildProvider::Gcp, LambdaFunctionKind::Api, LambdaCarrier::HttpIngress) => {
            Ok(LambdaRuntimeProfile::GcpApiHttp)
        }
        (LambdaBuildProvider::Aws, LambdaFunctionKind::Api, LambdaCarrier::DirectInvoke) => {
            Ok(LambdaRuntimeProfile::AwsApiDirect)
        }
        _ => Err(LambdaCompileError::InvalidRuntimeProfile),
    }
}

fn required_aliases(kind: LambdaFunctionKind) -> BTreeSet<&'static str> {
    match kind {
        LambdaFunctionKind::Web => [
            "ores_api_docs_client",
            "ores_lambda_runtime",
            "ores_web_app",
            "tokio",
        ]
        .into_iter()
        .collect(),
        LambdaFunctionKind::Api => [
            "ores_api_app",
            "ores_api_docs",
            "ores_fn_adapters",
            "ores_lambda_runtime",
            "tokio",
        ]
        .into_iter()
        .collect(),
    }
}

fn validate_architecture(
    provider: LambdaBuildProvider,
    architecture: LambdaBuildArchitecture,
) -> Result<(), LambdaCompileError> {
    if provider == LambdaBuildProvider::Gcp && architecture != LambdaBuildArchitecture::X86_64 {
        return Err(LambdaCompileError::UnsupportedArchitecture {
            provider,
            architecture,
        });
    }
    Ok(())
}

fn target_triple(architecture: LambdaBuildArchitecture) -> &'static str {
    match architecture {
        LambdaBuildArchitecture::X86_64 => "x86_64-unknown-linux-gnu",
        LambdaBuildArchitecture::Arm64 => "aarch64-unknown-linux-gnu",
    }
}

fn build_dir_from_manifest_path(path: &str) -> Option<&str> {
    let build_dir = path.strip_suffix("/Cargo.toml")?;
    if build_dir.starts_with("build/lambda/")
        && !build_dir.contains("..")
        && !build_dir.contains('\\')
        && !build_dir.contains('\0')
    {
        Some(build_dir)
    } else {
        None
    }
}

fn validate_features(alias: &str, features: &[String]) -> Result<(), LambdaCompileError> {
    let mut seen = BTreeSet::new();
    for feature in features {
        if feature.is_empty()
            || !feature.bytes().all(|byte| {
                byte.is_ascii_alphanumeric()
                    || matches!(byte, b'-' | b'_' | b'.' | b':' | b'/' | b'+')
            })
            || !seen.insert(feature)
        {
            return Err(LambdaCompileError::InvalidDependencyFeature {
                alias: alias.to_owned(),
                feature: feature.clone(),
            });
        }
    }
    Ok(())
}

fn valid_alias(value: &str) -> bool {
    let mut bytes = value.bytes();
    let Some(first) = bytes.next() else {
        return false;
    };
    (first.is_ascii_lowercase() || first == b'_')
        && bytes.all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_')
}

fn valid_package(value: &str) -> bool {
    !value.is_empty()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
}

fn valid_repository(value: &str) -> bool {
    let mut parts = value.split('/');
    let owner = parts.next().unwrap_or_default();
    let repo = parts.next().unwrap_or_default();
    !owner.is_empty()
        && !repo.is_empty()
        && parts.next().is_none()
        && owner.bytes().all(repository_byte)
        && repo.bytes().all(repository_byte)
}

fn repository_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.')
}

fn valid_registry(value: &str) -> bool {
    !value.is_empty()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
}

fn valid_exact_version(value: &str) -> bool {
    !value.is_empty()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'+'))
        && value.bytes().any(|byte| byte == b'.')
        && !value
            .chars()
            .next()
            .is_some_and(|ch| matches!(ch, '^' | '~' | '>' | '<' | '=' | '*'))
}

fn is_lower_hex(value: &str, len: usize) -> bool {
    value.len() == len
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
}

fn sha256_json<T: Serialize>(value: &T) -> Result<String, serde_json::Error> {
    serde_json::to_vec(value).map(|bytes| sha256_hex(&bytes))
}

fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(64);
    for byte in digest {
        use std::fmt::Write as _;
        let _ = write!(&mut out, "{byte:02x}");
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lambda_build::{LambdaHttpProjection, LambdaServerRole, LambdaSourceIdentity};

    const SHA: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const DIGEST: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    fn source(kind: LambdaFunctionKind) -> LambdaSourceIdentity {
        match kind {
            LambdaFunctionKind::Web => LambdaSourceIdentity {
                repository: "example/demo-web-server.rs".to_owned(),
                commit_sha: SHA.to_owned(),
                lambda_source: "src/pages/demo/lambda.rs".to_owned(),
                lambda_source_sha256: DIGEST.to_owned(),
                role: LambdaServerRole::WebServer,
            },
            LambdaFunctionKind::Api => LambdaSourceIdentity {
                repository: "example/demo-api-server.rs".to_owned(),
                commit_sha: SHA.to_owned(),
                lambda_source: "src/routes/demo/lambda.rs".to_owned(),
                lambda_source_sha256: DIGEST.to_owned(),
                role: LambdaServerRole::ApiServer,
            },
        }
    }

    fn web_unit(provider: LambdaBuildProvider) -> LambdaFunctionBuildUnit {
        LambdaFunctionBuildUnit::new(
            provider,
            LambdaFunctionKind::Web,
            LambdaCarrier::HttpIngress,
            source(LambdaFunctionKind::Web),
            Some("/demo".to_owned()),
            vec![],
            vec![],
            vec![],
        )
        .expect("web unit")
    }

    fn api_unit(rpc: bool) -> LambdaFunctionBuildUnit {
        LambdaFunctionBuildUnit::new(
            LambdaBuildProvider::Aws,
            LambdaFunctionKind::Api,
            LambdaCarrier::HttpIngress,
            source(LambdaFunctionKind::Api),
            None,
            vec![LambdaHttpProjection {
                method: "GET".to_owned(),
                path: "/demo".to_owned(),
                operation_key: "demo.read".to_owned(),
            }],
            vec!["demo.read".to_owned()],
            if rpc {
                vec!["demo.read".to_owned()]
            } else {
                vec![]
            },
        )
        .expect("api unit")
    }

    fn git(alias: &str, package: &str, features: &[&str]) -> LambdaCompileDependency {
        LambdaCompileDependency::git(
            alias,
            package,
            "example/dependency",
            SHA,
            features.iter().map(|value| (*value).to_owned()).collect(),
            false,
        )
    }

    fn tokio() -> LambdaCompileDependency {
        LambdaCompileDependency::registry(
            "tokio",
            "tokio",
            "crates_io",
            "1.46.1",
            vec!["macros".to_owned(), "rt-multi-thread".to_owned()],
            false,
        )
    }

    fn web_dependencies(provider: LambdaBuildProvider) -> Vec<LambdaCompileDependency> {
        vec![
            git("ores_api_docs_client", "ores-api-docs-client", &[]),
            git(
                "ores_lambda_runtime",
                "demo-lambdas",
                &[if provider == LambdaBuildProvider::Aws {
                    "page-aws"
                } else {
                    "page-gcp"
                }],
            ),
            git("ores_web_app", "demo-web-server", &[]),
            tokio(),
        ]
    }

    fn api_dependencies(provider: LambdaBuildProvider) -> Vec<LambdaCompileDependency> {
        vec![
            git("ores_api_app", "demo-api-server", &[]),
            git("ores_api_docs", "ores-api-docs", &[]),
            git("ores_fn_adapters", "ores-fn-adapters", &[]),
            git(
                "ores_lambda_runtime",
                "demo-lambdas",
                &[if provider == LambdaBuildProvider::Aws {
                    "aws"
                } else {
                    "http"
                }],
            ),
            tokio(),
        ]
    }

    #[test]
    fn api_and_web_dependency_alias_sets_are_closed() {
        let web = LambdaCompileUnit::new(
            &web_unit(LambdaBuildProvider::Aws),
            LambdaBuildArchitecture::X86_64,
            web_dependencies(LambdaBuildProvider::Aws),
            LambdaCargoPolicy::Locked,
        )
        .expect("web compile unit");
        assert_eq!(web.dependencies.len(), 4);

        let api = LambdaCompileUnit::new(
            &api_unit(true),
            LambdaBuildArchitecture::X86_64,
            api_dependencies(LambdaBuildProvider::Aws),
            LambdaCargoPolicy::Frozen,
        )
        .expect("api compile unit");
        assert_eq!(api.dependencies.len(), 5);
    }

    #[test]
    fn provider_runtime_profile_and_target_are_derived() {
        let unit = LambdaCompileUnit::new(
            &web_unit(LambdaBuildProvider::Gcp),
            LambdaBuildArchitecture::X86_64,
            web_dependencies(LambdaBuildProvider::Gcp),
            LambdaCargoPolicy::Frozen,
        )
        .expect("gcp compile unit");
        assert_eq!(unit.runtime_profile, LambdaRuntimeProfile::GcpWebHttp);
        assert_eq!(unit.target_triple, "x86_64-unknown-linux-gnu");
        assert_eq!(
            unit.executable_path,
            format!(
                "{}/compiled/x86_64-unknown-linux-gnu/release/{LAMBDA_COMPILE_BINARY}",
                web_unit(LambdaBuildProvider::Gcp).build_dir
            )
        );
    }

    #[test]
    fn gcp_arm64_fails_closed_until_certified() {
        let error = LambdaCompileUnit::new(
            &web_unit(LambdaBuildProvider::Gcp),
            LambdaBuildArchitecture::Arm64,
            web_dependencies(LambdaBuildProvider::Gcp),
            LambdaCargoPolicy::Locked,
        )
        .expect_err("gcp arm64 must fail");
        assert!(matches!(
            error,
            LambdaCompileError::UnsupportedArchitecture { .. }
        ));
    }

    #[test]
    fn repository_dependencies_require_immutable_zed_pkg_git_coordinates() {
        let mut dependencies = api_dependencies(LambdaBuildProvider::Aws);
        let app = dependencies
            .iter_mut()
            .find(|dependency| dependency.alias == "ores_api_app")
            .expect("app dependency");
        app.rev = Some("main".to_owned());
        let error = LambdaCompileUnit::new(
            &api_unit(true),
            LambdaBuildArchitecture::X86_64,
            dependencies,
            LambdaCargoPolicy::Locked,
        )
        .expect_err("floating rev must fail");
        assert!(matches!(
            error,
            LambdaCompileError::InvalidDependencyCoordinate(_)
        ));
    }

    #[test]
    fn local_paths_are_not_part_of_release_compile_v1() {
        assert_eq!(
            [
                LambdaCompileDependencyKind::Git,
                LambdaCompileDependencyKind::Registry
            ]
            .len(),
            2
        );
    }

    #[test]
    fn no_rpc_publication_does_not_change_dependency_closure() {
        let public = LambdaCompileUnit::new(
            &api_unit(true),
            LambdaBuildArchitecture::X86_64,
            api_dependencies(LambdaBuildProvider::Aws),
            LambdaCargoPolicy::Locked,
        )
        .expect("public");
        let no_rpc = LambdaCompileUnit::new(
            &api_unit(false),
            LambdaBuildArchitecture::X86_64,
            api_dependencies(LambdaBuildProvider::Aws),
            LambdaCargoPolicy::Locked,
        )
        .expect("no rpc");

        assert_eq!(public.dependencies, no_rpc.dependencies);
        assert_eq!(public.runtime_profile, no_rpc.runtime_profile);
        assert_ne!(public.build_unit_sha256, no_rpc.build_unit_sha256);
    }

    #[test]
    fn cargo_manifest_is_isolated_and_immutable() {
        let unit = LambdaCompileUnit::new(
            &api_unit(true),
            LambdaBuildArchitecture::X86_64,
            api_dependencies(LambdaBuildProvider::Aws),
            LambdaCargoPolicy::Frozen,
        )
        .expect("compile unit");
        let manifest = unit.render_cargo_manifest().expect("manifest");
        assert!(manifest.contains("[workspace]"));
        assert!(manifest.contains("resolver = \"2\""));
        assert!(manifest.contains("rev = \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\""));
        assert!(manifest.contains("version = \"=1.46.1\""));
        assert!(!manifest.contains("path = \"../"));
        assert!(!manifest.contains("workspace = true"));
    }

    #[test]
    fn runtime_features_are_derived_not_sniffed() {
        let mut dependencies = api_dependencies(LambdaBuildProvider::Aws);
        let runtime = dependencies
            .iter_mut()
            .find(|dependency| dependency.alias == "ores_lambda_runtime")
            .expect("runtime");
        runtime.features = vec!["page-aws".to_owned()];
        let error = LambdaCompileUnit::new(
            &api_unit(true),
            LambdaBuildArchitecture::X86_64,
            dependencies,
            LambdaCargoPolicy::Locked,
        )
        .expect_err("wrong profile must fail");
        assert!(matches!(
            error,
            LambdaCompileError::RuntimeFeatureMismatch { .. }
        ));
    }

    #[test]
    fn compile_receipt_binds_lock_toolchain_and_executable_without_deploy() {
        let unit = LambdaCompileUnit::new(
            &api_unit(true),
            LambdaBuildArchitecture::X86_64,
            api_dependencies(LambdaBuildProvider::Aws),
            LambdaCargoPolicy::Frozen,
        )
        .expect("compile unit");
        let receipt = LambdaCompileReceipt::new(
            &unit,
            b"cargo lock bytes",
            "rustc 1.88.0",
            "cargo 1.88.0",
            b"elf bytes",
        )
        .expect("receipt");
        assert_eq!(receipt.executable_path, unit.executable_path);
        assert!(!receipt.deploy_mutation_performed);
        assert_ne!(receipt.cargo_lock_sha256, receipt.executable_sha256);
    }
}
