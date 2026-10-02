#![forbid(unsafe_code)]

use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

pub const LAMBDA_BUILD_SCHEMA: &str = "ores.lambda.build.v2";
pub const LAMBDA_BUILD_GENERATOR: &str = "ores-stack";
pub const LAMBDA_BUILD_ROOT: &str = "build/lambda";
pub const LAMBDA_BUILD_TMP: &str = "build/tmp";
pub const LAMBDA_BUILD_MANIFEST: &str = "build/lambda/manifest.json";

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LambdaBuildProvider {
    Aws,
    Gcp,
}

impl LambdaBuildProvider {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Aws => "aws",
            Self::Gcp => "gcp",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LambdaFunctionKind {
    Web,
    Api,
}

impl LambdaFunctionKind {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Web => "web",
            Self::Api => "api",
        }
    }
}

/// One physical provider function has exactly one trust carrier. This is build
/// identity and is never inferred by sniffing caller-controlled payload bytes.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LambdaCarrier {
    HttpIngress,
    DirectInvoke,
}

impl LambdaCarrier {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::HttpIngress => "http_ingress",
            Self::DirectInvoke => "direct_invoke",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LambdaServerRole {
    WebServer,
    AdminWebServer,
    ApiServer,
    AdminApiServer,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LambdaSourceIdentity {
    pub repository: String,
    pub commit_sha: String,
    pub lambda_source: String,
    /// SHA-256 of the exact generated `lambda.rs` bytes authorized for this
    /// build unit. Repository/path/commit metadata alone is not byte provenance.
    pub lambda_source_sha256: String,
    pub role: LambdaServerRole,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LambdaHttpProjection {
    pub method: String,
    pub path: String,
    pub operation_key: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LambdaFunctionBuildUnit {
    pub id: String,
    pub provider: LambdaBuildProvider,
    pub kind: LambdaFunctionKind,
    pub carrier: LambdaCarrier,
    pub source: LambdaSourceIdentity,
    pub build_dir: String,
    pub wrapper_main: String,
    pub source_copy: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub page_route: Option<String>,
    pub http_projections: Vec<LambdaHttpProjection>,
    /// Closed semantic operation inventory owned by this physical API function.
    pub operation_keys: Vec<String>,
    /// Public `/v1/rpc` publication subset for an HTTP-ingress API function.
    /// It is independent from semantic eligibility and may be empty.
    pub rpc_operation_keys: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LambdaBuildManifest {
    pub schema_version: String,
    pub generated_by: String,
    pub functions: Vec<LambdaFunctionBuildUnit>,
}

#[derive(Clone, Debug, PartialEq, Eq, Error)]
pub enum LambdaBuildError {
    #[error("lambda build repository must be an owner/name identity using safe GitHub repository characters: {0:?}")]
    InvalidRepository(String),
    #[error("lambda build source commit must be a 40-character lowercase hex Git SHA: {0:?}")]
    InvalidCommit(String),
    #[error("lambda source SHA-256 must be 64 lowercase hexadecimal characters: {0:?}")]
    InvalidSourceDigest(String),
    #[error("lambda source must be a normalized repository-relative generated lambda.rs path: {0:?}")]
    InvalidLambdaSource(String),
    #[error("lambda function kind {kind:?} is incompatible with source role {role:?} and path {lambda_source:?}")]
    InvalidKindRole {
        kind: LambdaFunctionKind,
        role: LambdaServerRole,
        lambda_source: String,
    },
    #[error("carrier {carrier:?} is incompatible with function kind {kind:?}")]
    InvalidCarrier {
        kind: LambdaFunctionKind,
        carrier: LambdaCarrier,
    },
    #[error("provider {provider:?} does not currently certify carrier {carrier:?}")]
    UnsupportedProviderCarrier {
        provider: LambdaBuildProvider,
        carrier: LambdaCarrier,
    },
    #[error("web function {0:?} must use HTTP ingress, declare one absolute page_route, and contain no API inventories")]
    InvalidWebCardinality(String),
    #[error("API function {0:?} must declare at least one semantic operation key")]
    EmptyApiOperationSet(String),
    #[error("HTTP-ingress API function {0:?} must expose at least one HTTP projection or public RPC operation")]
    EmptyHttpIngress(String),
    #[error("direct-invoke API function {0:?} must not declare HTTP projections or public RPC publication")]
    InvalidDirectPublication(String),
    #[error("HTTP-ingress API function {function:?} carries semantic operation {operation_key:?} that is reachable through neither an HTTP projection nor public RPC")]
    UnreachableHttpOperation {
        function: String,
        operation_key: String,
    },
    #[error("API function {function:?} has invalid {field} operation key {operation_key:?}")]
    InvalidOperationKey {
        function: String,
        field: &'static str,
        operation_key: String,
    },
    #[error("API function {function:?} repeats {field} operation key {operation_key:?}")]
    DuplicateOperationKey {
        function: String,
        field: &'static str,
        operation_key: String,
    },
    #[error("API function {function:?} publishes RPC key {operation_key:?} outside its semantic operation_keys")]
    RpcOperationNotSemantic {
        function: String,
        operation_key: String,
    },
    #[error("API function {function:?} has invalid HTTP projection {method:?} {path:?}: {reason}")]
    InvalidHttpProjection {
        function: String,
        method: String,
        path: String,
        reason: String,
    },
    #[error("API function {function:?} declares colliding HTTP projection {method} {path}")]
    DuplicateHttpProjection {
        function: String,
        method: String,
        path: String,
    },
    #[error("function build path escaped the generated root: {0:?}")]
    UnsafeBuildPath(String),
    #[error("lambda build manifest schema_version must be {LAMBDA_BUILD_SCHEMA:?}; got {0:?}")]
    InvalidSchemaVersion(String),
    #[error("lambda build manifest generated_by must be {LAMBDA_BUILD_GENERATOR:?}; got {0:?}")]
    InvalidGenerator(String),
    #[error("duplicate function build directory in manifest: {0}")]
    DuplicateBuildDir(String),
    #[error("duplicate provider/kind/carrier/source unit in manifest: {0}")]
    DuplicateSourceUnit(String),
}

impl LambdaFunctionBuildUnit {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        provider: LambdaBuildProvider,
        kind: LambdaFunctionKind,
        carrier: LambdaCarrier,
        source: LambdaSourceIdentity,
        page_route: Option<String>,
        mut http_projections: Vec<LambdaHttpProjection>,
        mut operation_keys: Vec<String>,
        mut rpc_operation_keys: Vec<String>,
    ) -> Result<Self, LambdaBuildError> {
        validate_source(&source)?;
        validate_kind_role(kind, &source)?;
        http_projections.sort_by(|left, right| {
            (left.path.as_str(), left.method.as_str(), left.operation_key.as_str()).cmp(&(
                right.path.as_str(),
                right.method.as_str(),
                right.operation_key.as_str(),
            ))
        });
        operation_keys.sort();
        rpc_operation_keys.sort();

        let id = lambda_function_id(kind, carrier, &source.repository, &source.lambda_source);
        let build_dir = format!(
            "{LAMBDA_BUILD_ROOT}/{}/funcs/{}/{}/{}",
            provider.as_str(),
            kind.as_str(),
            carrier.as_str(),
            id
        );
        let wrapper_main = format!("{build_dir}/main.rs");
        let source_copy = format!("{build_dir}/lambda.rs");
        let unit = Self {
            id,
            provider,
            kind,
            carrier,
            source,
            build_dir,
            wrapper_main,
            source_copy,
            page_route,
            http_projections,
            operation_keys,
            rpc_operation_keys,
        };
        unit.validate()?;
        Ok(unit)
    }

    pub fn validate(&self) -> Result<(), LambdaBuildError> {
        validate_source(&self.source)?;
        validate_kind_role(self.kind, &self.source)?;
        validate_provider_carrier(self.provider, self.kind, self.carrier)?;

        let expected_id = lambda_function_id(
            self.kind,
            self.carrier,
            &self.source.repository,
            &self.source.lambda_source,
        );
        let expected_dir = format!(
            "{LAMBDA_BUILD_ROOT}/{}/funcs/{}/{}/{}",
            self.provider.as_str(),
            self.kind.as_str(),
            self.carrier.as_str(),
            expected_id
        );
        if self.id != expected_id
            || self.build_dir != expected_dir
            || self.build_dir.contains("..")
            || self.build_dir.contains('\\')
            || self.wrapper_main != format!("{}/main.rs", self.build_dir)
            || self.source_copy != format!("{}/lambda.rs", self.build_dir)
        {
            return Err(LambdaBuildError::UnsafeBuildPath(self.build_dir.clone()));
        }

        match self.kind {
            LambdaFunctionKind::Web => self.validate_web(),
            LambdaFunctionKind::Api => self.validate_api(),
        }
    }

    fn validate_web(&self) -> Result<(), LambdaBuildError> {
        let valid_page = self.page_route.as_deref().is_some_and(valid_absolute_route);
        if self.carrier != LambdaCarrier::HttpIngress
            || !valid_page
            || !self.http_projections.is_empty()
            || !self.operation_keys.is_empty()
            || !self.rpc_operation_keys.is_empty()
        {
            return Err(LambdaBuildError::InvalidWebCardinality(self.id.clone()));
        }
        Ok(())
    }

    fn validate_api(&self) -> Result<(), LambdaBuildError> {
        if self.page_route.is_some() || self.operation_keys.is_empty() {
            return Err(LambdaBuildError::EmptyApiOperationSet(self.id.clone()));
        }

        let semantic = validate_operation_set(&self.id, "operation_keys", &self.operation_keys)?;
        let rpc = validate_operation_set(&self.id, "rpc_operation_keys", &self.rpc_operation_keys)?;
        for key in &rpc {
            if !semantic.contains(key) {
                return Err(LambdaBuildError::RpcOperationNotSemantic {
                    function: self.id.clone(),
                    operation_key: (*key).to_owned(),
                });
            }
        }

        if self.carrier == LambdaCarrier::DirectInvoke {
            if !self.http_projections.is_empty() || !self.rpc_operation_keys.is_empty() {
                return Err(LambdaBuildError::InvalidDirectPublication(self.id.clone()));
            }
            return Ok(());
        }

        if self.http_projections.is_empty() && self.rpc_operation_keys.is_empty() {
            return Err(LambdaBuildError::EmptyHttpIngress(self.id.clone()));
        }

        let mut routes = BTreeSet::new();
        let mut projected = BTreeSet::new();
        for projection in &self.http_projections {
            if !valid_http_method(&projection.method) || !valid_absolute_route(&projection.path) {
                return Err(LambdaBuildError::InvalidHttpProjection {
                    function: self.id.clone(),
                    method: projection.method.clone(),
                    path: projection.path.clone(),
                    reason: "method must start with an uppercase ASCII letter and contain only uppercase ASCII letters/hyphens; path must be absolute"
                        .to_owned(),
                });
            }
            if !semantic.contains(projection.operation_key.as_str()) {
                return Err(LambdaBuildError::InvalidHttpProjection {
                    function: self.id.clone(),
                    method: projection.method.clone(),
                    path: projection.path.clone(),
                    reason: format!(
                        "operation key {:?} is absent from semantic operation_keys",
                        projection.operation_key
                    ),
                });
            }
            let shape = (
                projection.method.as_str(),
                normalized_route_shape(&projection.path),
            );
            if !routes.insert(shape) {
                return Err(LambdaBuildError::DuplicateHttpProjection {
                    function: self.id.clone(),
                    method: projection.method.clone(),
                    path: projection.path.clone(),
                });
            }
            projected.insert(projection.operation_key.as_str());
        }

        if let Some(unreachable) = self
            .operation_keys
            .iter()
            .find(|key| !projected.contains(key.as_str()) && !rpc.contains(key.as_str()))
        {
            return Err(LambdaBuildError::UnreachableHttpOperation {
                function: self.id.clone(),
                operation_key: unreachable.clone(),
            });
        }
        Ok(())
    }
}

impl LambdaBuildManifest {
    #[must_use]
    pub fn new(mut functions: Vec<LambdaFunctionBuildUnit>) -> Self {
        functions.sort_by(|left, right| left.build_dir.cmp(&right.build_dir));
        Self {
            schema_version: LAMBDA_BUILD_SCHEMA.to_owned(),
            generated_by: LAMBDA_BUILD_GENERATOR.to_owned(),
            functions,
        }
    }

    pub fn validate(&self) -> Result<(), LambdaBuildError> {
        if self.schema_version != LAMBDA_BUILD_SCHEMA {
            return Err(LambdaBuildError::InvalidSchemaVersion(self.schema_version.clone()));
        }
        if self.generated_by != LAMBDA_BUILD_GENERATOR {
            return Err(LambdaBuildError::InvalidGenerator(self.generated_by.clone()));
        }
        let mut build_dirs = BTreeSet::new();
        let mut source_units = BTreeSet::new();
        for function in &self.functions {
            function.validate()?;
            if !build_dirs.insert(function.build_dir.clone()) {
                return Err(LambdaBuildError::DuplicateBuildDir(function.build_dir.clone()));
            }
            let key = format!(
                "{}:{}:{}:{}:{}",
                function.provider.as_str(),
                function.kind.as_str(),
                function.carrier.as_str(),
                function.source.repository,
                function.source.lambda_source
            );
            if !source_units.insert(key.clone()) {
                return Err(LambdaBuildError::DuplicateSourceUnit(key));
            }
        }
        Ok(())
    }

    pub fn to_pretty_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string_pretty(self).map(|mut json| {
            json.push('\n');
            json
        })
    }
}

fn validate_provider_carrier(
    provider: LambdaBuildProvider,
    kind: LambdaFunctionKind,
    carrier: LambdaCarrier,
) -> Result<(), LambdaBuildError> {
    if kind == LambdaFunctionKind::Web && carrier != LambdaCarrier::HttpIngress {
        return Err(LambdaBuildError::InvalidCarrier { kind, carrier });
    }
    if provider == LambdaBuildProvider::Gcp && carrier == LambdaCarrier::DirectInvoke {
        return Err(LambdaBuildError::UnsupportedProviderCarrier { provider, carrier });
    }
    Ok(())
}

fn validate_operation_set<'a>(
    function: &str,
    field: &'static str,
    keys: &'a [String],
) -> Result<BTreeSet<&'a str>, LambdaBuildError> {
    let mut out = BTreeSet::new();
    for key in keys {
        if !valid_operation_key(key) {
            return Err(LambdaBuildError::InvalidOperationKey {
                function: function.to_owned(),
                field,
                operation_key: key.clone(),
            });
        }
        if !out.insert(key.as_str()) {
            return Err(LambdaBuildError::DuplicateOperationKey {
                function: function.to_owned(),
                field,
                operation_key: key.clone(),
            });
        }
    }
    Ok(out)
}

fn validate_source(source: &LambdaSourceIdentity) -> Result<(), LambdaBuildError> {
    let mut repository_parts = source.repository.split('/');
    let owner = repository_parts.next().unwrap_or_default();
    let name = repository_parts.next().unwrap_or_default();
    if owner.is_empty()
        || name.is_empty()
        || repository_parts.next().is_some()
        || !owner.bytes().all(is_repository_byte)
        || !name.bytes().all(is_repository_byte)
    {
        return Err(LambdaBuildError::InvalidRepository(source.repository.clone()));
    }
    if !is_lower_hex(&source.commit_sha, 40) {
        return Err(LambdaBuildError::InvalidCommit(source.commit_sha.clone()));
    }
    if !is_lower_hex(&source.lambda_source_sha256, 64) {
        return Err(LambdaBuildError::InvalidSourceDigest(
            source.lambda_source_sha256.clone(),
        ));
    }
    if !valid_lambda_source(&source.lambda_source) {
        return Err(LambdaBuildError::InvalidLambdaSource(
            source.lambda_source.clone(),
        ));
    }
    Ok(())
}

fn is_lower_hex(value: &str, len: usize) -> bool {
    value.len() == len
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
}

fn valid_lambda_source(source: &str) -> bool {
    if source.is_empty()
        || source.starts_with('/')
        || source.contains("..")
        || source.contains('\\')
        || source.contains('\0')
        || !source.ends_with("lambda.rs")
    {
        return false;
    }
    source == "src/pages/lambda.rs"
        || source == "src/routes/lambda.rs"
        || source.starts_with("src/pages/") && source.ends_with("/lambda.rs")
        || source.starts_with("src/routes/") && source.ends_with("/lambda.rs")
}

fn validate_kind_role(
    kind: LambdaFunctionKind,
    source: &LambdaSourceIdentity,
) -> Result<(), LambdaBuildError> {
    let valid = match (kind, source.role) {
        (LambdaFunctionKind::Web, LambdaServerRole::WebServer | LambdaServerRole::AdminWebServer) => {
            source.lambda_source.starts_with("src/pages/")
        }
        (LambdaFunctionKind::Api, LambdaServerRole::ApiServer | LambdaServerRole::AdminApiServer) => {
            source.lambda_source.starts_with("src/routes/")
        }
        _ => false,
    };
    if valid {
        Ok(())
    } else {
        Err(LambdaBuildError::InvalidKindRole {
            kind,
            role: source.role,
            lambda_source: source.lambda_source.clone(),
        })
    }
}

fn is_repository_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.')
}

