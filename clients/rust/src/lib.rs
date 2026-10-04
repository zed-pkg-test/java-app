//! Rust SDK for the zed-pkg registry. Reuses the DTOs and URL helpers from
//! `zed-interfaces`, so wire models and paths stay aligned with the contract.

use std::fmt;
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, percent_decode_str, utf8_percent_encode};
use serde::de::DeserializeOwned;
use sha2::{Digest, Sha256};
use zed_interfaces::registry::{
    self, ApiError, ClaimOrgRequest, ClaimOrgResponse, PackageMetadata, PublishMeta,
    PublishResponse, SearchResponse, VersionMetadata, YankRequest, YankResponse,
};

const SEGMENT: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~');

const DEFAULT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(30);
const MAX_ARTIFACT_BYTES: u64 = 100 * 1024 * 1024;
const DOWNLOAD_SLACK: u64 = 1024 * 1024;
const MAX_SEGMENT_BYTES: usize = 256;
pub const DEFAULT_MAX_RESPONSE_BYTES: u64 = 16 * 1024 * 1024;
pub const MAX_ERROR_BODY_BYTES: u64 = 16 * 1024;

fn invalid_input(message: impl Into<String>) -> Error {
    Error::InvalidInput(message.into())
}

fn validate_segment<'a>(segment: &'a str, name: &str) -> Result<&'a str> {
    if segment.trim().is_empty() {
        return Err(invalid_input(format!("{name} must not be blank")));
    }
    if matches!(segment, "." | "..") {
        return Err(invalid_input(format!("{name} must not be a dot segment")));
    }
    if segment.len() > MAX_SEGMENT_BYTES {
        return Err(invalid_input(format!(
            "{name} exceeds {MAX_SEGMENT_BYTES} UTF-8 bytes"
        )));
    }
    if segment.chars().any(char::is_control) {
        return Err(invalid_input(format!(
            "{name} must not contain control characters"
        )));
    }
    Ok(segment)
}

fn encode_segment(segment: &str) -> String {
    match segment {
        "." => "%2E".to_string(),
        ".." => "%2E%2E".to_string(),
        _ => utf8_percent_encode(segment, SEGMENT).to_string(),
    }
}

fn checked_segment(segment: &str, name: &str) -> Result<String> {
    validate_segment(segment, name)?;
    Ok(encode_segment(segment))
}

fn validate_path_segments(raw_path: &str, name: &str) -> Result<()> {
    for (index, encoded) in raw_path
        .split('/')
        .filter(|segment| !segment.is_empty())
        .enumerate()
    {
        let decoded = percent_decode_str(encoded)
            .decode_utf8()
            .map_err(|_| invalid_input(format!("{name} contains invalid percent encoding")))?;
        validate_segment(&decoded, &format!("{name} segment {}", index + 1))?;
        if decoded.contains('/') || decoded.contains('\\') {
            return Err(invalid_input(format!(
                "{name} segments must not contain encoded separators"
            )));
        }
    }
    Ok(())
}

fn validate_raw_base_path(raw: &str) -> Result<()> {
    let scheme_end = raw.find("://").ok_or(Error::InvalidBaseUrl)?;
    let authority_start = scheme_end + 3;
    let Some(relative_path_start) = raw[authority_start..].find('/') else {
        return Ok(());
    };
    let path_start = authority_start + relative_path_start;
    let path_end = raw[path_start..]
        .find(['?', '#'])
        .map_or(raw.len(), |offset| path_start + offset);
    validate_path_segments(&raw[path_start..path_end], "registry path")
        .map_err(|_| Error::InvalidBaseUrl)
}

fn validate_relative_download_path(raw: &str) -> Result<()> {
    if raw.starts_with('/') || raw.starts_with('\\') {
        return Err(invalid_input(
            "relative download URL must not replace the registry authority or gateway path",
        ));
    }
    let path_end = raw.find(['?', '#']).unwrap_or(raw.len());
    validate_path_segments(&raw[..path_end], "download URL")
}

fn download_limit(size: u64) -> u64 {
    if size > 0 {
        size.saturating_add(DOWNLOAD_SLACK).min(MAX_ARTIFACT_BYTES)
    } else {
        MAX_ARTIFACT_BYTES
    }
}

