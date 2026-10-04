//// Gleam SDK for the zed-pkg registry. Types mirror the JSON Schemas in
//// `zed-interfaces/schemas/`; the transport is injectable so tests run
//// without a network.

import gleam/bit_array
import gleam/crypto
import gleam/dynamic/decode
import gleam/http
import gleam/http/request.{type Request}
import gleam/http/response.{type Response}
import gleam/httpc
import gleam/int
import gleam/json
import gleam/option.{type Option, None, Some}
import gleam/result
import gleam/string
import gleam/uri
import zed_pkg_client/model.{
  type ClaimOrgResponse, type PackageMetadata, type PublishResponse,
  type SearchResponse, type VersionMetadata, type YankResponse,
}

pub const default_registry_url = "https://registry.zpkg.tech"

/// Bounds every request (connect + read), in milliseconds.
pub const default_timeout_ms = 30_000

/// Successful registry JSON documents are never accepted without a ceiling.
pub const max_json_response_bytes = 16_777_216

/// Remote error text is retained only below this independent ceiling.
pub const max_error_body_bytes = 16_384

/// Maximum UTF-8 size of one opaque route segment.
pub const max_path_segment_bytes = 256

/// Hard ceiling on artifact downloads, matching the server's
/// `MAX_ARTIFACT_BYTES` default (100 MiB); plus the slack added to a
/// version's declared size.
pub const max_artifact_bytes = 104_857_600

const download_slack = 1_048_576

/// The declared size (when sane) plus slack, capped by the ceiling.
pub fn download_limit(size: Int) -> Int {
  case size > 0 {
    True -> int.min(size + download_slack, max_artifact_bytes)
    False -> max_artifact_bytes
  }
}

pub type ClientError {
  /// The registry answered with a structured or bounded fallback error body.
  ApiError(status: Int, code: String, message: String)
  TransportError(error: httpc.HttpError)
  InvalidConfiguration(message: String)
  InvalidInput(message: String)
  MissingToken
  InvalidResponse(message: String)
  ResponseTooLarge(limit: Int)
  Sha256Mismatch(expected: String, actual: String)
  ArtifactTooLarge(limit: Int)
  InsecureDownloadUrl(message: String)
}

/// Injectable transport used for deterministic tests and alternate runtimes.
pub type Transport =
  fn(Request(BitArray), Int) -> Result(Response(BitArray), httpc.HttpError)

pub opaque type Client {
  Client(
    base: String,
    token: Option(String),
    timeout_ms: Int,
    transport: Transport,
    configuration_error: Option(ClientError),
  )
}

/// Construct a client against `base`. The constructor keeps its historical
/// non-throwing shape; invalid configuration is retained and every operation
/// fails before the injected transport can run.
pub fn new(base: String) -> Client {
  case normalize_base(base) {
    Ok(base) ->
      Client(
        base: base,
        token: None,
        timeout_ms: default_timeout_ms,
        transport: httpc_transport,
        configuration_error: None,
      )
    Error(error) ->
      Client(
        base: default_registry_url,
        token: None,
        timeout_ms: default_timeout_ms,
        transport: httpc_transport,
        configuration_error: Some(error),
      )
  }
}

/// Attach a bearer token used for authenticated calls (claim_org, yank,
/// publish). Never sent on artifact downloads. Blank tokens are treated as
/// absent so mutations fail closed before transport.
pub fn with_token(client: Client, token: String) -> Client {
  let token = string.trim(token)
  case token {
    "" -> Client(..client, token: None)
    _ -> Client(..client, token: Some(token))
  }
}

pub fn with_timeout(client: Client, timeout_ms: Int) -> Client {
  case timeout_ms > 0 {
    True -> Client(..client, timeout_ms: timeout_ms)
    False ->
      Client(
        ..client,
        configuration_error: Some(InvalidConfiguration(
          message: "timeout_ms must be positive",
        )),
      )
  }
}

pub fn with_transport(client: Client, transport: Transport) -> Client {
  Client(..client, transport: transport)
}

fn ensure_configured(client: Client) -> Result(Nil, ClientError) {
  case client.configuration_error {
    None -> Ok(Nil)
    Some(error) -> Error(error)
  }
}