fn valid_operation_key(key: &str) -> bool {
    let parts = key.split('.').collect::<Vec<_>>();
    parts.len() >= 2
        && parts.into_iter().all(|part| {
            let mut chars = part.chars();
            matches!(chars.next(), Some(first) if first.is_ascii_lowercase())
                && chars.all(|ch| {
                    ch.is_ascii_lowercase() || ch.is_ascii_digit() || ch == '_' || ch == '-'
                })
        })
}

fn valid_http_method(method: &str) -> bool {
    let mut bytes = method.bytes();
    matches!(bytes.next(), Some(first) if first.is_ascii_uppercase())
        && bytes.all(|byte| byte.is_ascii_uppercase() || byte == b'-')
}

fn valid_absolute_route(route: &str) -> bool {
    !route.is_empty()
        && route.starts_with('/')
        && !route.contains('\0')
        && !route.chars().any(char::is_control)
}

fn normalized_route_shape(path: &str) -> String {
    path.split('/')
        .map(|segment| {
            match segment
                .strip_prefix('{')
                .and_then(|rest| rest.strip_suffix('}'))
            {
                Some(capture) if capture.starts_with('*') => "{*}",
                Some(_) => "{}",
                None => segment,
            }
        })
        .collect::<Vec<_>>()
        .join("/")
}

