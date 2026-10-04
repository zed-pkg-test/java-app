import 'dart:async';
import 'dart:convert';
import 'dart:io' show InternetAddress;
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;

import 'models.dart';

const String defaultRegistryUrl = 'https://registry.zpkg.tech';

/// Bounds every request, including streamed response-body consumption.
const Duration defaultTimeout = Duration(seconds: 30);

/// Successful JSON documents are never allowed to grow without bound.
const int maxJsonResponseBytes = 16 * 1024 * 1024;

/// Remote error text is retained only through this bounded explicit field.
const int maxErrorBodyBytes = 16 * 1024;

/// Maximum UTF-8 size of one opaque registry route segment.
const int maxPathSegmentBytes = 256;

/// Hard ceiling on artifact downloads, matching the server's
/// `MAX_ARTIFACT_BYTES` default (100 MiB); plus the slack added to a
/// version's declared size.
const int maxArtifactBytes = 100 * 1024 * 1024;
const int _downloadSlack = 1024 * 1024;

/// The declared size (when sane) plus slack, capped by the ceiling.
int downloadLimit(int size) {
  if (size > 0) {
    final limit = size + _downloadSlack;
    return limit < maxArtifactBytes ? limit : maxArtifactBytes;
  }
  return maxArtifactBytes;
}

String _requireText(String value, String name) {
  if (value.trim().isEmpty) {
    throw ArgumentError.value(value, name, 'must not be blank');
  }
  if (value == '.' || value == '..') {
    throw ArgumentError.value(value, name, 'must not be a dot segment');
  }
  if (utf8.encode(value).length > maxPathSegmentBytes) {
    throw ArgumentError.value(
      value,
      name,
      'exceeds $maxPathSegmentBytes UTF-8 bytes',
    );
  }
  if (value.runes.any((rune) => rune < 0x20 || rune == 0x7f)) {
    throw ArgumentError.value(
      value,
      name,
      'must not contain control characters',
    );
  }
  return value;
}

String _segment(String value, [String name = 'path segment']) =>
    Uri.encodeComponent(_requireText(value, name));

String packagePath(String org, String name) =>
    '/v1/packages/${_segment(org, 'org')}/${_segment(name, 'name')}';

String versionPath(String org, String name, String version) =>
    '${packagePath(org, name)}/versions/${_segment(version, 'version')}';

String artifactPath(String sha256) =>
    '/v1/artifacts/${_segment(sha256, 'sha256')}';

String yankPath(String org, String name, String version) =>
    '${versionPath(org, name, version)}/yank';

void _validateRawRegistryPath(String raw) {
  final schemeEnd = raw.indexOf('://');
  if (schemeEnd < 0) return;
  final authorityStart = schemeEnd + 3;
  final pathStart = raw.indexOf('/', authorityStart);
  if (pathStart < 0) return;
  var pathEnd = raw.length;
  for (final marker in ['?', '#']) {
    final index = raw.indexOf(marker, pathStart);
    if (index >= 0 && index < pathEnd) pathEnd = index;
  }
  final rawPath = raw.substring(pathStart, pathEnd);
  final segments = rawPath.split('/');
  for (var index = 0; index < segments.length; index += 1) {
    final encoded = segments[index];
    if (encoded.isEmpty) continue;
    final String decoded;
    try {
      decoded = Uri.decodeComponent(encoded);
    } on FormatException {
      throw ArgumentError.value(
        raw,
        'registryUrl',
        'contains invalid percent encoding',
      );
    }
    _requireText(decoded, 'registry path segment ${index + 1}');
    if (decoded.contains('/') || decoded.contains('\\')) {
      throw ArgumentError.value(
        raw,
        'registryUrl',
        'path segments must not contain encoded separators',
      );
    }
  }
}