fn normalize_base(base: String) -> Result(String, ClientError) {
  let trimmed = string.trim(base)
  use parsed <- result.try(
    uri.parse(trimmed)
    |> result.map_error(fn(_) {
      InvalidConfiguration(
        message: "registry URL must be an absolute HTTP(S) URL",
      )
    }),
  )
  use _ <- result.try(validate_path(parsed.path, "registry URL path"))
  case
    #(
      parsed.scheme,
      parsed.userinfo,
      parsed.host,
      parsed.query,
      parsed.fragment,
    )
  {
    #(Some("http"), None, Some(host), None, None)
      | #(Some("https"), None, Some(host), None, None)
      if host != ""
    -> Ok(trim_trailing_slashes(trimmed))
    _ ->
      Error(InvalidConfiguration(
        message: "registry URL must be credential-free absolute HTTP(S) without query or fragment",
      ))
  }
}

fn trim_trailing_slashes(value: String) -> String {
  case string.ends_with(value, "/") {
    True -> trim_trailing_slashes(string.drop_end(value, 1))
    False -> value
  }
}

fn validate_path(path: String, name: String) -> Result(Nil, ClientError) {
  validate_path_segments(string.split(path, "/"), name, 1)
}

fn validate_path_segments(
  segments: List(String),
  name: String,
  index: Int,
) -> Result(Nil, ClientError) {
  case segments {
    [] -> Ok(Nil)
    ["", ..rest] -> validate_path_segments(rest, name, index + 1)
    [encoded, ..rest] -> {
      use decoded <- result.try(
        uri.percent_decode(encoded)
        |> result.map_error(fn(_) {
          InvalidInput(message: name <> " contains invalid percent encoding")
        }),
      )
      case string.contains(decoded, "/") || string.contains(decoded, "\\") {
        True ->
          Error(InvalidInput(
            message: name <> " segments must not contain encoded separators",
          ))
        False -> {
          use _ <- result.try(validate_segment(
            decoded,
            name <> " segment " <> int.to_string(index),
          ))
          validate_path_segments(rest, name, index + 1)
        }
      }
    }
  }
}

fn validate_segment(
  segment: String,
  name: String,
) -> Result(String, ClientError) {
  let size =
    segment
    |> bit_array.from_string
    |> bit_array.byte_size
  case
    string.trim(segment) == ""
    || segment == "."
    || segment == ".."
    || size > max_path_segment_bytes
    || string.contains(segment, "\n")
    || string.contains(segment, "\r")
    || string.contains(segment, "\u{0000}")
  {
    True ->
      Error(InvalidInput(
        message: name
        <> " must be nonblank, non-dot, bounded, and free of control characters",
      ))
    False -> Ok(segment)
  }
}

fn httpc_transport(
  req: Request(BitArray),
  timeout_ms: Int,
) -> Result(Response(BitArray), httpc.HttpError) {
  httpc.configure()
  |> httpc.timeout(timeout_ms)
  // Refusing redirects prevents a mutating request body and bearer token from
  // being replayed to a different target.
  |> httpc.follow_redirects(False)
  |> httpc.dispatch_bits(req)
}

/// Percent-encode one path segment (everything reserved, including `/`). Dot
/// segments are encoded explicitly because RFC URL normalizers may otherwise
/// treat them as traversal even when passed as supposedly opaque coordinates.
pub fn encode_segment(segment: String) -> String {
  case segment {
    "." -> "%2E"
    ".." -> "%2E%2E"
    _ -> uri.percent_encode(segment)
  }
}

pub fn package_path(org: String, name: String) -> String {
  "/v1/packages/" <> encode_segment(org) <> "/" <> encode_segment(name)
}

pub fn version_path(org: String, name: String, version: String) -> String {
  package_path(org, name) <> "/versions/" <> encode_segment(version)
}

pub fn artifact_path(sha256: String) -> String {
  "/v1/artifacts/" <> encode_segment(sha256)
}

pub fn yank_path(org: String, name: String, version: String) -> String {
  version_path(org, name, version) <> "/yank"
}

fn checked_package_path(
  org: String,
  name: String,
) -> Result(String, ClientError) {
  use org <- result.try(validate_segment(org, "org"))
  use name <- result.try(validate_segment(name, "name"))
  Ok(package_path(org, name))
}

fn checked_version_path(
  org: String,
  name: String,
  version: String,
) -> Result(String, ClientError) {
  use org <- result.try(validate_segment(org, "org"))
  use name <- result.try(validate_segment(name, "name"))
  use version <- result.try(validate_segment(version, "version"))
  Ok(version_path(org, name, version))
}