#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// `message` is bounded and explicitly inspectable; the Display output
    /// intentionally excludes arbitrary remote text.
    #[error("registry error {status}: {code}")]
    Api {
        status: u16,
        code: String,
        message: String,
    },
    #[error("invalid registry base URL")]
    InvalidBaseUrl,
    #[error("invalid client input: {0}")]
    InvalidInput(String),
    #[error("authenticated registry operation requires a nonblank bearer token")]
    MissingToken,
    #[error("{what} exceeded {limit} bytes")]
    ResponseTooLarge { what: String, limit: u64 },
    #[error("artifact exceeded {limit} bytes")]
    ArtifactTooLarge { limit: u64 },
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("artifact sha256 mismatch: expected {expected}, got {actual}")]
    Sha256Mismatch { expected: String, actual: String },
    #[error("{0}")]
    Other(String),
}

pub type Result<T> = std::result::Result<T, Error>;

pub struct Client {
    base: String,
    token: Option<String>,
    max_response_bytes: u64,
    http: reqwest::blocking::Client,
}

impl fmt::Debug for Client {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Client")
            .field("base", &self.base)
            .field("token", &self.token.as_ref().map(|_| "[REDACTED]"))
            .field("max_response_bytes", &self.max_response_bytes)
            .finish_non_exhaustive()
    }
}

impl Client {
    pub fn new(base_url: impl Into<String>) -> Result<Self> {
        let raw = base_url.into();
        let trimmed = raw.trim();
        validate_raw_base_path(trimmed)?;
        let mut parsed = reqwest::Url::parse(trimmed).map_err(|_| Error::InvalidBaseUrl)?;
        if !matches!(parsed.scheme(), "http" | "https")
            || parsed.host_str().is_none()
            || !parsed.username().is_empty()
            || parsed.password().is_some()
            || parsed.query().is_some()
            || parsed.fragment().is_some()
        {
            return Err(Error::InvalidBaseUrl);
        }
        let path = parsed.path().trim_end_matches('/').to_string();
        parsed.set_path(&path);
        let base = parsed.as_str().trim_end_matches('/').to_string();

        Ok(Self {
            base,
            token: None,
            max_response_bytes: DEFAULT_MAX_RESPONSE_BYTES,
            http: reqwest::blocking::Client::builder()
                .user_agent(concat!("zed-client-rust/", env!("CARGO_PKG_VERSION")))
                .connect_timeout(DEFAULT_TIMEOUT)
                .timeout(DEFAULT_TIMEOUT)
                .redirect(reqwest::redirect::Policy::none())
                .build()?,
        })
    }

    #[must_use]
    pub fn with_token(mut self, token: impl Into<String>) -> Self {
        let token = token.into();
        self.token = (!token.trim().is_empty()).then(|| token.trim().to_string());
        self
    }

    #[must_use]
    pub fn with_max_response_bytes(mut self, limit: u64) -> Self {
        self.max_response_bytes = limit.max(1);
        self
    }

    fn require_token(&self) -> Result<&str> {
        let token = self.token.as_deref().ok_or(Error::MissingToken)?;
        if token.chars().any(char::is_control) {
            return Err(invalid_input("token must not contain control characters"));
        }
        Ok(token)
    }

    fn url(&self, path: &str) -> String {
        format!("{}{path}", self.base)
    }

    fn read_capped(
        response: reqwest::blocking::Response,
        limit: u64,
        what: &str,
    ) -> Result<Vec<u8>> {
        let too_large = || Error::ResponseTooLarge {
            what: what.to_string(),
            limit,
        };
        if response
            .content_length()
            .is_some_and(|length| length > limit)
        {
            return Err(too_large());
        }
        let mut bytes = Vec::new();
        response
            .take(limit.saturating_add(1))
            .read_to_end(&mut bytes)?;
        if bytes.len() as u64 > limit {
            return Err(too_large());
        }
        Ok(bytes)
    }