#[must_use]
pub fn lambda_function_id(
    kind: LambdaFunctionKind,
    carrier: LambdaCarrier,
    repository: &str,
    lambda_source: &str,
) -> String {
    let authority = lambda_source
        .strip_prefix("src/pages/")
        .or_else(|| lambda_source.strip_prefix("src/routes/"))
        .unwrap_or(lambda_source)
        .strip_suffix("/lambda.rs")
        .unwrap_or(lambda_source)
        .strip_suffix("lambda.rs")
        .unwrap_or(lambda_source);
    let repository_name = repository.rsplit('/').next().unwrap_or(repository);
    let readable_source = format!("{repository_name}_{authority}_{}", carrier.as_str());
    let mut readable = String::new();
    let mut previous_separator = false;
    for ch in readable_source.chars() {
        if ch.is_ascii_alphanumeric() {
            readable.push(ch.to_ascii_lowercase());
            previous_separator = false;
        } else if !previous_separator && !readable.is_empty() {
            readable.push('_');
            previous_separator = true;
        }
    }
    while readable.ends_with('_') {
        readable.pop();
    }
    if readable.is_empty() {
        readable.push_str(kind.as_str());
    }
    readable.truncate(48);
    while readable.ends_with('_') {
        readable.pop();
    }
    let digest_input = format!(
        "{}\0{}\0{}\0{}",
        kind.as_str(),
        carrier.as_str(),
        repository,
        lambda_source
    );
    let digest = Sha256::digest(digest_input.as_bytes());
    let suffix = digest[..8]
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    format!("{readable}_{suffix}")
}

