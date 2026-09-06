//// Wire types mirroring `zed-interfaces/schemas/*.json` (snake_case as on
//// the wire; regenerate against those schemas when the contract changes).
//// Decoders ignore unknown server fields so newly added contract fields never
//// crash older clients.

import gleam/dynamic/decode
import gleam/option.{type Option, None}

pub type PackageSummary {
  PackageSummary(
    org: String,
    name: String,
    description: Option(String),
    latest: Option(String),
  )
}

pub type PackageMetadata {
  PackageMetadata(
    org: String,
    name: String,
    description: Option(String),
    vcs: String,
    repo_url: String,
    latest: Option(String),
    versions: List(String),
    version_scheme: String,
  )
}

pub type VersionMetadata {
  VersionMetadata(
    org: String,
    name: String,
    version: String,
    sha256: String,
    size: Int,
    format: String,
    vcs_tag: String,
    vcs_commit: Option(String),
    download_url: String,
    published_at: String,
    yanked: Bool,
  )
}

pub type SearchResponse {
  SearchResponse(query: String, items: List(PackageSummary))
}

pub type ClaimOrgResponse {
  ClaimOrgResponse(slug: String, created: Bool)
}

pub type YankResponse {
  YankResponse(org: String, name: String, version: String, yanked: Bool)
}

pub type PublishResponse {
  PublishResponse(org: String, name: String, version: String, sha256: String)
}

pub fn package_summary_decoder() -> decode.Decoder(PackageSummary) {
  use org <- decode.field("org", decode.string)
  use name <- decode.field("name", decode.string)
  use description <- decode.optional_field(
    "description",
    None,
    decode.optional(decode.string),
  )
  use latest <- decode.optional_field(
    "latest",
    None,
    decode.optional(decode.string),
  )
  decode.success(PackageSummary(org:, name:, description:, latest:))
}

pub fn package_metadata_decoder() -> decode.Decoder(PackageMetadata) {
  use org <- decode.field("org", decode.string)
  use name <- decode.field("name", decode.string)
  use description <- decode.optional_field(
    "description",
    None,
    decode.optional(decode.string),
  )
  use vcs <- decode.field("vcs", decode.string)
  use repo_url <- decode.field("repo_url", decode.string)
  use latest <- decode.optional_field(
    "latest",
    None,
    decode.optional(decode.string),
  )
  use versions <- decode.field("versions", decode.list(decode.string))
  use version_scheme <- decode.optional_field(
    "version_scheme",
    "semver",
    decode.string,
  )
  decode.success(PackageMetadata(
    org:,
    name:,
    description:,
    vcs:,
    repo_url:,
    latest:,
    versions:,
    version_scheme:,
  ))
}

pub fn version_metadata_decoder() -> decode.Decoder(VersionMetadata) {
  use org <- decode.field("org", decode.string)
  use name <- decode.field("name", decode.string)
  use version <- decode.field("version", decode.string)
  use sha256 <- decode.field("sha256", decode.string)
  use size <- decode.field("size", decode.int)
  use format <- decode.field("format", decode.string)
  use vcs_tag <- decode.field("vcs_tag", decode.string)
  use vcs_commit <- decode.optional_field(
    "vcs_commit",
    None,
    decode.optional(decode.string),
  )
  use download_url <- decode.field("download_url", decode.string)
  use published_at <- decode.field("published_at", decode.string)
  use yanked <- decode.optional_field("yanked", False, decode.bool)
  decode.success(VersionMetadata(
    org:,
    name:,
    version:,
    sha256:,
    size:,
    format:,
    vcs_tag:,
    vcs_commit:,
    download_url:,
    published_at:,
    yanked:,
  ))
}

pub fn search_response_decoder() -> decode.Decoder(SearchResponse) {
  use query <- decode.optional_field("query", "", decode.string)
  use items <- decode.field("items", decode.list(package_summary_decoder()))
  decode.success(SearchResponse(query:, items:))
}

pub fn claim_org_response_decoder() -> decode.Decoder(ClaimOrgResponse) {
  use slug <- decode.field("slug", decode.string)
  use created <- decode.field("created", decode.bool)
  decode.success(ClaimOrgResponse(slug:, created:))
}

pub fn yank_response_decoder() -> decode.Decoder(YankResponse) {
  use org <- decode.field("org", decode.string)
  use name <- decode.field("name", decode.string)
  use version <- decode.field("version", decode.string)
  use yanked <- decode.field("yanked", decode.bool)
  decode.success(YankResponse(org:, name:, version:, yanked:))
}

pub fn publish_response_decoder() -> decode.Decoder(PublishResponse) {
  use org <- decode.field("org", decode.string)
  use name <- decode.field("name", decode.string)
  use version <- decode.field("version", decode.string)
  use sha256 <- decode.field("sha256", decode.string)
  decode.success(PublishResponse(org:, name:, version:, sha256:))
}
