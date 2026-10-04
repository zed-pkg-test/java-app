from __future__ import annotations

import email
import email.policy
import hashlib
import http.server
import json
import os
import tempfile
import threading
import unittest
from unittest import mock

from zed_pkg_client import (
    MAX_ARTIFACT_BYTES,
    MAX_ERROR_BODY_BYTES,
    PackageMetadata,
    PublishResponse,
    VersionMetadata,
    YankResponse,
    ZedApiError,
    ZedClient,
    _download_limit,
    artifact_path,
    package_path,
    version_path,
    yank_path,
)


class _FakeResponse:
    def __init__(self, payload: dict | bytes, headers: dict | None = None):
        self._payload = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.headers = headers or {}
        self._offset = 0

    def read(self, size: int = -1) -> bytes:
        if size < 0:
            size = len(self._payload) - self._offset
        start = self._offset
        self._offset = min(len(self._payload), self._offset + size)
        return self._payload[start : self._offset]

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class _FakeOpener:
    def __init__(self, response):
        self.response = response
        self.requests = []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        return self.response


class UrlAndConstructionTest(unittest.TestCase):
    def test_paths_match_and_encode_core_contract(self):
        self.assertEqual(package_path("acme", "kit"), "/v1/packages/acme/kit")
        self.assertEqual(
            version_path("acme", "kit", "1.2.0"),
            "/v1/packages/acme/kit/versions/1.2.0",
        )
        self.assertEqual(
            yank_path("acme", "kit", "1.2.0"),
            "/v1/packages/acme/kit/versions/1.2.0/yank",
        )
        self.assertEqual(artifact_path("abc"), "/v1/artifacts/abc")
        self.assertEqual(
            version_path("acme", "kit", "release candidate/1"),
            "/v1/packages/acme/kit/versions/release%20candidate%2F1",
        )
        self.assertEqual(package_path("a?b", "c#d"), "/v1/packages/a%3Fb/c%23d")

    def test_base_url_is_validated_and_token_is_redacted(self):
        client = ZedClient(" https://registry.zpkg.tech/gateway/// ", token="very-secret")
        self.assertEqual(client.base, "https://registry.zpkg.tech/gateway")
        self.assertIn("[REDACTED]", repr(client))
        self.assertNotIn("very-secret", repr(client))
        for invalid in (
            "relative/path",
            "ftp://registry.test",
            "https://user:secret@registry.test",
            "https://registry.test?tenant=one",
            "https://registry.test#fragment",
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                ZedClient(invalid)


class WireAndAuthTest(unittest.TestCase):
    def test_unknown_server_fields_are_ignored(self):
        payload = {
            "org": "acme",
            "name": "kit",
            "vcs": "git",
            "repo_url": "https://github.com/acme/kit",
            "versions": ["1.2.0"],
            "version_scheme": "calver",
            "tags": ["http"],
            "brand_new_server_field": {"nested": True},
        }
        opener = _FakeOpener(_FakeResponse(payload))
        package = ZedClient("https://x.test", opener=opener).get_package("acme", "kit")
        self.assertIsInstance(package, PackageMetadata)
        self.assertEqual(package.version_scheme, "calver")
        self.assertEqual(package.tags, ["http"])
        self.assertFalse(hasattr(package, "brand_new_server_field"))

    def test_public_reads_omit_auth_and_mutations_attach_it(self):
        search_opener = _FakeOpener(_FakeResponse({"query": "kit", "items": []}))
        search_client = ZedClient("https://x.test/gateway", token=" secret ", opener=search_opener)
        search_client.search("kit")
        search_request = search_opener.requests[0][0]
        self.assertIsNone(search_request.get_header("Authorization"))
        self.assertEqual(search_request.full_url, "https://x.test/gateway/v1/search?q=kit")

        yank_opener = _FakeOpener(
            _FakeResponse({"org": "acme", "name": "kit", "version": "1.2.0", "yanked": True})
        )
        yank_client = ZedClient("https://x.test", token=" secret ", opener=yank_opener)
        result = yank_client.yank("acme", "kit", "1.2.0", True)
        self.assertEqual(
            result,
            YankResponse(org="acme", name="kit", version="1.2.0", yanked=True),
        )
        yank_request = yank_opener.requests[0][0]
        self.assertEqual(yank_request.get_header("Authorization"), "Bearer secret")
        self.assertEqual(yank_request.get_method(), "POST")
        self.assertEqual(json.loads(yank_request.data), {"yanked": True})

    def test_publish_builds_authenticated_multipart_put(self):
        response = {"org": "acme", "name": "kit", "version": "1.2.0", "sha256": "abc"}
        meta = {"manifest": {"package": {"org": "acme", "name": "kit", "version": "1.2.0"}}}
        opener = _FakeOpener(_FakeResponse(response))
        client = ZedClient("https://x.test", token="zpkg_t", opener=opener)
        result = client.publish("acme", "kit", "1.2.0", meta, b"\x1f\x8bartifact-bytes")

        self.assertEqual(
            result,
            PublishResponse(org="acme", name="kit", version="1.2.0", sha256="abc"),
        )
        request = opener.requests[0][0]
        self.assertEqual(request.get_method(), "PUT")
        self.assertEqual(request.get_header("Authorization"), "Bearer zpkg_t")
        content_type = request.get_header("Content-type")
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))
        message = email.message_from_bytes(
            f"Content-Type: {content_type}\r\n\r\n".encode() + request.data,
            policy=email.policy.HTTP,
        )
        meta_part, artifact_part = message.get_payload()
        self.assertEqual(json.loads(meta_part.get_payload()), meta)
        self.assertEqual(artifact_part.get_payload(decode=True), b"\x1f\x8bartifact-bytes")

    def test_error_text_is_bounded_and_not_in_default_diagnostic(self):
        remote = b"provider-secret" * MAX_ERROR_BODY_BYTES
        error = mock.MagicMock()
        error.code = 502
        error.headers = {}
        error.read.side_effect = lambda size=-1: remote[:size]
        mapped = __import__("zed_pkg_client")._api_error(error)
        self.assertEqual(str(mapped), "registry error 502: http_502")
        self.assertNotIn("provider-secret", str(mapped))
        self.assertLessEqual(len(mapped.registry_message.encode()), MAX_ERROR_BODY_BYTES)