String normalizeRegistryUrl(String raw) {
  final trimmed = raw.trim();
  _validateRawRegistryPath(trimmed);
  final uri = Uri.tryParse(trimmed);
  if (uri == null ||
      !uri.hasScheme ||
      (uri.scheme != 'http' && uri.scheme != 'https') ||
      uri.host.isEmpty ||
      uri.userInfo.isNotEmpty ||
      uri.hasQuery ||
      uri.hasFragment) {
    throw ArgumentError.value(
      raw,
      'registryUrl',
      'must be a credential-free absolute HTTP(S) URL without query or fragment',
    );
  }
  for (var index = 0; index < uri.pathSegments.length; index += 1) {
    final segment = uri.pathSegments[index];
    if (segment.isEmpty) continue;
    _requireText(segment, 'registry path segment ${index + 1}');
    if (segment.contains('/') || segment.contains('\\')) {
      throw ArgumentError.value(
        raw,
        'registryUrl',
        'path segments must not contain encoded separators',
      );
    }
  }
  return trimmed.replaceAll(RegExp(r'/+$'), '');
}

bool _isLoopbackHost(String host) {
  if (host == 'localhost') return true;
  final bare = host.replaceAll(RegExp(r'^\[|\]$'), '');
  final address = InternetAddress.tryParse(bare);
  return address?.isLoopback ?? false;
}

/// Enforce the download-url scheme policy: https is always allowed; http only
/// for loopback hosts or when the registry base is itself http. Query strings
/// remain allowed for presigned URLs, while userinfo and fragments are refused.
String allowedDownloadUrl(String raw, String base) {
  final url = Uri.tryParse(raw);
  if (url == null ||
      !url.hasScheme ||
      url.host.isEmpty ||
      url.userInfo.isNotEmpty ||
      url.hasFragment) {
    throw ZedApiError(0, 'bad_download_url', 'download URL is invalid');
  }
  if (url.scheme == 'https') return url.toString();
  if (url.scheme == 'http' &&
      (_isLoopbackHost(url.host) || base.startsWith('http://'))) {
    return url.toString();
  }
  throw ZedApiError(
    0,
    'insecure_download_url',
    'refusing artifact download over `${url.scheme}` from $raw '
        '(https required for non-local registries)',
  );
}

/// Verify `bytes` against an expected hex sha256.
void verifySha256(List<int> bytes, String expected) {
  final actual = sha256.convert(bytes).toString();
  if (actual.toLowerCase() != expected.toLowerCase()) {
    throw ZedApiError(0, 'sha256_mismatch', 'expected $expected, got $actual');
  }
}

/// Client for the zed-pkg registry API.
final class ZedClient {
  factory ZedClient({
    String registryUrl = defaultRegistryUrl,
    String? token,
    http.Client? httpClient,
    Duration timeout = defaultTimeout,
  }) {
    if (timeout <= Duration.zero) {
      throw ArgumentError.value(timeout, 'timeout', 'must be positive');
    }
    final normalizedToken = token?.trim();
    return ZedClient._(
      base: normalizeRegistryUrl(registryUrl),
      token: normalizedToken == null || normalizedToken.isEmpty
          ? null
          : normalizedToken,
      httpClient: httpClient ?? http.Client(),
      ownsHttpClient: httpClient == null,
      timeout: timeout,
    );
  }

  ZedClient._({
    required this.base,
    required this.token,
    required http.Client httpClient,
    required bool ownsHttpClient,
    required Duration timeout,
  })  : _http = httpClient,
        _ownsHttpClient = ownsHttpClient,
        _timeout = timeout;

  final String base;
  final String? token;
  final http.Client _http;
  final bool _ownsHttpClient;
  final Duration _timeout;

  void close() {
    if (_ownsHttpClient) {
      _http.close();
    }
  }

  String _requireToken() {
    final value = token;
    if (value == null) {
      throw ZedApiError(
        0,
        'missing_token',
        'authenticated registry operation requires a nonblank bearer token',
      );
    }
    return value;
  }

  Map<String, String> _headers({bool authorized = false, String? contentType}) {
    final headers = <String, String>{'accept': 'application/json'};
    if (authorized) {
      headers['authorization'] = 'Bearer ${_requireToken()}';
    }
    if (contentType != null) {
      headers['content-type'] = contentType;
    }
    return headers;
  }

