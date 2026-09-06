import gleam/bit_array
import gleam/http
import gleam/http/request.{type Request}
import gleam/http/response.{type Response}
import gleam/httpc
import gleam/list
import gleam/option.{None, Some}
import gleam/string
import gleeunit
import gleeunit/should
import zed_pkg_client as client
import zed_pkg_client/model

pub fn main() {
  gleeunit.main()
}

fn make_version(
  sha256 sha256: String,
  size size: Int,
  download_url download_url: String,
) -> model.VersionMetadata {
  model.VersionMetadata(
    org: "acme",
    name: "kit",
    version: "1.2.0",
    sha256: sha256,
    size: size,
    format: "tar.gz",
    vcs_tag: "v1.2.0",
    vcs_commit: None,
    download_url: download_url,
    published_at: "2024-01-01T00:00:00Z",
    yanked: False,
  )
}

/// A transport that records the request and replies with a canned response.
fn canned(
  status: Int,
  body: String,
  spy: fn(Request(BitArray)) -> Nil,
) -> client.Transport {
  fn(req: Request(BitArray), _timeout: Int) -> Result(
    Response(BitArray),
    httpc.HttpError,
  ) {
    spy(req)
    Ok(response.Response(
      status: status,
      headers: [#("content-type", "application/json")],
      body: bit_array.from_string(body),
    ))
  }
}

fn forbidden_transport(
  _req: Request(BitArray),
  _timeout: Int,
) -> Result(Response(BitArray), httpc.HttpError) {
  panic as "transport must not run"
}

fn header(req: Request(BitArray), name: String) -> Result(String, Nil) {
  request.get_header(req, name)
}

pub fn url_helpers_match_the_contract_test() {
  client.package_path("acme", "kit")
  |> should.equal("/v1/packages/acme/kit")
  client.version_path("acme", "kit", "1.2.0")
  |> should.equal("/v1/packages/acme/kit/versions/1.2.0")
  client.artifact_path("abc")
  |> should.equal("/v1/artifacts/abc")
  client.yank_path("acme", "kit", "1.2.0")
  |> should.equal("/v1/packages/acme/kit/versions/1.2.0/yank")
}

pub fn path_segments_are_percent_encoded_test() {
  client.version_path("acme", "kit", "release candidate/1")
  |> should.equal("/v1/packages/acme/kit/versions/release%20candidate%2F1")
  client.package_path("..", "kit")
  |> should.equal("/v1/packages/%2E%2E/kit")
}

pub fn download_limit_caps_at_ceiling_test() {
  client.download_limit(0)
  |> should.equal(client.max_artifact_bytes)
  client.download_limit(10)
  |> should.equal(10 + 1_048_576)
  client.download_limit(client.max_artifact_bytes)
  |> should.equal(client.max_artifact_bytes)
}

pub fn invalid_configuration_fails_before_transport_test() {
  let invalid_bases = [
    "relative/path",
    "ftp://registry.test",
    "https://user:secret@registry.test",
    "https://registry.test?tenant=one",
    "https://registry.test#fragment",
    "https://registry.test/../admin",
    "https://registry.test/%2e%2e/admin",
    "https://registry.test/a%2Fb",
  ]
  list.each(invalid_bases, fn(base) {
    let zed =
      client.new(base)
      |> client.with_transport(forbidden_transport)
    case client.get_package(zed, "acme", "kit") {
      Error(client.InvalidConfiguration(message: _))
      | Error(client.InvalidInput(message: _)) -> Nil
      other ->
        panic as {
          "expected invalid configuration, got " <> string.inspect(other)
        }
    }
  })

  let invalid_timeout =
    client.new("https://registry.test")
    |> client.with_timeout(0)
    |> client.with_transport(forbidden_transport)
  case client.search(invalid_timeout, "x") {
    Error(client.InvalidConfiguration(message: _)) -> Nil
    other ->
      panic as { "expected invalid timeout, got " <> string.inspect(other) }
  }
}

pub fn hostile_segments_fail_before_transport_test() {
  let zed =
    client.new("https://registry.test")
    |> client.with_transport(forbidden_transport)
  let values = ["", "   ", ".", "..", "line\nbreak"]
  list.each(values, fn(value) {
    case client.get_package(zed, value, "kit") {
      Error(client.InvalidInput(message: _)) -> Nil
      other ->
        panic as { "expected invalid segment, got " <> string.inspect(other) }
    }
  })
  let overlong =
    list.repeat("x", client.max_path_segment_bytes + 1)
    |> string.concat
  case client.get_version(zed, "acme", "kit", overlong) {
    Error(client.InvalidInput(message: _)) -> Nil
    other ->
      panic as { "expected overlong rejection, got " <> string.inspect(other) }
  }
}

pub fn authenticated_operations_require_a_token_before_transport_test() {
  let zed =
    client.new("https://registry.test")
    |> client.with_transport(forbidden_transport)
  client.claim_org(zed, "acme")
  |> should.equal(Error(client.MissingToken))
  client.yank(zed, "acme", "kit", "1.2.0", True)
  |> should.equal(Error(client.MissingToken))
  client.restore(zed, "acme", "kit", "1.2.0")
  |> should.equal(Error(client.MissingToken))
  client.publish(
    zed,
    "acme",
    "kit",
    "1.2.0",
    "{\"manifest\":{\"package\":{\"org\":\"acme\",\"name\":\"kit\",\"version\":\"1.2.0\"}}}",
    bit_array.from_string("bytes"),
  )
  |> should.equal(Error(client.MissingToken))
}

pub fn insecure_download_urls_are_rejected_test() {
  let base = "https://registry.zpkg.tech"
  client.allowed_download_url("http://evil.example/a", base)
  |> should.be_error
  client.allowed_download_url("file:///etc/passwd", base)
  |> should.be_error
  client.allowed_download_url("https://user:secret@cdn.example/a", base)
  |> should.be_error
  client.allowed_download_url("https://cdn.example/a#fragment", base)
  |> should.be_error
  client.allowed_download_url("https://cdn.example/a?signature=one", base)
  |> should.equal(Ok("https://cdn.example/a?signature=one"))
  client.allowed_download_url("http://127.0.0.1:8080/a", base)
  |> should.equal(Ok("http://127.0.0.1:8080/a"))
  client.allowed_download_url("http://localhost/a", base)
  |> should.equal(Ok("http://localhost/a"))
  // An http registry base opts in to plaintext downloads.
  client.allowed_download_url(
    "http://mirror.internal/a",
    "http://registry.internal",
  )
  |> should.equal(Ok("http://mirror.internal/a"))
}

pub fn get_package_decodes_metadata_test() {
  let body =
    "{\"org\":\"acme\",\"name\":\"kit\",\"vcs\":\"git\","
    <> "\"repo_url\":\"https://github.com/acme/kit\",\"latest\":\"1.2.0\","
    <> "\"versions\":[\"1.2.0\"],\"version_scheme\":\"calver\","
    <> "\"unknown_future_field\":true}"
  let zed =
    client.new("https://registry.zpkg.tech/")
    |> client.with_transport(
      canned(200, body, fn(req) {
        req.path
        |> should.equal("/v1/packages/acme/kit")
        req.method
        |> should.equal(http.Get)
        // No token attached: reads are unauthenticated.
        header(req, "authorization")
        |> should.be_error
        Nil
      }),
    )
  let assert Ok(pkg) = client.get_package(zed, "acme", "kit")
  pkg.latest
  |> should.equal(Some("1.2.0"))
  pkg.version_scheme
  |> should.equal("calver")
  pkg.versions
  |> should.equal(["1.2.0"])
}

pub fn api_errors_carry_the_registry_code_test() {
  let zed =
    client.new("https://registry.zpkg.tech")
    |> client.with_token("  zpkg_t  ")
    |> client.with_transport(
      canned(409, "{\"code\":\"org_taken\",\"message\":\"claimed\"}", fn(req) {
        header(req, "authorization")
        |> should.equal(Ok("Bearer zpkg_t"))
        Nil
      }),
    )
  client.claim_org(zed, "acme")
  |> should.equal(
    Error(client.ApiError(status: 409, code: "org_taken", message: "claimed")),
  )
}

pub fn blank_structured_error_code_uses_http_fallback_test() {
  let zed =
    client.new("https://registry.zpkg.tech")
    |> client.with_token("token")
    |> client.with_transport(
      canned(409, "{\"code\":\"   \",\"message\":\"detail\"}", fn(_) { Nil }),
    )
  client.claim_org(zed, "acme")
  |> should.equal(
    Error(client.ApiError(status: 409, code: "http_409", message: "detail")),
  )
}

pub fn non_json_error_bodies_map_to_http_status_test() {
  let zed =
    client.new("https://registry.zpkg.tech")
    |> client.with_transport(canned(500, "boom", fn(_) { Nil }))
  client.get_version(zed, "acme", "kit", "1.2.0")
  |> should.equal(
    Error(client.ApiError(status: 500, code: "http_500", message: "boom")),
  )
}

pub fn oversized_error_body_is_not_retained_test() {
  let body =
    list.repeat("x", client.max_error_body_bytes + 1)
    |> string.concat
  let zed =
    client.new("https://registry.zpkg.tech")
    |> client.with_transport(canned(502, body, fn(_) { Nil }))
  client.get_package(zed, "acme", "kit")
  |> should.equal(
    Error(client.ApiError(
      status: 502,
      code: "http_502",
      message: "registry error body exceeded the client limit",
    )),
  )
}

pub fn yank_and_restore_post_the_canonical_flag_test() {
  let zed =
    client.new("https://registry.zpkg.tech")
    |> client.with_token("zpkg_t")
    |> client.with_transport(fn(req, _timeout) {
      req.path
      |> should.equal("/v1/packages/acme/kit/versions/1.2.0/yank")
      req.method
      |> should.equal(http.Post)
      let assert Ok(body) = bit_array.to_string(req.body)
      let yanked = string.contains(body, "true")
      Ok(response.Response(
        status: 200,
        headers: [],
        body: bit_array.from_string(
          "{\"org\":\"acme\",\"name\":\"kit\",\"version\":\"1.2.0\",\"yanked\":"
          <> case yanked {
            True -> "true"
            False -> "false"
          }
          <> "}",
        ),
      ))
    })
  let assert Ok(yanked) = client.yank(zed, "acme", "kit", "1.2.0", True)
  yanked.yanked
  |> should.be_true
  let assert Ok(restored) = client.restore(zed, "acme", "kit", "1.2.0")
  restored.yanked
  |> should.be_false
}

pub fn download_verifies_sha_omits_auth_and_preserves_gateway_test() {
  let bytes = bit_array.from_string("artifact-bytes")
  let digest = client.sha256_hex(bytes)
  let zed =
    client.new("https://registry.zpkg.tech/gateway")
    |> client.with_token("zpkg_t")
    |> client.with_transport(fn(req, _timeout) {
      // Bearer token must not leak to the download host.
      header(req, "authorization")
      |> should.be_error
      req.path
      |> should.equal("/gateway/artifacts/hash")
      Ok(response.Response(status: 200, headers: [], body: bytes))
    })
  client.download_artifact(
    zed,
    make_version(
      sha256: string.uppercase(digest),
      size: 14,
      download_url: "artifacts/hash",
    ),
  )
  |> should.equal(Ok(bytes))
}

pub fn download_rejects_sha_mismatch_test() {
  let zed =
    client.new("https://registry.zpkg.tech")
    |> client.with_transport(fn(_req, _timeout) {
      Ok(response.Response(
        status: 200,
        headers: [],
        body: bit_array.from_string("tampered"),
      ))
    })
  let result =
    client.download_artifact(
      zed,
      make_version(sha256: "00", size: 8, download_url: ""),
    )
  case result {
    Error(client.Sha256Mismatch(expected: "00", actual: _)) -> Nil
    other -> panic as { "expected sha mismatch, got " <> string.inspect(other) }
  }
}

pub fn download_rejects_oversize_body_test() {
  let limit = client.download_limit(1)
  let oversize =
    list.repeat(<<0>>, limit + 64)
    |> bit_array.concat
  let zed =
    client.new("https://registry.zpkg.tech")
    |> client.with_transport(fn(_req, _timeout) {
      Ok(response.Response(status: 200, headers: [], body: oversize))
    })
  client.download_artifact(
    zed,
    make_version(sha256: "deadbeef", size: 1, download_url: ""),
  )
  |> should.equal(Error(client.ResponseTooLarge(limit: limit)))
}

pub fn multipart_body_layout_test() {
  let body =
    client.multipart_body(
      "BOUNDARY",
      "{\"manifest\":{}}",
      "artifact.tar.gz",
      bit_array.from_string("bytes"),
    )
  let assert Ok(text) = bit_array.to_string(body)
  string.contains(text, "--BOUNDARY\r\n")
  |> should.be_true
  string.contains(text, "name=\"meta\"")
  |> should.be_true
  string.contains(text, "name=\"artifact\"; filename=\"artifact.tar.gz\"")
  |> should.be_true
  string.ends_with(text, "--BOUNDARY--\r\n")
  |> should.be_true
}

pub fn publish_rejects_coordinate_mismatch_before_transport_test() {
  let zed =
    client.new("https://registry.zpkg.tech")
    |> client.with_token("token")
    |> client.with_transport(forbidden_transport)
  let result =
    client.publish(
      zed,
      "acme",
      "kit",
      "1.2.0",
      "{\"manifest\":{\"package\":{\"org\":\"other\",\"name\":\"kit\",\"version\":\"1.2.0\"}}}",
      bit_array.from_string("bytes"),
    )
  case result {
    Error(client.InvalidInput(message: _)) -> Nil
    other ->
      panic as { "expected coordinate mismatch, got " <> string.inspect(other) }
  }
}

pub fn publish_sends_fixed_filename_multipart_put_test() {
  let body =
    "{\"org\":\"acme\",\"name\":\"kit\",\"version\":\"1.2.0\",\"sha256\":\"abc\"}"
  let zed =
    client.new("https://registry.zpkg.tech")
    |> client.with_token("zpkg_t")
    |> client.with_transport(
      canned(200, body, fn(req) {
        req.method
        |> should.equal(http.Put)
        req.path
        |> should.equal("/v1/packages/acme/kit/versions/1.2.0")
        header(req, "authorization")
        |> should.equal(Ok("Bearer zpkg_t"))
        let assert Ok(content_type) = header(req, "content-type")
        string.starts_with(content_type, "multipart/form-data; boundary=")
        |> should.be_true
        let assert Ok(raw_body) = bit_array.to_string(req.body)
        string.contains(raw_body, "filename=\"artifact.tar.gz\"")
        |> should.be_true
        Nil
      }),
    )
  let assert Ok(published) =
    client.publish(
      zed,
      "acme",
      "kit",
      "1.2.0",
      "{\"manifest\":{\"package\":{\"org\":\"acme\",\"name\":\"kit\",\"version\":\"1.2.0\"}}}",
      bit_array.from_string("bytes"),
    )
  published.sha256
  |> should.equal("abc")
}