fn checked_yank_path(
  org: String,
  name: String,
  version: String,
) -> Result(String, ClientError) {
  use path <- result.try(checked_version_path(org, name, version))
  Ok(path <> "/yank")
}

/// Enforce the download-url scheme policy: https is always allowed; http only
/// for loopback hosts or when the registry base is itself http. Query strings
/// remain allowed for presigned URLs; userinfo and fragments are rejected.
pub fn allowed_download_url(
  raw: String,
  base: String,
) -> Result(String, ClientError) {
  case uri.parse(raw) {
    Error(_) -> Error(InsecureDownloadUrl(message: "bad download url"))
    Ok(parsed) ->
      case #(parsed.userinfo, parsed.host, parsed.fragment) {
        #(None, Some(host), None) if host != "" -> {
          let loopback = case host {
            "localhost" | "127.0.0.1" | "[::1]" | "::1" -> True
            _ -> string.starts_with(host, "127.")
          }
          case parsed.scheme {
            Some("https") -> Ok(raw)
            Some("http") ->
              case loopback || string.starts_with(base, "http://") {
                True -> Ok(raw)
                False ->
                  Error(InsecureDownloadUrl(
                    message: "refusing artifact download over `http`",
                  ))
              }
            _ ->
              Error(InsecureDownloadUrl(
                message: "refusing artifact download over an unsupported scheme",
              ))
          }
        }
        _ ->
          Error(InsecureDownloadUrl(
            message: "download URL contains credentials, fragment, or no host",
          ))
      }
  }
}

fn resolve_download_url(
  raw: String,
  base: String,
  sha256: String,
) -> Result(String, ClientError) {
  let trimmed = string.trim(raw)
  case trimmed {
    "" -> {
      use sha256 <- result.try(validate_segment(sha256, "sha256"))
      Ok(base <> artifact_path(sha256))
    }
    _ -> {
      use parsed <- result.try(
        uri.parse(trimmed)
        |> result.map_error(fn(_) {
          InsecureDownloadUrl(message: "bad download url")
        }),
      )
      case parsed.scheme {
        Some(_) -> allowed_download_url(trimmed, base)
        None ->
          case #(parsed.host, parsed.userinfo, parsed.fragment) {
            #(None, None, None) -> {
              use _ <- result.try(validate_path(parsed.path, "download_url"))
              case string.starts_with(trimmed, "/") {
                True ->
                  Error(InsecureDownloadUrl(
                    message: "absolute-path download URLs are not allowed",
                  ))
                False -> allowed_download_url(base <> "/" <> trimmed, base)
              }
            }
            _ ->
              Error(InsecureDownloadUrl(
                message: "relative download URL contains an authority or fragment",
              ))
          }
      }
    }
  }
}

/// Lowercase hex sha256 of `bytes`.
pub fn sha256_hex(bytes: BitArray) -> String {
  crypto.hash(crypto.Sha256, bytes)
  |> bit_array.base16_encode
  |> string.lowercase
}

pub fn verify_sha256(
  bytes: BitArray,
  expected: String,
) -> Result(Nil, ClientError) {
  let actual = sha256_hex(bytes)
  case actual == string.lowercase(expected) {
    True -> Ok(Nil)
    False -> Error(Sha256Mismatch(expected: expected, actual: actual))
  }
}

fn base_request(
  client: Client,
  path: String,
) -> Result(Request(BitArray), ClientError) {
  use _ <- result.try(ensure_configured(client))
  case request.to(client.base <> path) {
    Ok(req) ->
      Ok(
        req
        |> request.set_header("accept", "application/json")
        |> request.set_body(<<>>),
      )
    Error(_) -> Error(InvalidResponse(message: "invalid registry url"))
  }
}

fn authorize(
  client: Client,
  req: Request(BitArray),
) -> Result(Request(BitArray), ClientError) {
  case client.token {
    Some(token) ->
      Ok(request.set_header(req, "authorization", "Bearer " <> token))
    None -> Error(MissingToken)
  }
}

fn send(
  client: Client,
  req: Request(BitArray),
) -> Result(Response(BitArray), ClientError) {
  use _ <- result.try(ensure_configured(client))
  client.transport(req, client.timeout_ms)
  |> result.map_error(fn(error) { TransportError(error: error) })
}