  Never _throwApiError(int status, List<int> body) {
    final text = utf8.decode(body, allowMalformed: true);
    var code = 'http_$status';
    var message = text;
    try {
      final parsed = jsonDecode(text);
      if (parsed is Map<String, dynamic>) {
        final candidate = parsed['code'];
        if (candidate is String && candidate.trim().isNotEmpty) {
          code = candidate.trim();
        }
        message =
            parsed['message'] is String ? parsed['message'] as String : text;
      }
    } on FormatException {
      // Non-JSON error body remains available only through the explicit field.
    }
    throw ZedApiError(status, code, message);
  }

  Future<Uint8List> _readBounded(
    http.StreamedResponse response,
    int limit, {
    required bool failOnOverflow,
    required String overflowCode,
    required String description,
  }) async {
    final declared = int.tryParse(response.headers['content-length'] ?? '') ??
        response.contentLength;
    if (declared != null && declared > limit && failOnOverflow) {
      throw ZedApiError(
        0,
        overflowCode,
        '$description exceeded $limit bytes; refusing',
      );
    }
    final builder = BytesBuilder(copy: false);
    await for (final chunk in response.stream.timeout(_timeout)) {
      final remaining = limit - builder.length;
      if (chunk.length > remaining) {
        if (remaining > 0) {
          builder.add(chunk.sublist(0, remaining));
        }
        if (failOnOverflow) {
          throw ZedApiError(
            0,
            overflowCode,
            '$description exceeded $limit bytes; refusing',
          );
        }
        break;
      }
      builder.add(chunk);
    }
    return builder.takeBytes();
  }

