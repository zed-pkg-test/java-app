import 'dart:async';
import 'dart:collection';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:test/test.dart';
import 'package:zed_pkg_client/zed_pkg_client.dart';
import 'package:zed_pkg_client/src/client.dart' as client;

class _HugeList extends ListBase<int> {
  @override
  int get length => client.maxArtifactBytes + 1;

  @override
  set length(int value) => throw UnsupportedError('immutable');

  @override
  int operator [](int index) => 0;

  @override
  void operator []=(int index, int value) =>
      throw UnsupportedError('immutable');
}

class _SlowBodyClient extends http.BaseClient {
  http.BaseRequest? seen;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    seen = request;
    return http.StreamedResponse(
      Stream<List<int>>.periodic(
        const Duration(milliseconds: 100),
        (_) => utf8.encode('{}'),
      ).take(1),
      200,
    );
  }
}

Map<String, dynamic> _meta({String org = 'acme'}) => {
      'manifest': {
        'package': {'org': org, 'name': 'kit', 'version': '1.2.0'},
      },
    };

VersionMetadata _version(List<int> body, {String downloadUrl = ''}) =>
    VersionMetadata(
      org: 'acme',
      name: 'kit',
      version: '1.2.0',
      sha256: sha256.convert(body).toString().toUpperCase(),
      size: body.length,
      format: 'tar.gz',
      vcsTag: 'v1.2.0',
      downloadUrl: downloadUrl,
      publishedAt: '2026-08-02T00:00:00Z',
    );

