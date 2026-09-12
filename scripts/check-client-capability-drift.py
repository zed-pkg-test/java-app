#!/usr/bin/env python3
"""Fail closed when the documented SDK matrix drifts from reviewed code."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"

EXPECTED_CLIENTS = {
    "rust",
    "wasm",
    "typescript",
    "python",
    "go",
    "dart",
    "gleam",
    "erlang",
    "elixir",
    "java",
    "kotlin",
    "ruby",
    "php",
    "swift",
}

EXPECTED_TYPESCRIPT_RUNTIMES = {"nodejs", "deno", "bun", "edge"}

HISTORICALLY_STALE_YANK_MARKERS = {
    "typescript": (ROOT / "clients/typescript/src/client.ts", "yank("),
    "python": (ROOT / "clients/python/zed_pkg_client/__init__.py", "def yank("),
    "go": (ROOT / "clients/go/client.go", "func (c *Client) Yank("),
    "rust": (ROOT / "clients/rust/src/lib.rs", "pub fn yank("),
    "elixir": (ROOT / "clients/elixir/lib/zed_pkg_client.ex", "def yank("),
    "kotlin": (ROOT / "clients/kotlin/src/main/kotlin/tech/zpkg/client/ZedClient.kt", "fun yankJson("),
    "ruby": (ROOT / "clients/ruby/lib/zed_pkg_client.rb", "def yank("),
    "php": (ROOT / "clients/php/src/Client.php", "function yank("),
}


def fail(message: str) -> None:
    raise SystemExit(f"client capability drift: {message}")


def main() -> None:
    readme = README.read_text(encoding="utf-8")
    documented = re.findall(r"\[clients/([a-z0-9-]+)/\]\(clients/\1/\)", readme)
    documented_set = set(documented)

    if len(documented) != len(documented_set):
        fail(f"duplicate client table rows: {documented}")
    if documented_set != EXPECTED_CLIENTS:
        missing = sorted(EXPECTED_CLIENTS - documented_set)
        extra = sorted(documented_set - EXPECTED_CLIENTS)
        fail(f"README matrix mismatch; missing={missing}, extra={extra}")

    required_claim = "All fourteen clients\nimplement that core lifecycle."
    if required_claim not in readme:
        fail("README must state that all fourteen clients implement the core lifecycle")

    retired_claims = (
        "Completion of that operation in the older",
        "remains tracked by the fleet-level parity issue",
    )
    for claim in retired_claims:
        if claim in readme:
            fail(f"retired incomplete-parity claim returned: {claim!r}")

    for client, (path, marker) in HISTORICALLY_STALE_YANK_MARKERS.items():
        if not path.is_file():
            fail(f"{client} source is missing: {path.relative_to(ROOT)}")
        if marker not in path.read_text(encoding="utf-8"):
            fail(f"{client} lost yank/restore marker {marker!r}")

    package_json = json.loads((ROOT / "clients/typescript/package.json").read_text(encoding="utf-8"))
    exports = package_json.get("exports", {})
    for runtime in EXPECTED_TYPESCRIPT_RUNTIMES:
        path = ROOT / "clients/typescript/src/runtimes" / runtime / "index.ts"
        if not path.is_file():
            fail(f"TypeScript runtime entry point is missing: {path.relative_to(ROOT)}")
        if f"./{runtime}" not in exports:
            fail(f"package.json export is missing for TypeScript runtime {runtime}")

    print("zed-clients README, runtime exports, and implemented core lifecycle are aligned")


if __name__ == "__main__":
    main()