  Future<Uint8List> _sendBounded(
    http.BaseRequest request, {
    required int successLimit,
    required String successOverflowCode,
    required String successDescription,
  }) {
    request.followRedirects = false;
    request.maxRedirects = 0;
    return (() async {
      final response = await _http.send(request);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        final body = await _readBounded(
          response,
          maxErrorBodyBytes,
          failOnOverflow: false,
          overflowCode: 'error_body_too_large',
          description: 'registry error body',
        );
        _throwApiError(response.statusCode, body);
      }
      return _readBounded(
        response,
        successLimit,
        failOnOverflow: true,
        overflowCode: successOverflowCode,
        description: successDescription,
      );
    })()
        .timeout(_timeout);
  }

  Future<Map<String, dynamic>> _requestJson(
    String method,
    String path, {
    Object? body,
    bool authorized = false,
  }) async {
    final request = http.Request(method, Uri.parse('$base$path'));
    request.headers.addAll(
      _headers(
        authorized: authorized,
        contentType: body != null ? 'application/json' : null,
      ),
    );
    if (body != null) {
      request.body = jsonEncode(body);
    }
    final bytes = await _sendBounded(
      request,
      successLimit: maxJsonResponseBytes,
      successOverflowCode: 'response_too_large',
      successDescription: 'registry JSON response',
    );
    final dynamic decoded;
    try {
      decoded = jsonDecode(utf8.decode(bytes));
    } on FormatException catch (error) {
      throw ZedApiError(0, 'invalid_response', 'invalid registry JSON: $error');
    }
    if (decoded is! Map<String, dynamic>) {
      throw ZedApiError(0, 'invalid_response', 'expected a JSON object');
    }
    return decoded;
  }

  /// `GET /v1/packages/{org}/{name}` — package metadata + version list.
  Future<PackageMetadata> getPackage(String org, String name) async =>
      PackageMetadata.fromJson(
        await _requestJson('GET', packagePath(org, name)),
      );

  /// `GET /v1/packages/{org}/{name}/versions/{version}`.
  Future<VersionMetadata> getVersion(
    String org,
    String name,
    String version,
  ) async =>
      VersionMetadata.fromJson(
        await _requestJson('GET', versionPath(org, name, version)),
      );

  /// `GET /v1/search?q=`.
  Future<SearchResponse> search(String query) async => SearchResponse.fromJson(
        await _requestJson(
          'GET',
          '/v1/search?q=${Uri.encodeQueryComponent(query)}',
        ),
      );

  /// `POST /v1/orgs` (bearer token).
  Future<ClaimOrgResponse> claimOrg(String slug) async =>
      ClaimOrgResponse.fromJson(
        await _requestJson(
          'POST',
          '/v1/orgs',
          body: {'slug': _requireText(slug, 'slug')},
          authorized: true,
        ),
      );

  Future<YankResponse> setYanked(
    String org,
    String name,
    String version,
    bool yanked,
  ) async =>
      YankResponse.fromJson(
        await _requestJson(
          'POST',
          yankPath(org, name, version),
          body: {'yanked': yanked},
          authorized: true,
        ),
      );

  /// Yank a version. The optional argument is retained for compatibility.
  Future<YankResponse> yank(
    String org,
    String name,
    String version, [
    bool yanked = true,
  ]) async =>
      setYanked(org, name, version, yanked);

  /// Restore a previously yanked version.
  Future<YankResponse> restore(String org, String name, String version) async =>
      setYanked(org, name, version, false);

  /// Download an artifact, verify its sha256, and return the bytes.
  Future<Uint8List> downloadArtifact(VersionMetadata version) async {
    final raw = version.downloadUrl.trim();
    final String url;
    if (raw.isEmpty) {
      url = '$base${artifactPath(version.sha256)}';
    } else {
      final parsed = Uri.tryParse(raw);
      if (parsed != null && parsed.hasScheme) {
        url = allowedDownloadUrl(raw, base);
      } else {
        url = allowedDownloadUrl(
          Uri.parse('$base/').resolve(raw).toString(),
          base,
        );
      }
    }
    // Deliberately no auth header: download_url may point at a third-party
    // host (e.g. a presigned S3/R2 URL) and the token must not leak there.
    final request = http.Request('GET', Uri.parse(url));
    final bytes = await _sendBounded(
      request,
      successLimit: downloadLimit(version.size),
      successOverflowCode: 'artifact_too_large',
      successDescription: 'artifact',
    );
    verifySha256(bytes, version.sha256);
    return bytes;
  }

  /// Publish: multipart `meta` (PublishMeta JSON) + `artifact` bytes.
  Future<PublishResponse> publish(
    Map<String, dynamic> meta,
    List<int> artifact,
  ) async {
    _requireToken();
    if (artifact.length > maxArtifactBytes) {
      throw ZedApiError(
        0,
        'artifact_too_large',
        'artifact exceeded $maxArtifactBytes bytes; refusing',
      );
    }
    final manifest = meta['manifest'];
    final package =
        manifest is Map<String, dynamic> ? manifest['package'] : null;
    if (package is! Map<String, dynamic> ||
        package['org'] is! String ||
        package['name'] is! String ||
        package['version'] is! String) {
      throw ZedApiError(
        0,
        'invalid_publish_meta',
        'meta.manifest.package is required',
      );
    }
    final org = _requireText(
      package['org'] as String,
      'meta.manifest.package.org',
    );
    final name = _requireText(
      package['name'] as String,
      'meta.manifest.package.name',
    );
    final version = _requireText(
      package['version'] as String,
      'meta.manifest.package.version',
    );
    final request = http.MultipartRequest(
      'PUT',
      Uri.parse('$base${versionPath(org, name, version)}'),
    )
      ..followRedirects = false
      ..maxRedirects = 0
      ..headers.addAll(_headers(authorized: true))
      ..fields['meta'] = jsonEncode(meta)
      ..files.add(
        http.MultipartFile.fromBytes(
          'artifact',
          artifact,
          filename: '$org-$name-$version.tar.gz',
        ),
      );
    final bytes = await _sendBounded(
      request,
      successLimit: maxJsonResponseBytes,
      successOverflowCode: 'response_too_large',
      successDescription: 'registry JSON response',
    );
    final dynamic decoded;
    try {
      decoded = jsonDecode(utf8.decode(bytes));
    } on FormatException catch (error) {
      throw ZedApiError(0, 'invalid_response', 'invalid registry JSON: $error');
    }
    if (decoded is! Map<String, dynamic>) {
      throw ZedApiError(0, 'invalid_response', 'expected a JSON object');
    }
    return PublishResponse.fromJson(decoded);
  }
}