void main() {
  test('registry bases and hostile route segments are rejected', () {
    for (final base in [
      'relative/path',
      'ftp://registry.test',
      'https://user:secret@registry.test',
      'https://registry.test?tenant=one',
      'https://registry.test#fragment',
      'https://registry.test/../admin',
      'https://registry.test/%2e%2e/admin',
      'https://registry.test/a%2Fb',
    ]) {
      expect(
        () => ZedClient(registryUrl: base),
        throwsArgumentError,
        reason: base,
      );
    }
    for (final value in ['', '   ', '.', '..', 'line\nbreak', 'nul\x00byte']) {
      expect(
        () => client.packagePath(value, 'kit'),
        throwsArgumentError,
        reason: value,
      );
    }
    expect(
      () => client.versionPath(
        'acme',
        'kit',
        'x' * (client.maxPathSegmentBytes + 1),
      ),
      throwsArgumentError,
    );
  });

  test(
    'authenticated operations fail before transport without a token',
    () async {
      var calls = 0;
      final mock = MockClient((request) async {
        calls += 1;
        throw StateError('transport must not run');
      });
      final zed = ZedClient(httpClient: mock);
      final operations = <Future<Object?> Function()>[
        () => zed.claimOrg('acme'),
        () => zed.yank('acme', 'kit', '1.2.0'),
        () => zed.restore('acme', 'kit', '1.2.0'),
        () => zed.publish(_meta(), utf8.encode('artifact')),
      ];
      for (final operation in operations) {
        await expectLater(
          operation,
          throwsA(
            isA<ZedApiError>().having(
              (error) => error.code,
              'code',
              'missing_token',
            ),
          ),
        );
      }
      expect(calls, 0);
    },
  );

  test(
    'redirect refusal is explicit on registry and artifact requests',
    () async {
      final seen = <http.Request>[];
      final body = utf8.encode('artifact');
      final mock = MockClient((request) async {
        seen.add(request);
        if (request.url.path.contains('/artifacts/')) {
          return http.Response.bytes(body, 200);
        }
        return http.Response(jsonEncode({'query': 'x', 'items': []}), 200);
      });
      final zed = ZedClient(httpClient: mock);
      await zed.search('x');
      await zed.downloadArtifact(_version(body));
      expect(seen, hasLength(2));
      for (final request in seen) {
        expect(request.followRedirects, isFalse);
        expect(request.maxRedirects, 0);
      }
    },
  );

  test('success and error bodies use independent bounds', () async {
    final oversizedSuccess = MockClient(
      (request) async => http.Response(
        '{}',
        200,
        headers: {'content-length': '${client.maxJsonResponseBytes + 1}'},
      ),
    );
    await expectLater(
      ZedClient(httpClient: oversizedSuccess).search('x'),
      throwsA(
        isA<ZedApiError>().having(
          (error) => error.code,
          'code',
          'response_too_large',
        ),
      ),
    );

    final remote = 'provider-secret' * client.maxErrorBodyBytes;
    final oversizedError = MockClient(
      (request) async => http.Response(remote, 502),
    );
    try {
      await ZedClient(httpClient: oversizedError).search('x');
      fail('expected ZedApiError');
    } on ZedApiError catch (error) {
      expect(
        utf8.encode(error.registryMessage).length,
        lessThanOrEqualTo(client.maxErrorBodyBytes),
      );
      expect(error.toString(), 'registry error 502: http_502');
      expect(error.toString(), isNot(contains('provider-secret')));
    }
  });

  test('relative artifact urls preserve the registry gateway prefix', () async {
    final body = utf8.encode('artifact');
    late Uri seen;
    final mock = MockClient((request) async {
      seen = request.url;
      return http.Response.bytes(body, 200);
    });
    final zed = ZedClient(
      registryUrl: 'https://registry.test/gateway',
      httpClient: mock,
    );
    final bytes = await zed.downloadArtifact(
      _version(body, downloadUrl: 'artifacts/hash'),
    );
    expect(bytes, body);
    expect(seen.toString(), 'https://registry.test/gateway/artifacts/hash');
  });

  test(
    'credentialed and fragmented artifact urls fail before transport',
    () async {
      var calls = 0;
      final zed = ZedClient(
        httpClient: MockClient((request) async {
          calls += 1;
          return http.Response('', 200);
        }),
      );
      for (final raw in [
        'https://user:secret@cdn.test/artifact',
        'https://cdn.test/artifact#fragment',
      ]) {
        await expectLater(
          zed.downloadArtifact(_version([1], downloadUrl: raw)),
          throwsA(
            isA<ZedApiError>().having(
              (error) => error.code,
              'code',
              'bad_download_url',
            ),
          ),
        );
      }
      expect(calls, 0);
    },
  );

  test('restore sends the canonical false body with a trimmed token', () async {
    late http.Request seen;
    final mock = MockClient((request) async {
      seen = request;
      return http.Response(
        jsonEncode({
          'org': 'acme',
          'name': 'kit',
          'version': '1.2.0',
          'yanked': false,
        }),
        200,
      );
    });
    final result = await ZedClient(
      httpClient: mock,
      token: ' token ',
    ).restore('acme', 'kit', '1.2.0');
    expect(result.yanked, isFalse);
    expect(seen.headers['authorization'], 'Bearer token');
    expect(jsonDecode(seen.body), {'yanked': false});
  });

  test('publish rejects oversized artifacts before transport', () async {
    var calls = 0;
    final zed = ZedClient(
      token: 'token',
      httpClient: MockClient((request) async {
        calls += 1;
        throw StateError('transport must not run');
      }),
    );
    await expectLater(
      zed.publish(_meta(), _HugeList()),
      throwsA(
        isA<ZedApiError>().having(
          (error) => error.code,
          'code',
          'artifact_too_large',
        ),
      ),
    );
    expect(calls, 0);
  });

  test('deadline remains active while a response body is pending', () async {
    final slow = _SlowBodyClient();
    final zed = ZedClient(
      httpClient: slow,
      timeout: const Duration(milliseconds: 5),
    );
    await expectLater(zed.search('x'), throwsA(isA<TimeoutException>()));
    expect(slow.seen?.followRedirects, isFalse);
  });
}