class _RecordingServer:
    def __init__(
        self,
        body: bytes,
        content_length: int | None = None,
        status: int = 200,
        redirect_to: str | None = None,
    ):
        self.body = body
        self.content_length = content_length
        self.status = status
        self.redirect_to = redirect_to
        self.last_headers: dict = {}
        self.last_path = ""
        server = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                server.last_headers = dict(self.headers)
                server.last_path = self.path
                if server.redirect_to is not None:
                    self.send_response(302)
                    self.send_header("Location", server.redirect_to)
                    self.end_headers()
                    return
                self.send_response(server.status)
                length = server.content_length
                if length is None:
                    length = len(server.body)
                self.send_header("Content-Length", str(length))
                self.end_headers()
                try:
                    self.wfile.write(server.body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *args):
                pass

        self._httpd = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self._httpd.server_address[1]}"
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._httpd.shutdown()
        self._httpd.server_close()
        return False


def _version(url: str, body: bytes, size: int = 0) -> VersionMetadata:
    return VersionMetadata(
        org="acme",
        name="kit",
        version="1.2.0",
        sha256=hashlib.sha256(body).hexdigest(),
        size=size,
        format="tar.gz",
        vcs_tag="v1.2.0",
        download_url=url,
        published_at="2024-01-01T00:00:00Z",
    )


class DownloadArtifactTest(unittest.TestCase):
    def test_no_auth_header_relative_resolution_and_sha_verification(self):
        body = b"artifact-bytes"
        with _RecordingServer(body) as server, tempfile.TemporaryDirectory() as tmp:
            client = ZedClient(f"{server.url}/gateway", token="zpkg_t")
            version = _version("artifact/file", body, size=len(body))
            dest = os.path.join(tmp, "artifact.tar.gz")
            client.download_artifact(version, dest)
            self.assertNotIn("authorization", {key.lower() for key in server.last_headers})
            self.assertEqual(server.last_path, "/gateway/artifact/file")
            with open(dest, "rb") as handle:
                self.assertEqual(handle.read(), body)

    def test_redirects_are_refused(self):
        with _RecordingServer(b"destination") as destination:
            with _RecordingServer(b"", redirect_to=destination.url) as source:
                client = ZedClient(source.url, token="secret")
                with self.assertRaises(ZedApiError) as context:
                    client.download_artifact(_version(f"{source.url}/a", b"destination"), "/dev/null")
                self.assertEqual(context.exception.code, "http_302")
                self.assertEqual(destination.last_path, "")

    def test_insecure_and_oversized_downloads_are_rejected(self):
        client = ZedClient("https://registry.zpkg.tech", token="zpkg_t")
        for target in ("http://evil.example/artifact", "file:///etc/passwd"):
            with self.subTest(target=target), self.assertRaises(ZedApiError) as context:
                client.download_artifact(_version(target, b"x"), "/dev/null")
            self.assertEqual(context.exception.code, "insecure_download_url")

        limit = _download_limit(1)
        body = b"\0" * (limit + 64)
        with _RecordingServer(body) as server, tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ZedApiError) as context:
                ZedClient(server.url).download_artifact(
                    _version(f"{server.url}/artifact", body, size=1),
                    os.path.join(tmp, "a"),
                )
            self.assertEqual(context.exception.code, "artifact_too_large")

        with _RecordingServer(b"small", content_length=MAX_ARTIFACT_BYTES + 1) as server:
            with self.assertRaises(ZedApiError) as context:
                ZedClient(server.url).download_artifact(
                    _version(f"{server.url}/artifact", b"small"),
                    "/dev/null",
                )
            self.assertEqual(context.exception.code, "artifact_too_large")


if __name__ == "__main__":
    unittest.main()