    fn check(&self, response: reqwest::blocking::Response) -> Result<reqwest::blocking::Response> {
        if response.status().is_success() {
            return Ok(response);
        }
        let status = response.status().as_u16();
        let limit = self.max_response_bytes.min(MAX_ERROR_BODY_BYTES);
        let body = match Self::read_capped(response, limit, "registry error body") {
            Ok(bytes) => String::from_utf8_lossy(&bytes).into_owned(),
            Err(Error::ResponseTooLarge { .. }) => {
                return Err(Error::Api {
                    status,
                    code: format!("http_{status}"),
                    message: "registry error body exceeded the client limit".to_string(),
                });
            }
            Err(error) => return Err(error),
        };
        match serde_json::from_str::<ApiError>(&body) {
            Ok(error) => {
                let code = if error.code.trim().is_empty() {
                    format!("http_{status}")
                } else {
                    error.code.trim().to_string()
                };
                Err(Error::Api {
                    status,
                    code,
                    message: error.message,
                })
            }
            Err(_) => Err(Error::Api {
                status,
                code: format!("http_{status}"),
                message: body,
            }),
        }
    }

    fn json<T: DeserializeOwned>(&self, response: reqwest::blocking::Response) -> Result<T> {
        let response = self.check(response)?;
        let bytes = Self::read_capped(response, self.max_response_bytes, "registry response")?;
        serde_json::from_slice(&bytes)
            .map_err(|error| Error::Other(format!("invalid registry response: {error}")))
    }

    pub fn get_package(&self, org: &str, name: &str) -> Result<PackageMetadata> {
        let path = registry::package_path(
            &checked_segment(org, "org")?,
            &checked_segment(name, "name")?,
        );
        let response = self.http.get(self.url(&path)).send()?;
        self.json(response)
    }

    pub fn get_version(&self, org: &str, name: &str, version: &str) -> Result<VersionMetadata> {
        let path = registry::version_path(
            &checked_segment(org, "org")?,
            &checked_segment(name, "name")?,
            &checked_segment(version, "version")?,
        );
        let response = self.http.get(self.url(&path)).send()?;
        self.json(response)
    }

    pub fn search(&self, query: &str) -> Result<SearchResponse> {
        let response = self
            .http
            .get(self.url(&registry::search_path()))
            .query(&[("q", query)])
            .send()?;
        self.json(response)
    }

    pub fn claim_org(&self, slug: &str) -> Result<ClaimOrgResponse> {
        validate_segment(slug, "slug")?;
        let token = self.require_token()?;
        let response = self
            .http
            .post(self.url(&registry::orgs_path()))
            .bearer_auth(token)
            .json(&ClaimOrgRequest {
                slug: slug.to_string(),
            })
            .send()?;
        self.json(response)
    }

    pub fn set_yanked(
        &self,
        org: &str,
        name: &str,
        version: &str,
        yanked: bool,
    ) -> Result<YankResponse> {
        let path = registry::yank_path(
            &checked_segment(org, "org")?,
            &checked_segment(name, "name")?,
            &checked_segment(version, "version")?,
        );
        let token = self.require_token()?;
        let response = self
            .http
            .post(self.url(&path))
            .bearer_auth(token)
            .json(&YankRequest { yanked })
            .send()?;
        self.json(response)
    }

    /// Compatibility form retained for existing callers.
    pub fn yank(&self, org: &str, name: &str, version: &str, yanked: bool) -> Result<YankResponse> {
        self.set_yanked(org, name, version, yanked)
    }

    pub fn restore(&self, org: &str, name: &str, version: &str) -> Result<YankResponse> {
        self.set_yanked(org, name, version, false)
    }

