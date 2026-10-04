//! WebAssembly SDK for the zed-pkg registry (browsers and workers via
//! `wasm-pack`). Reuses DTOs and route helpers from `zed-interfaces` so wire
//! models and paths stay aligned with the contract.

use js_sys::{Array, Function, Reflect, Uint8Array};
use percent_encoding::{percent_decode_str, utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use serde::de::DeserializeOwned;
use sha2::{Digest, Sha256};
use wasm_bindgen::closure::Closure;
use wasm_bindgen::prelude::*;
use wasm_bindgen::JsCast;
use wasm_bindgen_futures::JsFuture;
use web_sys::{
    AbortController, Blob, BlobPropertyBag, FormData, Request, RequestInit, RequestRedirect,
    Response,
};
use zed_interfaces::registry::{
    self, ApiError, ClaimOrgResponse, PackageMetadata, PublishResponse, SearchResponse,
    VersionMetadata, YankResponse, DEFAULT_REGISTRY_URL,
};

const SEGMENT: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~');

const MAX_ARTIFACT_BYTES: u64 = 100 * 1024 * 1024;
const DOWNLOAD_SLACK: u64 = 1024 * 1024;
const MAX_JSON_RESPONSE_BYTES: u64 = 16 * 1024 * 1024;
const MAX_ERROR_BODY_BYTES: u64 = 16 * 1024;
const MAX_SEGMENT_BYTES: usize = 256;
const DEFAULT_TIMEOUT_MS: u32 = 30_000;

fn validate_segment<'a>(segment: &'a str, name: &str) -> Result<&'a str, String> {
    if segment.trim().is_empty() {
        return Err(format!("{name} must not be blank"));
    }
    if matches!(segment, "." | "..") {
        return Err(format!("{name} must not be a dot segment"));
    }
    if segment.len() > MAX_SEGMENT_BYTES {
        return Err(format!("{name} exceeds {MAX_SEGMENT_BYTES} UTF-8 bytes"));
    }
    if segment.chars().any(char::is_control) {
        return Err(format!("{name} must not contain control characters"));
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

fn checked_segment(segment: &str, name: &str) -> Result<String, String> {
    validate_segment(segment, name)?;
    Ok(encode_segment(segment))
}

fn validate_path_segments(raw_path: &str, name: &str) -> Result<(), String> {
    for (index, encoded) in raw_path
        .split('/')
        .filter(|value| !value.is_empty())
        .enumerate()
    {
        let decoded = percent_decode_str(encoded)
            .decode_utf8()
            .map_err(|_| format!("{name} contains invalid percent encoding"))?;
        validate_segment(&decoded, &format!("{name} segment {}", index + 1))?;
        if decoded.contains('/') || decoded.contains('\\') {
            return Err(format!(
                "{name} segments must not contain encoded separators"
            ));
        }
    }
    Ok(())
}

fn validate_raw_base_path(raw: &str) -> Result<(), String> {
    let Some(scheme_end) = raw.find("://") else {
        return Ok(());
    };
    let authority_start = scheme_end + 3;
    let Some(path_offset) = raw[authority_start..].find('/') else {
        return Ok(());
    };
    let path_start = authority_start + path_offset;
    let path_end = raw[path_start..]
        .find(['?', '#'])
        .map_or(raw.len(), |offset| path_start + offset);
    validate_path_segments(&raw[path_start..path_end], "registry URL path")
}

fn validate_relative_download_path(raw: &str) -> Result<(), String> {
    if raw.starts_with('/') || raw.starts_with('\\') {
        return Err(
            "relative download URL must not replace the registry authority or gateway path"
                .to_string(),
        );
    }
    let path_end = raw.find(['?', '#']).unwrap_or(raw.len());
    validate_path_segments(&raw[..path_end], "download_url")
}

fn normalize_base(raw: &str) -> Result<String, String> {
    let trimmed = raw.trim();
    validate_raw_base_path(trimmed)?;
    let mut url = url::Url::parse(trimmed)
        .map_err(|error| format!("registry URL must be absolute HTTP(S): {error}"))?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(
            "registry URL must be a credential-free absolute HTTP(S) URL without query or fragment"
                .to_string(),
        );
    }
    let path = url.path().trim_end_matches('/').to_string();
    url.set_path(&path);
    Ok(url.as_str().trim_end_matches('/').to_string())
}

