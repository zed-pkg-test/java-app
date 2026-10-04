import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:test/test.dart';
import 'package:zed_pkg_client/zed_pkg_client.dart';
import 'package:zed_pkg_client/src/client.dart' as client;

VersionMetadata makeVersion({
  String sha256 = '',
  int size = 0,
  String downloadUrl = '',
}) =>
    VersionMetadata(
      org: 'acme',
      name: 'kit',
      version: '1.2.0',
      sha256: sha256,
      size: size,
      format: 'tar.gz',
      vcsTag: 'v1.2.0',
      downloadUrl: downloadUrl,
      publishedAt: '2024-01-01T00:00:00Z',
    );

void main() {
  test('url helpers match the contract', () {
    expect(client.packagePath('acme', 'kit'), '/v1/packages/acme/kit');
    expect(
      client.versionPath('acme', 'kit', '1.2.0'),
      '/v1/packages/acme/kit/versions/1.2.0',
    );
    expect(client.artifactPath('abc'), '/v1/artifacts/abc');
    expect(
      client.yankPath('acme', 'kit', '1.2.0'),
      '/v1/packages/acme/kit/versions/1.2.0/yank',
    );
  });

  test('path segments are percent-encoded', () {
    expect(
      client.versionPath('acme', 'kit', 'release candidate/1'),
      '/v1/packages/acme/kit/versions/release%20candidate%2F1',
    );
  });

  test('download limit caps at the ceiling', () {
    expect(client.downloadLimit(0), client.maxArtifactBytes);
    expect(client.downloadLimit(10), 10 + 1024 * 1024);
    expect(
      client.downloadLimit(client.maxArtifactBytes),
      client.maxArtifactBytes,
    );
  });

  test('insecure download urls are rejected', () {
    const base = 'https://registry.zpkg.tech';
    for (final raw in ['http://evil.example/a', 'file:///etc/passwd']) {
      expect(
        () => client.allowedDownloadUrl(raw, base),
        throwsA(isA<ZedApiError>()),
      );
    }
    expect(
      client.allowedDownloadUrl('https://cdn.example/a', base),
      'https://cdn.example/a',
    );
    expect(
      client.allowedDownloadUrl('http://127.0.0.1:8080/a', base),
      'http://127.0.0.1:8080/a',
    );
    expect(
      client.allowedDownloadUrl('http://localhost/a', base),
      'http://localhost/a',
    );
    expect(
      client.allowedDownloadUrl(
        'http://mirror.internal/a',
        'http://registry.internal',
      ),
      'http://mirror.internal/a',
    );
  });

  test('getPackage decodes metadata and sends no auth header', () async {
    late http.Request seen;
    final mock = MockClient((request) async {
      seen = request;
      return http.Response(
        jsonEncode({
          'org': 'acme',
          'name': 'kit',
          'vcs': 'git',
          'repo_url': 'https://github.com/acme/kit',
          'latest': '1.2.0',
          'versions': ['1.2.0'],
          'version_scheme': 'calver',
          'unknown_future_field': true,
        }),
        200,
        headers: {'content-type': 'application/json'},
      );
    });
    final zed = ZedClient(
      registryUrl: 'https://registry.zpkg.tech/',
      httpClient: mock,
    );
    final pkg = await zed.getPackage('acme', 'kit');
    expect(
      seen.url.toString(),
      'https://registry.zpkg.tech/v1/packages/acme/kit',
    );
    expect(seen.headers.containsKey('authorization'), isFalse);
    expect(pkg.latest, '1.2.0');
    expect(pkg.versionScheme, 'calver');
  });

  test(
    'api errors carry the registry code without leaking by default',
    () async {
      final mock = MockClient(
        (request) async => http.Response(
          jsonEncode({'code': 'org_taken', 'message': 'claimed'}),
          409,
        ),
      );
      final zed = ZedClient(httpClient: mock, token: 'zpkg_t');
      try {
        await zed.claimOrg('acme');
        fail('expected ZedApiError');
      } on ZedApiError catch (error) {
        expect(error.status, 409);
        expect(error.code, 'org_taken');
        expect(error.message, 'claimed');
        expect(error.registryMessage, 'claimed');
        expect(error.toString(), 'registry error 409: org_taken');
      }
    },
  );

  test('non-JSON error bodies map to the HTTP-derived code', () async {
    final mock = MockClient((request) async => http.Response('boom', 500));
    final zed = ZedClient(httpClient: mock);
    try {
      await zed.getVersion('acme', 'kit', '1.2.0');
      fail('expected ZedApiError');
    } on ZedApiError catch (error) {
      expect(error.code, 'http_500');
      expect(error.message, 'boom');
      expect(error.toString(), 'registry error 500: http_500');
    }
  });

  test('claimOrg and yank send the bearer token', () async {
    final auths = <String?>[];
    final mock = MockClient((request) async {
      auths.add(request.headers['authorization']);
      if (request.url.path.endsWith('/yank')) {
        return http.Response(
          jsonEncode({
            'org': 'acme',
            'name': 'kit',
            'version': '1.2.0',
            'yanked': true,
          }),
          200,
        );
      }
      return http.Response(jsonEncode({'slug': 'acme', 'created': true}), 200);
    });
    final zed = ZedClient(httpClient: mock, token: 'zpkg_t');
    final org = await zed.claimOrg('acme');
    expect(org.created, isTrue);
    final yank = await zed.yank('acme', 'kit', '1.2.0', true);
    expect(yank.yanked, isTrue);
    expect(auths, ['Bearer zpkg_t', 'Bearer zpkg_t']);
  });

  test('downloadArtifact verifies sha256 and omits the token', () async {
    final body = utf8.encode('artifact-bytes');
    final digest = sha256.convert(body).toString();
    String? auth;
    final mock = MockClient((request) async {
      auth = request.headers['authorization'];
      return http.Response.bytes(body, 200);
    });
    final zed = ZedClient(httpClient: mock, token: 'zpkg_t');
    final bytes = await zed.downloadArtifact(
      makeVersion(sha256: digest, size: body.length),
    );
    expect(bytes, body);
    expect(auth, isNull, reason: 'bearer token must not leak to downloads');
  });

  test('downloadArtifact rejects a sha mismatch', () async {
    final mock = MockClient(
      (request) async => http.Response.bytes(utf8.encode('tampered'), 200),
    );
    final zed = ZedClient(httpClient: mock);
    expect(
      () => zed.downloadArtifact(makeVersion(sha256: '00', size: 8)),
      throwsA(
        isA<ZedApiError>().having((e) => e.code, 'code', 'sha256_mismatch'),
      ),
    );
  });

  test('downloadArtifact rejects an oversize body', () async {
    final limit = client.downloadLimit(1);
    final mock = MockClient(
      (request) async => http.Response.bytes(List.filled(limit + 64, 0), 200),
    );
    final zed = ZedClient(httpClient: mock);
    expect(
      () => zed.downloadArtifact(makeVersion(sha256: 'deadbeef', size: 1)),
      throwsA(
        isA<ZedApiError>().having((e) => e.code, 'code', 'artifact_too_large'),
      ),
    );
  });

  test('publish sends multipart meta + artifact', () async {
    late http.Request seen;
    final mock = MockClient((request) async {
      seen = request;
      return http.Response(
        jsonEncode({
          'org': 'acme',
          'name': 'kit',
          'version': '1.2.0',
          'sha256': 'abc',
        }),
        200,
      );
    });
    final zed = ZedClient(httpClient: mock, token: 'zpkg_t');
    final meta = {
      'manifest': {
        'package': {'org': 'acme', 'name': 'kit', 'version': '1.2.0'},
      },
    };
    final response = await zed.publish(meta, utf8.encode('bytes'));
    expect(response.sha256, 'abc');
    expect(seen.method, 'PUT');
    expect(seen.url.path, '/v1/packages/acme/kit/versions/1.2.0');
    expect(seen.headers['content-type'], startsWith('multipart/form-data'));
    expect(seen.headers['authorization'], 'Bearer zpkg_t');
    final raw = latin1.decode(seen.bodyBytes);
    expect(raw, contains('name="meta"'));
    expect(raw, contains('name="artifact"'));
    expect(raw, contains('filename="acme-kit-1.2.0.tar.gz"'));
  });
}