/// Map a response to either a bounded successful body or a typed error.
fn check(
  response: Response(BitArray),
  success_limit: Int,
) -> Result(BitArray, ClientError) {
  let size = bit_array.byte_size(response.body)
  case response.status >= 200 && response.status < 300 {
    True ->
      case size > success_limit {
        True -> Error(ResponseTooLarge(limit: success_limit))
        False -> Ok(response.body)
      }
    False -> {
      let fallback_code = "http_" <> int.to_string(response.status)
      case size > max_error_body_bytes {
        True ->
          Error(ApiError(
            status: response.status,
            code: fallback_code,
            message: "registry error body exceeded the client limit",
          ))
        False -> {
          let text =
            bit_array.to_string(response.body)
            |> result.unwrap("")
          let decoder = {
            use code <- decode.field("code", decode.string)
            use message <- decode.field("message", decode.string)
            decode.success(#(code, message))
          }
          case json.parse(from: text, using: decoder) {
            Ok(#(code, message)) -> {
              let code = case string.trim(code) {
                "" -> fallback_code
                code -> code
              }
              Error(ApiError(
                status: response.status,
                code: code,
                message: message,
              ))
            }
            Error(_) ->
              Error(ApiError(
                status: response.status,
                code: fallback_code,
                message: text,
              ))
          }
        }
      }
    }
  }
}

fn decode_json(
  bytes: BitArray,
  decoder: decode.Decoder(t),
) -> Result(t, ClientError) {
  case bit_array.to_string(bytes) {
    Error(_) -> Error(InvalidResponse(message: "response body is not utf-8"))
    Ok(text) ->
      json.parse(from: text, using: decoder)
      |> result.map_error(fn(error) {
        InvalidResponse(
          message: "invalid registry response: " <> string.inspect(error),
        )
      })
  }
}

fn request_json(
  client: Client,
  method: http.Method,
  path: String,
  body: Option(json.Json),
  authorized: Bool,
  decoder: decode.Decoder(t),
) -> Result(t, ClientError) {
  use req <- result.try(base_request(client, path))
  let req = request.set_method(req, method)
  use req <- result.try(case authorized {
    True -> authorize(client, req)
    False -> Ok(req)
  })
  let req = case body {
    Some(payload) ->
      req
      |> request.set_header("content-type", "application/json")
      |> request.set_body(bit_array.from_string(json.to_string(payload)))
    None -> req
  }
  use response <- result.try(send(client, req))
  use bytes <- result.try(check(response, max_json_response_bytes))
  decode_json(bytes, decoder)
}

/// `GET /v1/packages/{org}/{name}` — package metadata + version list.
pub fn get_package(
  client: Client,
  org: String,
  name: String,
) -> Result(PackageMetadata, ClientError) {
  use path <- result.try(checked_package_path(org, name))
  request_json(
    client,
    http.Get,
    path,
    None,
    False,
    model.package_metadata_decoder(),
  )
}

/// `GET /v1/packages/{org}/{name}/versions/{version}`.
pub fn get_version(
  client: Client,
  org: String,
  name: String,
  version: String,
) -> Result(VersionMetadata, ClientError) {
  use path <- result.try(checked_version_path(org, name, version))
  request_json(
    client,
    http.Get,
    path,
    None,
    False,
    model.version_metadata_decoder(),
  )
}

/// `GET /v1/search?q=`.
pub fn search(
  client: Client,
  query: String,
) -> Result(SearchResponse, ClientError) {
  request_json(
    client,
    http.Get,
    "/v1/search?q=" <> uri.percent_encode(query),
    None,
    False,
    model.search_response_decoder(),
  )
}

/// `POST /v1/orgs` (bearer token).
pub fn claim_org(
  client: Client,
  slug: String,
) -> Result(ClaimOrgResponse, ClientError) {
  use slug <- result.try(validate_segment(slug, "slug"))
  request_json(
    client,
    http.Post,
    "/v1/orgs",
    Some(json.object([#("slug", json.string(slug))])),
    True,
    model.claim_org_response_decoder(),
  )
}

pub fn set_yanked(
  client: Client,
  org: String,
  name: String,
  version: String,
  yanked: Bool,
) -> Result(YankResponse, ClientError) {
  use path <- result.try(checked_yank_path(org, name, version))
  request_json(
    client,
    http.Post,
    path,
    Some(json.object([#("yanked", json.bool(yanked))])),
    True,
    model.yank_response_decoder(),
  )
}

/// Compatibility form retained for existing callers.
pub fn yank(
  client: Client,
  org: String,
  name: String,
  version: String,
  yanked: Bool,
) -> Result(YankResponse, ClientError) {
  set_yanked(client, org, name, version, yanked)
}

pub fn restore(
  client: Client,
  org: String,
  name: String,
  version: String,
) -> Result(YankResponse, ClientError) {
  set_yanked(client, org, name, version, False)
}

/// Download an artifact, verify its sha256, and return the bytes.
pub fn download_artifact(
  client: Client,
  version: VersionMetadata,
) -> Result(BitArray, ClientError) {
  use _ <- result.try(ensure_configured(client))
  use url <- result.try(resolve_download_url(
    version.download_url,
    client.base,
    version.sha256,
  ))
  use req <- result.try(case request.to(url) {
    Ok(req) -> Ok(request.set_body(req, <<>>))
    Error(_) -> Error(InvalidResponse(message: "invalid download url"))
  })
  // Deliberately no auth header: download_url may point at a third-party host
  // (e.g. a presigned S3/R2 url) and the token must not leak there.
  use response <- result.try(send(client, req))
  let limit = download_limit(version.size)
  use bytes <- result.try(check(response, limit))
  use _ <- result.try(verify_sha256(bytes, version.sha256))
  Ok(bytes)
}

/// Build the `multipart/form-data` body for a publish: a `meta` field carrying
/// the PublishMeta JSON and an artifact file part. The filename is fixed so
/// caller-controlled coordinates cannot inject multipart headers.
pub fn multipart_body(
  boundary: String,
  meta_json: String,
  filename: String,
  artifact: BitArray,
) -> BitArray {
  bit_array.concat([
    bit_array.from_string(
      "--"
      <> boundary
      <> "\r\ncontent-disposition: form-data; name=\"meta\"\r\n\r\n"
      <> meta_json
      <> "\r\n--"
      <> boundary
      <> "\r\ncontent-disposition: form-data; name=\"artifact\"; filename=\""
      <> filename
      <> "\"\r\ncontent-type: application/octet-stream\r\n\r\n",
    ),
    artifact,
    bit_array.from_string("\r\n--" <> boundary <> "--\r\n"),
  ])
}

fn publish_coordinate_decoder() -> decode.Decoder(#(String, String, String)) {
  use org <- decode.then(decode.at(
    ["manifest", "package", "org"],
    decode.string,
  ))
  use name <- decode.then(decode.at(
    ["manifest", "package", "name"],
    decode.string,
  ))
  use version <- decode.then(decode.at(
    ["manifest", "package", "version"],
    decode.string,
  ))
  decode.success(#(org, name, version))
}

/// Publish: multipart `meta` (PublishMeta JSON) + `artifact` bytes via
/// `PUT /v1/packages/{org}/{name}/versions/{version}`. Requires a bearer
/// token. `org`, `name` and `version` must match `meta.manifest.package`.
pub fn publish(
  client: Client,
  org: String,
  name: String,
  version: String,
  meta_json: String,
  artifact: BitArray,
) -> Result(PublishResponse, ClientError) {
  use _ <- result.try(ensure_configured(client))
  use _ <- result.try(case client.token {
    Some(_) -> Ok(Nil)
    None -> Error(MissingToken)
  })
  use org <- result.try(validate_segment(org, "org"))
  use name <- result.try(validate_segment(name, "name"))
  use version <- result.try(validate_segment(version, "version"))
  case bit_array.byte_size(artifact) > max_artifact_bytes {
    True -> Error(ArtifactTooLarge(limit: max_artifact_bytes))
    False -> {
      use coordinate <- result.try(decode_json(
        bit_array.from_string(meta_json),
        publish_coordinate_decoder(),
      ))
      case coordinate == #(org, name, version) {
        False ->
          Error(InvalidInput(
            message: "publish route and meta.manifest.package coordinates differ",
          ))
        True -> {
          let boundary = "zedpkg" <> sha256_hex(crypto.strong_random_bytes(16))
          use path <- result.try(checked_version_path(org, name, version))
          use req <- result.try(base_request(client, path))
          let req =
            req
            |> request.set_method(http.Put)
            |> request.set_header(
              "content-type",
              "multipart/form-data; boundary=" <> boundary,
            )
            |> request.set_body(multipart_body(
              boundary,
              meta_json,
              "artifact.tar.gz",
              artifact,
            ))
          use req <- result.try(authorize(client, req))
          use response <- result.try(send(client, req))
          use bytes <- result.try(check(response, max_json_response_bytes))
          decode_json(bytes, model.publish_response_decoder())
        }
      }
    }
  }
}