fn download_limit(size: u64) -> u64 {
    if size > 0 {
        size.saturating_add(DOWNLOAD_SLACK).min(MAX_ARTIFACT_BYTES)
    } else {
        MAX_ARTIFACT_BYTES
    }
}

fn allowed_download_url(raw: &str, base: &str) -> Result<String, String> {
    let url = url::Url::parse(raw).map_err(|error| format!("bad download URL: {error}"))?;
    if !url.username().is_empty() || url.password().is_some() || url.fragment().is_some() {
        return Err("download URL contains credentials or fragment".to_string());
    }
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
        return Err(format!(
            "refusing artifact download over `{}`",
            url.scheme()
        ));
    }
    let loopback = matches!(url.host_str(), Some("localhost"))
        || url
            .host_str()
            .and_then(|host| {
                host.trim_matches(['[', ']'])
                    .parse::<std::net::IpAddr>()
                    .ok()
            })
            .is_some_and(|ip| ip.is_loopback());
    match url.scheme() {
        "https" => Ok(url.to_string()),
        "http" if loopback || base.starts_with("http://") => Ok(url.to_string()),
        scheme => Err(format!(
            "refusing artifact download over `{scheme}` from {raw}"
        )),
    }
}

fn resolve_download_url(raw: &str, base: &str, sha256: &str) -> Result<String, String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        let sha256 = checked_segment(sha256, "sha256")?;
        return Ok(format!("{base}{}", registry::artifact_path(&sha256)));
    }
    if let Ok(absolute) = url::Url::parse(trimmed) {
        return allowed_download_url(absolute.as_str(), base);
    }
    validate_relative_download_path(trimmed)?;
    let base_url = url::Url::parse(&(base.to_string() + "/"))
        .map_err(|error| format!("invalid registry base: {error}"))?;
    let resolved = base_url
        .join(trimmed)
        .map_err(|error| format!("invalid relative download URL: {error}"))?;
    allowed_download_url(resolved.as_str(), base)
}