    fn allowed_download_url(&self, raw: &str) -> Result<reqwest::Url> {
        let url = reqwest::Url::parse(raw)
            .map_err(|_| Error::Other("download URL is invalid".to_string()))?;
        if !url.username().is_empty() || url.password().is_some() || url.fragment().is_some() {
            return Err(Error::Other(
                "download URL contains credentials or fragment".to_string(),
            ));
        }
        if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
            return Err(Error::Other(format!(
                "refusing artifact download over `{}`",
                url.scheme()
            )));
        }
        let loopback = matches!(url.host_str(), Some("localhost"))
            || url
                .host_str()
                .and_then(|host| host.parse::<std::net::IpAddr>().ok())
                .is_some_and(|ip| ip.is_loopback());
        match url.scheme() {
            "https" => Ok(url),
            "http" if loopback || self.base.starts_with("http://") => Ok(url),
            scheme => Err(Error::Other(format!(
                "refusing artifact download over `{scheme}`"
            ))),
        }
    }

    fn resolve_download_url(&self, raw: &str, sha256: &str) -> Result<reqwest::Url> {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            let sha256 = checked_segment(sha256, "sha256")?;
            return reqwest::Url::parse(&self.url(&registry::artifact_path(&sha256)))
                .map_err(|_| Error::InvalidBaseUrl);
        }
        if let Ok(absolute) = reqwest::Url::parse(trimmed) {
            return self.allowed_download_url(absolute.as_str());
        }
        validate_relative_download_path(trimmed)?;
        let base =
            reqwest::Url::parse(&(self.base.clone() + "/")).map_err(|_| Error::InvalidBaseUrl)?;
        let resolved = base
            .join(trimmed)
            .map_err(|_| Error::Other("relative download URL is invalid".to_string()))?;
        self.allowed_download_url(resolved.as_str())
    }

    fn write_atomic(dest: &Path, bytes: &[u8]) -> Result<()> {
        let parent = dest.parent().unwrap_or_else(|| Path::new("."));
        fs::create_dir_all(parent)?;
        let name = dest
            .file_name()
            .and_then(|value| value.to_str())
            .unwrap_or("artifact");
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let temporary = parent.join(format!(".{name}.zed-{}-{nonce}.tmp", std::process::id()));
        let result = (|| -> Result<()> {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary)?;
            file.write_all(bytes)?;
            file.sync_all()?;
            drop(file);
            fs::rename(&temporary, dest)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        result
    }

    pub fn download_artifact(&self, version: &VersionMetadata, dest: &Path) -> Result<()> {
        let url = self.resolve_download_url(&version.download_url, &version.sha256)?;
        // The shared HTTP client refuses redirects. No bearer token is attached:
        // download_url may be a third-party presigned URL.
        let response = self.check(self.http.get(url).send()?)?;
        let limit = download_limit(version.size);
        let bytes = match Self::read_capped(response, limit, "artifact") {
            Ok(bytes) => bytes,
            Err(Error::ResponseTooLarge { .. }) => {
                return Err(Error::ArtifactTooLarge { limit });
            }
            Err(error) => return Err(error),
        };
        verify_sha256(&bytes, &version.sha256)?;
        Self::write_atomic(dest, &bytes)
    }

    pub fn publish(&self, meta: &PublishMeta, artifact: &Path) -> Result<PublishResponse> {
        let token = self.require_token()?;
        let package = &meta.manifest.package;
        validate_segment(&package.org, "meta.manifest.package.org")?;
        validate_segment(&package.name, "meta.manifest.package.name")?;
        validate_segment(&package.version, "meta.manifest.package.version")?;
        let metadata = fs::metadata(artifact)?;
        if !metadata.is_file() {
            return Err(invalid_input("artifact must be a regular file"));
        }
        if metadata.len() > MAX_ARTIFACT_BYTES {
            return Err(Error::ArtifactTooLarge {
                limit: MAX_ARTIFACT_BYTES,
            });
        }
        let filename = safe_filename(&format!(
            "{}-{}-{}.tar.gz",
            package.org, package.name, package.version
        ));
        let part = reqwest::blocking::multipart::Part::file(artifact)?.file_name(filename);
        let form = reqwest::blocking::multipart::Form::new()
            .text(
                registry::PUBLISH_META_FIELD,
                serde_json::to_string(meta).map_err(|error| Error::Other(error.to_string()))?,
            )
            .part(registry::PUBLISH_ARTIFACT_FIELD, part);
        let response = self
            .http
            .put(self.url(&registry::version_path(
                &checked_segment(&package.org, "meta.manifest.package.org")?,
                &checked_segment(&package.name, "meta.manifest.package.name")?,
                &checked_segment(&package.version, "meta.manifest.package.version")?,
            )))
            .bearer_auth(token)
            .multipart(form)
            .send()?;
        self.json(response)
    }
}

