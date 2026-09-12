from __future__ import annotations

import hashlib
import json
import math
import os
import tempfile
import unittest

from zed_pkg_client import (
    MAX_ARTIFACT_BYTES,
    MAX_PATH_SEGMENT_BYTES,
    VersionMetadata,
    ZedApiError,
    ZedClient,
    package_path,
    version_path,
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


class _CountingOpener:
    def __init__(self, response=None):
        self.response = response
        self.calls = []

    def open(self, request, timeout):
        self.calls.append((request, timeout))
        if self.response is None:
            raise AssertionError("transport must not run")
        return self.response


class _HugeArtifact:
    def __len__(self):
        return MAX_ARTIFACT_BYTES + 1


META = {
    "manifest": {
        "package": {
            "org": "acme",
            "name": "kit",
            "version": "1.2.0",
        }
    }
}


def _version(body: bytes, *, download_url: str = "") -> VersionMetadata:
    return VersionMetadata(
        org="acme",
        name="kit",
        version="1.2.0",
        sha256=hashlib.sha256(body).hexdigest().upper(),
        size=len(body),
        format="tar.gz",
        vcs_tag="v1.2.0",
        download_url=download_url,
        published_at="2026-08-02T00:00:00Z",
    )


class HostileInputTest(unittest.TestCase):
    def test_dot_control_blank_and_overlong_segments_are_rejected(self):
        for value in ("", "   ", ".", "..", "line\nbreak", "nul\0byte"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                package_path(value, "kit")
        with self.assertRaises(ValueError):
            version_path("acme", "kit", "x" * (MAX_PATH_SEGMENT_BYTES + 1))
        self.assertEqual(
            version_path("acme", "kit", "release candidate/1"),
            "/v1/packages/acme/kit/versions/release%20candidate%2F1",
        )

    def test_registry_base_rejects_dot_and_encoded_separator_segments(self):
        for base in (
            "https://registry.test/../admin",
            "https://registry.test/%2e%2e/admin",
            "https://registry.test/a%2Fb",
        ):
            with self.subTest(base=base), self.assertRaises(ValueError):
                ZedClient(base)

    def test_timeout_must_be_positive_and_finite(self):
        for timeout in (0, -1, math.inf, -math.inf, math.nan):
            with self.subTest(timeout=timeout), self.assertRaises(ValueError):
                ZedClient("https://registry.test", timeout=timeout)


class FailClosedAuthTest(unittest.TestCase):
    def test_authenticated_operations_do_not_invoke_transport_without_token(self):
        opener = _CountingOpener()
        client = ZedClient("https://registry.test", opener=opener)
        operations = (
            lambda: client.claim_org("acme"),
            lambda: client.yank("acme", "kit", "1.2.0"),
            lambda: client.restore("acme", "kit", "1.2.0"),
            lambda: client.publish("acme", "kit", "1.2.0", META, b"artifact"),
        )
        for operation in operations:
            with self.subTest(operation=operation), self.assertRaises(ZedApiError) as context:
                operation()
            self.assertEqual(context.exception.code, "missing_token")
        self.assertEqual(opener.calls, [])

    def test_blank_structured_error_code_uses_http_fallback(self):
        response = _FakeResponse({"code": "   ", "message": "remote"})
        opener = _CountingOpener(response)
        # _request sees a successful fake transport, so exercise the mapper through
        # the public helper's explicit behavior indirectly by importing it.
        from zed_pkg_client import _api_error

        class ErrorResponse(_FakeResponse):
            code = 409

        mapped = _api_error(ErrorResponse({"code": "   ", "message": "remote"}))
        self.assertEqual(mapped.code, "http_409")


class PublishAndDownloadHardeningTest(unittest.TestCase):
    def test_publish_rejects_oversize_and_coordinate_mismatch_before_transport(self):
        opener = _CountingOpener()
        client = ZedClient("https://registry.test", token="token", opener=opener)
        with self.assertRaises(ZedApiError) as oversized:
            client.publish("acme", "kit", "1.2.0", META, _HugeArtifact())
        self.assertEqual(oversized.exception.code, "artifact_too_large")

        wrong = {
            "manifest": {
                "package": {
                    "org": "other",
                    "name": "kit",
                    "version": "1.2.0",
                }
            }
        }
        with self.assertRaises(ZedApiError) as mismatch:
            client.publish("acme", "kit", "1.2.0", wrong, b"artifact")
        self.assertEqual(mismatch.exception.code, "publish_coordinate_mismatch")
        self.assertEqual(opener.calls, [])

    def test_verified_download_atomically_replaces_destination(self):
        body = b"verified artifact"
        opener = _CountingOpener(_FakeResponse(body))
        client = ZedClient("https://registry.test", opener=opener)
        with tempfile.TemporaryDirectory() as directory:
            destination = os.path.join(directory, "nested", "artifact.tar.gz")
            os.makedirs(os.path.dirname(destination))
            with open(destination, "wb") as handle:
                handle.write(b"old")
            client.download_artifact(_version(body), destination)
            with open(destination, "rb") as handle:
                self.assertEqual(handle.read(), body)
            leftovers = [name for name in os.listdir(os.path.dirname(destination)) if name.startswith(".zed-artifact-")]
            self.assertEqual(leftovers, [])


if __name__ == "__main__":
    unittest.main()