fn verify_sha256(bytes: &[u8], expected: &str) -> Result<(), String> {
    let actual = hex::encode(Sha256::digest(bytes));
    if !actual.eq_ignore_ascii_case(expected) {
        return Err(format!(
            "artifact sha256 mismatch: expected {expected}, got {actual}"
        ));
    }
    Ok(())
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

fn js_error(message: impl AsRef<str>) -> JsValue {
    js_sys::Error::new(message.as_ref()).into()
}

fn api_js_error(status: u16, code: &str, registry_message: &str) -> JsValue {
    let value: JsValue = js_sys::Error::new(&format!("registry error {status}: {code}")).into();
    let _ = Reflect::set(
        &value,
        &JsValue::from_str("status"),
        &JsValue::from_f64(f64::from(status)),
    );
    let _ = Reflect::set(&value, &JsValue::from_str("code"), &JsValue::from_str(code));
    let _ = Reflect::set(
        &value,
        &JsValue::from_str("registryMessage"),
        &JsValue::from_str(registry_message),
    );
    value
}

fn global_fetch(request: &Request) -> Result<js_sys::Promise, JsValue> {
    let global = js_sys::global();
    let fetch = Reflect::get(&global, &JsValue::from_str("fetch"))?;
    let fetch: Function = fetch
        .dyn_into()
        .map_err(|_| js_error("global fetch is unavailable in this runtime"))?;
    fetch
        .call1(&global, request)?
        .dyn_into()
        .map_err(JsValue::from)
}

fn schedule_abort(controller: AbortController, timeout_ms: u32) -> Result<JsValue, JsValue> {
    let global = js_sys::global();
    let set_timeout: Function = Reflect::get(&global, &JsValue::from_str("setTimeout"))?
        .dyn_into()
        .map_err(|_| js_error("global setTimeout is unavailable in this runtime"))?;
    let callback = Closure::once_into_js(move || controller.abort());
    set_timeout.call2(
        &global,
        &callback,
        &JsValue::from_f64(f64::from(timeout_ms)),
    )
}

fn clear_timeout(timer: &JsValue) {
    let global = js_sys::global();
    if let Ok(value) = Reflect::get(&global, &JsValue::from_str("clearTimeout")) {
        if let Ok(function) = value.dyn_into::<Function>() {
            let _ = function.call1(&global, timer);
        }
    }
}

async fn response_bytes(
    response: &Response,
    limit: u64,
    overflow_code: &str,
    description: &str,
) -> Result<Vec<u8>, JsValue> {
    if let Ok(Some(declared)) = response.headers().get("content-length") {
        if declared.parse::<u64>().is_ok_and(|length| length > limit) {
            return Err(api_js_error(
                0,
                overflow_code,
                &format!("{description} exceeded {limit} bytes; refusing"),
            ));
        }
    }
    let buffer = JsFuture::from(response.array_buffer()?).await?;
    let bytes = Uint8Array::new(&buffer).to_vec();
    if bytes.len() as u64 > limit {
        return Err(api_js_error(
            0,
            overflow_code,
            &format!("{description} exceeded {limit} bytes; refusing"),
        ));
    }
    Ok(bytes)
}

struct TimedResponse {
    response: Response,
    timer: JsValue,
}

#[wasm_bindgen]
pub struct ZedClient {
    base: String,
    token: Option<String>,
    token_error: Option<String>,
    timeout_ms: u32,
    configuration_error: Option<String>,
}

#[wasm_bindgen]
impl ZedClient {
    /// Invalid constructor input is retained and every operation fails before
    /// transport, preserving the existing non-throwing constructor shape.
    #[wasm_bindgen(constructor)]
    pub fn new(base_url: Option<String>) -> ZedClient {
        let raw = base_url.unwrap_or_else(|| DEFAULT_REGISTRY_URL.to_string());
        let (base, configuration_error) = match normalize_base(&raw) {
            Ok(base) => (base, None),
            Err(error) => (DEFAULT_REGISTRY_URL.to_string(), Some(error)),
        };
        ZedClient {
            base,
            token: None,
            token_error: None,
            timeout_ms: DEFAULT_TIMEOUT_MS,
            configuration_error,
        }
    }

    #[wasm_bindgen(js_name = withToken)]
    pub fn with_token(&mut self, token: String) {
        let trimmed = token.trim();
        if trimmed.is_empty() {
            self.token = None;
            self.token_error = None;
        } else if trimmed.chars().any(char::is_control) {
            self.token = None;
            self.token_error = Some("token must not contain control characters".to_string());
        } else {
            self.token = Some(trimmed.to_string());
            self.token_error = None;
        }
    }

    #[wasm_bindgen(js_name = withTimeoutMs)]
    pub fn with_timeout_ms(&mut self, timeout_ms: u32) -> Result<(), JsValue> {
        if timeout_ms == 0 {
            return Err(api_js_error(
                0,
                "invalid_timeout",
                "timeout_ms must be positive",
            ));
        }
        self.timeout_ms = timeout_ms;
        Ok(())
    }

    fn ensure_configured(&self) -> Result<(), JsValue> {
        if let Some(error) = &self.configuration_error {
            return Err(api_js_error(0, "invalid_configuration", error));
        }
        Ok(())
    }

    fn require_token(&self) -> Result<&str, JsValue> {
        if let Some(error) = &self.token_error {
            return Err(api_js_error(0, "invalid_token", error));
        }
        self.token.as_deref().ok_or_else(|| {
            api_js_error(
                0,
                "missing_token",
                "authenticated registry operation requires a nonblank bearer token",
            )
        })
    }

    fn url(&self, path: &str) -> String {
        format!("{}{path}", self.base)
    }

    async fn send(&self, request: Request, authorized: bool) -> Result<TimedResponse, JsValue> {
        self.ensure_configured()?;
        if authorized {
            request.headers().set(
                "authorization",
                &format!("Bearer {}", self.require_token()?),
            )?;
        }
        request.headers().set("accept", "application/json")?;

        let controller = AbortController::new()?;
        let init = RequestInit::new();
        init.set_signal(Some(&controller.signal()));
        init.set_redirect(RequestRedirect::Error);
        let timed_request = Request::new_with_request_and_init(&request, &init)?;
        let timer = schedule_abort(controller, self.timeout_ms)?;
        let promise = match global_fetch(&timed_request) {
            Ok(promise) => promise,
            Err(error) => {
                clear_timeout(&timer);
                return Err(error);
            }
        };
        let response = match JsFuture::from(promise).await {
            Ok(response) => match response.dyn_into::<Response>() {
                Ok(response) => response,
                Err(error) => {
                    clear_timeout(&timer);
                    return Err(error);
                }
            },
            Err(error) => {
                clear_timeout(&timer);
                return Err(error);
            }
        };
        Ok(TimedResponse { response, timer })
    }

    async fn check(&self, response: Response) -> Result<Response, JsValue> {
        if response.ok() {
            return Ok(response);
        }
        let status = response.status();
        let bytes = match response_bytes(
            &response,
            MAX_ERROR_BODY_BYTES,
            "error_body_too_large",
            "registry error body",
        )
        .await
        {
            Ok(bytes) => bytes,
            Err(_) => {
                return Err(api_js_error(
                    status,
                    &format!("http_{status}"),
                    "registry error body exceeded the client limit",
                ));
            }
        };
        let text = String::from_utf8_lossy(&bytes).into_owned();
        let (code, message) = match serde_json::from_slice::<ApiError>(&bytes) {
            Ok(error) if !error.code.trim().is_empty() => {
                (error.code.trim().to_string(), error.message)
            }
            _ => (format!("http_{status}"), text),
        };
        Err(api_js_error(status, &code, &message))
    }

    async fn json<T: DeserializeOwned + serde::Serialize>(
        &self,
        request: Request,
        authorized: bool,
    ) -> Result<JsValue, JsValue> {
        let TimedResponse { response, timer } = self.send(request, authorized).await?;
        let result = async {
            let response = self.check(response).await?;
            let bytes = response_bytes(
                &response,
                MAX_JSON_RESPONSE_BYTES,
                "response_too_large",
                "registry JSON response",
            )
            .await?;
            let value: T = serde_json::from_slice(&bytes)
                .map_err(|error| api_js_error(0, "invalid_response", &error.to_string()))?;
            serde_wasm_bindgen::to_value(&value).map_err(JsValue::from)
        }
        .await;
        clear_timeout(&timer);
        result
    }

    fn get_request(&self, path: &str) -> Result<Request, JsValue> {
        let init = RequestInit::new();
        init.set_method("GET");
        init.set_redirect(RequestRedirect::Error);
        Request::new_with_str_and_init(&self.url(path), &init).map_err(JsValue::from)
    }

    fn json_request(&self, method: &str, path: &str, body: &str) -> Result<Request, JsValue> {
        let init = RequestInit::new();
        init.set_method(method);
        init.set_redirect(RequestRedirect::Error);
        init.set_body(&JsValue::from_str(body));
        let request = Request::new_with_str_and_init(&self.url(path), &init)?;
        request.headers().set("content-type", "application/json")?;
        Ok(request)
    }

    #[wasm_bindgen(js_name = getPackage)]
    pub async fn get_package(&self, org: String, name: String) -> Result<JsValue, JsValue> {
        let path = registry::package_path(
            &checked_segment(&org, "org").map_err(js_error)?,
            &checked_segment(&name, "name").map_err(js_error)?,
        );
        self.json::<PackageMetadata>(self.get_request(&path)?, false)
            .await
    }

    #[wasm_bindgen(js_name = getVersion)]
    pub async fn get_version(
        &self,
        org: String,
        name: String,
        version: String,
    ) -> Result<JsValue, JsValue> {
        let path = registry::version_path(
            &checked_segment(&org, "org").map_err(js_error)?,
            &checked_segment(&name, "name").map_err(js_error)?,
            &checked_segment(&version, "version").map_err(js_error)?,
        );
        self.json::<VersionMetadata>(self.get_request(&path)?, false)
            .await
    }

    pub async fn search(&self, query: String) -> Result<JsValue, JsValue> {
        let path = format!(
            "{}?q={}",
            registry::search_path(),
            utf8_percent_encode(&query, SEGMENT)
        );
        self.json::<SearchResponse>(self.get_request(&path)?, false)
            .await
    }

    #[wasm_bindgen(js_name = claimOrg)]
    pub async fn claim_org(&self, slug: String) -> Result<JsValue, JsValue> {
        validate_segment(&slug, "slug").map_err(js_error)?;
        self.require_token()?;
        let body = serde_json::json!({ "slug": slug }).to_string();
        self.json::<ClaimOrgResponse>(
            self.json_request("POST", &registry::orgs_path(), &body)?,
            true,
        )
        .await
    }

    #[wasm_bindgen(js_name = setYanked)]
    pub async fn set_yanked(
        &self,
        org: String,
        name: String,
        version: String,
        yanked: bool,
    ) -> Result<JsValue, JsValue> {
        self.require_token()?;
        let path = registry::yank_path(
            &checked_segment(&org, "org").map_err(js_error)?,
            &checked_segment(&name, "name").map_err(js_error)?,
            &checked_segment(&version, "version").map_err(js_error)?,
        );
        let body = serde_json::json!({ "yanked": yanked }).to_string();
        self.json::<YankResponse>(self.json_request("POST", &path, &body)?, true)
            .await
    }

    pub async fn yank(
        &self,
        org: String,
        name: String,
        version: String,
        yanked: bool,
    ) -> Result<JsValue, JsValue> {
        self.set_yanked(org, name, version, yanked).await
    }

    pub async fn restore(
        &self,
        org: String,
        name: String,
        version: String,
    ) -> Result<JsValue, JsValue> {
        self.set_yanked(org, name, version, false).await
    }

    #[wasm_bindgen(js_name = downloadArtifact)]
    pub async fn download_artifact(&self, version: JsValue) -> Result<Uint8Array, JsValue> {
        self.ensure_configured()?;
        let version: VersionMetadata =
            serde_wasm_bindgen::from_value(version).map_err(JsValue::from)?;
        let url = resolve_download_url(&version.download_url, &self.base, &version.sha256)
            .map_err(js_error)?;
        let init = RequestInit::new();
        init.set_method("GET");
        init.set_redirect(RequestRedirect::Error);
        let request = Request::new_with_str_and_init(&url, &init)?;
        let TimedResponse { response, timer } = self.send(request, false).await?;
        let result = async {
            let response = self.check(response).await?;
            let bytes = response_bytes(
                &response,
                download_limit(version.size),
                "artifact_too_large",
                "artifact",
            )
            .await?;
            verify_sha256(&bytes, &version.sha256).map_err(js_error)?;
            Ok(Uint8Array::from(bytes.as_slice()))
        }
        .await;
        clear_timeout(&timer);
        result
    }

    pub async fn publish(
        &self,
        meta_json: String,
        artifact: Uint8Array,
    ) -> Result<JsValue, JsValue> {
        self.ensure_configured()?;
        self.require_token()?;
        if u64::from(artifact.length()) > MAX_ARTIFACT_BYTES {
            return Err(api_js_error(
                0,
                "artifact_too_large",
                &format!("artifact exceeded {MAX_ARTIFACT_BYTES} bytes; refusing"),
            ));
        }
        let meta: zed_interfaces::registry::PublishMeta = serde_json::from_str(&meta_json)
            .map_err(|error| api_js_error(0, "invalid_publish_meta", &error.to_string()))?;
        let package = &meta.manifest.package;
        let org = checked_segment(&package.org, "meta.manifest.package.org").map_err(js_error)?;
        let name =
            checked_segment(&package.name, "meta.manifest.package.name").map_err(js_error)?;
        let version =
            checked_segment(&package.version, "meta.manifest.package.version").map_err(js_error)?;
        let path = registry::version_path(&org, &name, &version);
        let filename = safe_filename(&format!(
            "{}-{}-{}.tar.gz",
            package.org, package.name, package.version
        ));

        let parts = Array::new();
        parts.push(&Uint8Array::from(artifact.to_vec().as_slice()));
        let options = BlobPropertyBag::new();
        options.set_type("application/gzip");
        let blob = Blob::new_with_u8_array_sequence_and_options(&parts, &options)?;

        let form = FormData::new()?;
        form.append_with_str(registry::PUBLISH_META_FIELD, &meta_json)?;
        form.append_with_blob_and_filename(registry::PUBLISH_ARTIFACT_FIELD, &blob, &filename)?;

        let init = RequestInit::new();
        init.set_method("PUT");
        init.set_redirect(RequestRedirect::Error);
        init.set_body(&form);
        let request = Request::new_with_str_and_init(&self.url(&path), &init)?;
        self.json::<PublishResponse>(request, true).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha_verification_is_case_insensitive() {
        let bytes = b"zed";
        let good = hex::encode(Sha256::digest(bytes));
        assert!(verify_sha256(bytes, &good.to_uppercase()).is_ok());
        assert!(verify_sha256(bytes, "00").is_err());
    }

    #[test]
    fn registry_base_and_segments_fail_closed() {
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
            assert!(normalize_base(invalid).is_err(), "accepted {invalid}");
        }
        for invalid in ["", "   ", ".", "..", "line\nbreak"] {
            assert!(validate_segment(invalid, "segment").is_err());
        }
        assert_eq!(encode_segment(".."), "%2E%2E");
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
    fn download_limit_caps_at_ceiling() {
        assert_eq!(download_limit(0), MAX_ARTIFACT_BYTES);
        assert_eq!(download_limit(10), 10 + DOWNLOAD_SLACK);
        assert_eq!(download_limit(MAX_ARTIFACT_BYTES), MAX_ARTIFACT_BYTES);
    }

    #[test]
    fn download_urls_reject_credentials_fragments_and_insecure_schemes() {
        let base = "https://registry.zpkg.tech/gateway";
        for raw in [
            "http://evil.example/artifact",
            "file:///etc/passwd",
            "https://user:secret@cdn.example/a",
            "https://cdn.example/a#fragment",
        ] {
            assert!(allowed_download_url(raw, base).is_err(), "accepted {raw}");
        }
        assert!(allowed_download_url("http://127.0.0.1:8080/a", base).is_ok());
        assert!(allowed_download_url("http://localhost/a", base).is_ok());
        assert!(allowed_download_url("http://[::1]:9/a", base).is_ok());
        assert!(allowed_download_url("https://cdn.example/a", base).is_ok());
        assert_eq!(
            resolve_download_url("artifacts/hash", base, "abc").unwrap(),
            "https://registry.zpkg.tech/gateway/artifacts/hash"
        );
        for invalid in [
            "../escape",
            "%2e%2e/escape",
            "a%2Fb",
            "//evil.example/artifact",
            "/absolute/artifact",
            "\\authority-replacement",
        ] {
            assert!(
                resolve_download_url(invalid, base, "abc").is_err(),
                "accepted {invalid}"
            );
        }
    }

    #[test]
    fn missing_token_and_invalid_constructor_state_fail_before_transport() {
        let client = ZedClient::new(Some("relative/path".to_string()));
        assert!(client.configuration_error.is_some());

        let mut client = ZedClient::new(Some("https://registry.test".to_string()));
        assert!(client.token.is_none());
        client.with_token("   ".to_string());
        assert!(client.token.is_none());
        client.with_token("token\r\nheader".to_string());
        assert!(client.token.is_none());
        assert!(client.token_error.is_some());
    }
}