fn safe_filename(value: &str) -> String {
    value
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-') {
                character
            } else {
                '_'
            }
        })
        .collect()
}

pub fn verify_sha256(bytes: &[u8], expected: &str) -> Result<()> {
    let actual = hex::encode(Sha256::digest(bytes));
    if !actual.eq_ignore_ascii_case(expected) {
        return Err(Error::Sha256Mismatch {
            expected: expected.to_string(),
            actual,
        });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha_verification_is_case_insensitive() {
        let bytes = b"zed";
        let good = hex::encode(Sha256::digest(bytes));
        assert!(verify_sha256(bytes, &good.to_uppercase()).is_ok());
        assert!(matches!(
            verify_sha256(bytes, "00"),
            Err(Error::Sha256Mismatch { .. })
        ));
    }

    #[test]
    fn base_url_is_validated_and_token_is_redacted() {
        let client = Client::new(" https://registry.zpkg.tech/gateway/// ")
            .unwrap()
            .with_token("very-secret");
        assert_eq!(
            client.url(&registry::package_path("acme", "kit")),
            "https://registry.zpkg.tech/gateway/v1/packages/acme/kit"
        );
        let debug = format!("{client:?}");
        assert!(debug.contains("[REDACTED]"));
        assert!(!debug.contains("very-secret"));
        for invalid in [
            "relative/path",
            "ftp://registry.test",
            "https://user:secret@registry.test",
            "https://registry.test?tenant=one",
            "https://registry.test#fragment",
            "https://registry.test/../admin",
            "https://registry.test/%2e%2e/admin",
            "https://registry.test/a%2Fb",
        ] {
            assert!(Client::new(invalid).is_err(), "accepted {invalid}");
        }
    }

    #[test]
    fn api_error_display_excludes_remote_message() {
        let error = Error::Api {
            status: 502,
            code: "http_502".to_string(),
            message: "provider-secret".to_string(),
        };
        assert_eq!(error.to_string(), "registry error 502: http_502");
        assert!(!error.to_string().contains("provider-secret"));
    }

    #[test]
    fn hostile_segments_and_missing_tokens_fail_before_transport() {
        let client = Client::new("http://127.0.0.1:9").unwrap();
        for value in ["", "   ", ".", "..", "line\nbreak"] {
            assert!(matches!(
                client.get_package(value, "kit"),
                Err(Error::InvalidInput(_))
            ));
        }
        assert_eq!(encode_segment(".."), "%2E%2E");
        assert!(matches!(client.claim_org("acme"), Err(Error::MissingToken)));
        assert!(matches!(
            client.yank("acme", "kit", "1.2.0", true),
            Err(Error::MissingToken)
        ));
        assert!(matches!(
            client.restore("acme", "kit", "1.2.0"),
            Err(Error::MissingToken)
        ));

        let unsafe_token = Client::new("http://127.0.0.1:9")
            .unwrap()
            .with_token("token\r\nheader");
        assert!(matches!(
            unsafe_token.claim_org("acme"),
            Err(Error::InvalidInput(_))
        ));
    }

    #[test]
    fn path_segments_are_percent_encoded() {
        assert_eq!(encode_segment("1.2.0"), "1.2.0");
        assert_eq!(
            encode_segment("release candidate/1"),
            "release%20candidate%2F1"
        );
        assert_eq!(
            registry::version_path(
                &encode_segment("acme"),
                &encode_segment("kit"),
                &encode_segment("v/2?x"),
            ),
            "/v1/packages/acme/kit/versions/v%2F2%3Fx"
        );
    }

    #[test]
    fn download_policy_and_limits() {
        let client = Client::new("https://registry.zpkg.tech/gateway").unwrap();
        assert_eq!(download_limit(0), MAX_ARTIFACT_BYTES);
        assert_eq!(download_limit(10), 10 + DOWNLOAD_SLACK);
        for raw in ["http://evil.example/artifact", "file:///etc/passwd"] {
            assert!(client.allowed_download_url(raw).is_err(), "accepted {raw}");
        }
        assert!(
            client
                .allowed_download_url("http://127.0.0.1:8080/a")
                .is_ok()
        );
        assert!(client.allowed_download_url("https://cdn.example/a").is_ok());
        for invalid in [
            "../escape",
            "%2e%2e/escape",
            "a%2Fb",
            "//evil.example/artifact",
            "/absolute/artifact",
            "\\authority-replacement",
        ] {
            assert!(
                client.resolve_download_url(invalid, "abc").is_err(),
                "accepted {invalid}"
            );
        }
        assert_eq!(
            client
                .resolve_download_url("artifacts/hash", "abc")
                .unwrap()
                .as_str(),
            "https://registry.zpkg.tech/gateway/artifacts/hash"
        );
    }
}

#[cfg(test)]
mod download_tests {
    use super::*;
    use std::net::TcpListener;
    use std::sync::mpsc;

    fn version(url: &str, sha256: &str, size: u64) -> VersionMetadata {
        VersionMetadata {
            org: "acme".into(),
            name: "kit".into(),
            version: "1.2.0".into(),
            sha256: sha256.into(),
            size,
            format: Default::default(),
            vcs_tag: "v1.2.0".into(),
            vcs_commit: None,
            download_url: url.into(),
            published_at: "2024-01-01T00:00:00Z".into(),
            yanked: false,
        }
    }

    fn spawn_server(body: Vec<u8>) -> (String, mpsc::Receiver<String>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let (sender, receiver) = mpsc::channel();
        std::thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                let mut buffer = [0_u8; 4096];
                let mut request = Vec::new();
                loop {
                    let read = stream.read(&mut buffer).unwrap_or(0);
                    if read == 0 {
                        break;
                    }
                    request.extend_from_slice(&buffer[..read]);
                    if request.windows(4).any(|window| window == b"\r\n\r\n") {
                        break;
                    }
                }
                let _ = sender.send(String::from_utf8_lossy(&request).into_owned());
                let header = format!(
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                let _ = stream.write_all(header.as_bytes());
                let _ = stream.write_all(&body);
                let _ = stream.flush();
            }
        });
        (format!("http://{addr}"), receiver)
    }

    #[test]
    fn download_omits_auth_resolves_relative_and_verifies_sha_atomically() {
        let body = b"artifact-bytes".to_vec();
        let sha = hex::encode(Sha256::digest(&body));
        let (base, receiver) = spawn_server(body.clone());
        let client = Client::new(format!("{base}/gateway"))
            .unwrap()
            .with_token("zpkg_t");
        let directory = std::env::temp_dir().join(format!(
            "zed-dl-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let destination = directory.join("artifact.tar.gz");
        fs::create_dir_all(&directory).unwrap();
        fs::write(&destination, b"old").unwrap();
        let metadata = version("artifact", &sha.to_uppercase(), body.len() as u64);
        client.download_artifact(&metadata, &destination).unwrap();
        let request = receiver.recv().unwrap();
        assert!(
            request.starts_with("GET /gateway/artifact "),
            "request={request}"
        );
        assert!(!request.to_lowercase().contains("authorization"));
        assert_eq!(fs::read(&destination).unwrap(), body);
        assert!(fs::read_dir(&directory).unwrap().all(|entry| {
            !entry
                .unwrap()
                .file_name()
                .to_string_lossy()
                .contains(".zed-")
        }));
        let _ = fs::remove_dir_all(&directory);
    }

    #[test]
    fn download_rejects_oversize_body() {
        let limit = download_limit(1);
        let body = vec![0_u8; (limit + 64) as usize];
        let (base, _receiver) = spawn_server(body);
        let client = Client::new(&base).unwrap();
        let directory = std::env::temp_dir().join(format!("zed-dl-big-{}", std::process::id()));
        let destination = directory.join("artifact.tar.gz");
        let metadata = version(&format!("{base}/artifact"), "deadbeef", 1);
        let error = client
            .download_artifact(&metadata, &destination)
            .unwrap_err();
        assert!(matches!(error, Error::ArtifactTooLarge { .. }));
        let _ = fs::remove_dir_all(&directory);
    }
}
