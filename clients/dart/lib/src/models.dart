/// Wire types mirroring `zed-interfaces/schemas/*.json` (snake_case as on the
/// wire; regenerate against those schemas when the contract changes). Unknown
/// server fields are ignored so newly added contract fields never crash older
/// clients.
library;

/// Registry error carrying the stable `ApiError.code` and bounded explicit
/// remote text. The default diagnostic intentionally excludes that text.
class ZedApiError implements Exception {
  ZedApiError(this.status, this.code, this.message);

  final int status;
  final String code;
  final String message;

  /// Explicit alias for callers that intentionally inspect bounded remote text.
  String get registryMessage => message;

  @override
  String toString() => 'registry error $status: $code';
}

class PackageSummary {
  PackageSummary({
    required this.org,
    required this.name,
    this.description,
    this.latest,
  });

  factory PackageSummary.fromJson(Map<String, dynamic> json) => PackageSummary(
        org: json['org'] as String,
        name: json['name'] as String,
        description: json['description'] as String?,
        latest: json['latest'] as String?,
      );

  final String org;
  final String name;
  final String? description;
  final String? latest;
}

class PackageMetadata {
  PackageMetadata({
    required this.org,
    required this.name,
    required this.vcs,
    required this.repoUrl,
    required this.versions,
    this.description,
    this.latest,
    this.versionScheme = 'semver',
  });

  factory PackageMetadata.fromJson(Map<String, dynamic> json) =>
      PackageMetadata(
        org: json['org'] as String,
        name: json['name'] as String,
        vcs: json['vcs'] as String,
        repoUrl: json['repo_url'] as String,
        versions: (json['versions'] as List<dynamic>? ?? const [])
            .cast<String>()
            .toList(),
        description: json['description'] as String?,
        latest: json['latest'] as String?,
        versionScheme: json['version_scheme'] as String? ?? 'semver',
      );

  final String org;
  final String name;
  final String vcs;
  final String repoUrl;
  final List<String> versions;
  final String? description;
  final String? latest;
  final String versionScheme;
}

class VersionMetadata {
  VersionMetadata({
    required this.org,
    required this.name,
    required this.version,
    required this.sha256,
    required this.size,
    required this.format,
    required this.vcsTag,
    required this.downloadUrl,
    required this.publishedAt,
    this.yanked = false,
    this.vcsCommit,
  });

  factory VersionMetadata.fromJson(Map<String, dynamic> json) =>
      VersionMetadata(
        org: json['org'] as String,
        name: json['name'] as String,
        version: json['version'] as String,
        sha256: json['sha256'] as String,
        size: (json['size'] as num).toInt(),
        format: json['format'] as String,
        vcsTag: json['vcs_tag'] as String,
        downloadUrl: json['download_url'] as String,
        publishedAt: json['published_at'] as String,
        yanked: json['yanked'] as bool? ?? false,
        vcsCommit: json['vcs_commit'] as String?,
      );

  final String org;
  final String name;
  final String version;
  final String sha256;
  final int size;
  final String format;
  final String vcsTag;
  final String downloadUrl;
  final String publishedAt;
  final bool yanked;
  final String? vcsCommit;
}

class SearchResponse {
  SearchResponse({required this.query, required this.items});

  factory SearchResponse.fromJson(Map<String, dynamic> json) => SearchResponse(
        query: json['query'] as String? ?? '',
        items: (json['items'] as List<dynamic>? ?? const [])
            .map(
                (item) => PackageSummary.fromJson(item as Map<String, dynamic>))
            .toList(),
      );

  final String query;
  final List<PackageSummary> items;
}

class ClaimOrgResponse {
  ClaimOrgResponse({required this.slug, required this.created});

  factory ClaimOrgResponse.fromJson(Map<String, dynamic> json) =>
      ClaimOrgResponse(
        slug: json['slug'] as String,
        created: json['created'] as bool,
      );

  final String slug;
  final bool created;
}

class YankResponse {
  YankResponse({
    required this.org,
    required this.name,
    required this.version,
    required this.yanked,
  });

  factory YankResponse.fromJson(Map<String, dynamic> json) => YankResponse(
        org: json['org'] as String,
        name: json['name'] as String,
        version: json['version'] as String,
        yanked: json['yanked'] as bool,
      );

  final String org;
  final String name;
  final String version;
  final bool yanked;
}

class PublishResponse {
  PublishResponse({
    required this.org,
    required this.name,
    required this.version,
    required this.sha256,
  });

  factory PublishResponse.fromJson(Map<String, dynamic> json) =>
      PublishResponse(
        org: json['org'] as String,
        name: json['name'] as String,
        version: json['version'] as String,
        sha256: json['sha256'] as String,
      );

  final String org;
  final String name;
  final String version;
  final String sha256;
}