pub fn render_provider_main(unit: &LambdaFunctionBuildUnit) -> Result<String, LambdaBuildError> {
    unit.validate()?;
    let provider = unit.provider.as_str();
    let source = match (unit.kind, unit.carrier) {
        (LambdaFunctionKind::Web, LambdaCarrier::HttpIngress) => format!(
            "// @generated by ores-stack; DO NOT EDIT.\nmod lambda;\n\n#[tokio::main]\nasync fn main() -> Result<(), ::ores_lambda_runtime::RuntimeError> {{\n    let state = lambda::init_state().await.map_err(::ores_lambda_runtime::RuntimeError::state_init)?;\n    ::ores_lambda_runtime::{provider}::run_web(state, lambda::ORES_PAGE_ROUTE, lambda::ORES_PAGE_AXUM_PATHS, lambda::admit, lambda::run).await\n}}\n"
        ),
        (LambdaFunctionKind::Api, LambdaCarrier::HttpIngress) => format!(
            "// @generated by ores-stack; DO NOT EDIT.\nmod lambda;\n\n#[tokio::main]\nasync fn main() -> Result<(), ::ores_lambda_runtime::RuntimeError> {{\n    let state = lambda::init_state().await.map_err(::ores_lambda_runtime::RuntimeError::state_init)?;\n    ::ores_lambda_runtime::{provider}::run_api_http(state, lambda::HTTP_ROUTES, lambda::RPC_OPERATION_KEYS, lambda::run).await\n}}\n"
        ),
        (LambdaFunctionKind::Api, LambdaCarrier::DirectInvoke) => format!(
            "// @generated by ores-stack; DO NOT EDIT.\nmod lambda;\n\n#[tokio::main]\nasync fn main() -> Result<(), ::ores_lambda_runtime::RuntimeError> {{\n    let state = lambda::init_state().await.map_err(::ores_lambda_runtime::RuntimeError::state_init)?;\n    ::ores_lambda_runtime::{provider}::run_api_direct(state, lambda::OPERATION_KEYS, lambda::run).await\n}}\n"
        ),
        (kind, carrier) => return Err(LambdaBuildError::InvalidCarrier { kind, carrier }),
    };
    Ok(source)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn source(path: &str, role: LambdaServerRole) -> LambdaSourceIdentity {
        LambdaSourceIdentity {
            repository: "example/service".to_owned(),
            commit_sha: "a".repeat(40),
            lambda_source: path.to_owned(),
            lambda_source_sha256: "b".repeat(64),
            role,
        }
    }

    fn api_unit(
        provider: LambdaBuildProvider,
        carrier: LambdaCarrier,
        http: Vec<LambdaHttpProjection>,
        operation_keys: Vec<&str>,
        rpc_operation_keys: Vec<&str>,
    ) -> Result<LambdaFunctionBuildUnit, LambdaBuildError> {
        LambdaFunctionBuildUnit::new(
            provider,
            LambdaFunctionKind::Api,
            carrier,
            source("src/routes/users/lambda.rs", LambdaServerRole::ApiServer),
            None,
            http,
            operation_keys.into_iter().map(str::to_owned).collect(),
            rpc_operation_keys.into_iter().map(str::to_owned).collect(),
        )
    }

    fn projection(method: &str, path: &str, key: &str) -> LambdaHttpProjection {
        LambdaHttpProjection {
            method: method.to_owned(),
            path: path.to_owned(),
            operation_key: key.to_owned(),
        }
    }

    #[test]
    fn http_only_no_rpc_operation_is_packageable() {
        api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::HttpIngress,
            vec![projection("POST", "/users/reindex", "demo.users.reindex")],
            vec!["demo.users.reindex"],
            vec![],
        )
        .expect("HTTP-only no-RPC operation remains semantic");
    }

    #[test]
    fn rpc_only_http_ingress_is_packageable() {
        api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::HttpIngress,
            vec![],
            vec!["demo.jobs.status"],
            vec!["demo.jobs.status"],
        )
        .expect("RPC-only HTTP ingress is a valid physical function");
    }

    #[test]
    fn direct_only_no_rpc_operation_is_packageable() {
        let unit = api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::DirectInvoke,
            vec![],
            vec!["demo.jobs.run"],
            vec![],
        )
        .expect("direct-only operation remains semantic");
        assert!(unit.http_projections.is_empty());
        assert!(unit.rpc_operation_keys.is_empty());
    }

    #[test]
    fn http_ingress_rejects_dead_semantic_operations() {
        let error = api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::HttpIngress,
            vec![projection("GET", "/users/{id}", "demo.users.find")],
            vec!["demo.users.find", "demo.users.hidden"],
            vec![],
        )
        .expect_err("hidden semantic operation is unreachable on this carrier");
        assert!(matches!(error, LambdaBuildError::UnreachableHttpOperation { .. }));
    }

    #[test]
    fn direct_carrier_cannot_publish_http_or_rpc() {
        let error = api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::DirectInvoke,
            vec![],
            vec!["demo.jobs.run"],
            vec!["demo.jobs.run"],
        )
        .expect_err("direct artifact has no public RPC ingress");
        assert!(matches!(error, LambdaBuildError::InvalidDirectPublication(_)));
    }

    #[test]
    fn rpc_subset_cannot_widen_semantic_inventory() {
        let error = api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::HttpIngress,
            vec![],
            vec!["demo.users.find"],
            vec!["demo.users.find", "demo.users.hidden"],
        )
        .expect_err("RPC must be a subset");
        assert!(matches!(error, LambdaBuildError::RpcOperationNotSemantic { .. }));
    }

    #[test]
    fn constructor_rejects_duplicate_semantic_operation_keys() {
        let error = api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::HttpIngress,
            vec![projection("GET", "/users", "demo.users.find")],
            vec!["demo.users.find", "demo.users.find"],
            vec![],
        )
        .expect_err("constructor must preserve duplicate evidence for validation");
        assert!(matches!(
            error,
            LambdaBuildError::DuplicateOperationKey {
                field: "operation_keys",
                ..
            }
        ));
    }

    #[test]
    fn constructor_rejects_duplicate_rpc_operation_keys() {
        let error = api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::HttpIngress,
            vec![],
            vec!["demo.users.find"],
            vec!["demo.users.find", "demo.users.find"],
        )
        .expect_err("constructor and deserialized manifests must reject the same duplicate RPC key");
        assert!(matches!(
            error,
            LambdaBuildError::DuplicateOperationKey {
                field: "rpc_operation_keys",
                ..
            }
        ));
    }

    #[test]
    fn capture_name_only_http_collisions_are_rejected() {
        let error = api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::HttpIngress,
            vec![
                projection("GET", "/users/{id}", "demo.users.by_id"),
                projection("GET", "/users/{user_id}", "demo.users.by_user_id"),
            ],
            vec!["demo.users.by_id", "demo.users.by_user_id"],
            vec![],
        )
        .expect_err("same route shape must collide");
        assert!(matches!(error, LambdaBuildError::DuplicateHttpProjection { .. }));
    }

    #[test]
    fn source_digest_is_part_of_admission() {
        let mut src = source("src/routes/users/lambda.rs", LambdaServerRole::ApiServer);
        src.lambda_source_sha256 = "ABC".to_owned();
        let error = LambdaFunctionBuildUnit::new(
            LambdaBuildProvider::Aws,
            LambdaFunctionKind::Api,
            LambdaCarrier::DirectInvoke,
            src,
            None,
            vec![],
            vec!["demo.jobs.run".to_owned()],
            vec![],
        )
        .expect_err("digest must be immutable lowercase sha256");
        assert!(matches!(error, LambdaBuildError::InvalidSourceDigest(_)));
    }

    #[test]
    fn gcp_direct_invoke_fails_closed_until_a_runtime_canary_exists() {
        let error = api_unit(
            LambdaBuildProvider::Gcp,
            LambdaCarrier::DirectInvoke,
            vec![],
            vec!["demo.jobs.run"],
            vec![],
        )
        .expect_err("unsupported provider/carrier pair");
        assert!(matches!(error, LambdaBuildError::UnsupportedProviderCarrier { .. }));
    }

    #[test]
    fn web_wrapper_cannot_bypass_generated_admission() {
        let unit = LambdaFunctionBuildUnit::new(
            LambdaBuildProvider::Aws,
            LambdaFunctionKind::Web,
            LambdaCarrier::HttpIngress,
            source("src/pages/home/lambda.rs", LambdaServerRole::WebServer),
            Some("/home".to_owned()),
            vec![],
            vec![],
            vec![],
        )
        .expect("web");
        let rendered = render_provider_main(&unit).expect("wrapper");
        let admit = rendered.find("lambda::admit").expect("admission hook");
        let run = rendered.find("lambda::run").expect("run hook");
        assert!(admit < run);
        assert!(rendered.contains("run_web"));
        assert!(!rendered.contains("match "));
    }

    #[test]
    fn api_carriers_have_distinct_runtime_entrypoints() {
        let http = api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::HttpIngress,
            vec![projection("GET", "/users", "demo.users.list")],
            vec!["demo.users.list"],
            vec![],
        )
        .expect("http");
        let direct = api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::DirectInvoke,
            vec![],
            vec!["demo.users.list"],
            vec![],
        )
        .expect("direct");
        let http = render_provider_main(&http).expect("http wrapper");
        let direct = render_provider_main(&direct).expect("direct wrapper");
        assert!(http.contains("run_api_http"));
        assert!(http.contains("lambda::HTTP_ROUTES"));
        assert!(http.contains("lambda::RPC_OPERATION_KEYS"));
        assert!(!http.contains("run_api_direct"));
        assert!(direct.contains("run_api_direct"));
        assert!(direct.contains("lambda::OPERATION_KEYS"));
        assert!(!direct.contains("lambda::HTTP_ROUTES"));
    }

    #[test]
    fn manifest_round_trip_preserves_carrier_semantic_rpc_and_source_digest() {
        let unit = api_unit(
            LambdaBuildProvider::Aws,
            LambdaCarrier::HttpIngress,
            vec![projection("GET", "/users", "demo.users.find")],
            vec!["demo.users.find", "demo.users.rpc_only"],
            vec!["demo.users.rpc_only"],
        )
        .expect("unit");
        let manifest = LambdaBuildManifest::new(vec![unit]);
        let json = manifest.to_pretty_json().expect("json");
        let decoded: LambdaBuildManifest = serde_json::from_str(&json).expect("decode");
        decoded.validate().expect("decoded contract");
        assert_eq!(decoded.functions[0].carrier, LambdaCarrier::HttpIngress);
        assert_eq!(decoded.functions[0].operation_keys.len(), 2);
        assert_eq!(decoded.functions[0].rpc_operation_keys.len(), 1);
        assert_eq!(decoded.functions[0].source.lambda_source_sha256.len(), 64);
    }
}
