#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
expected_ref="$(tr -d '[:space:]' < "$repo_root/SOURCE_REF")"
compiler="${ORESLANG_COMPILER:-oreslang-compiler}"

if ! command -v "$compiler" >/dev/null 2>&1 && [[ ! -x "$compiler" ]]; then
  echo "oreslang compiler not found: $compiler" >&2
  echo "set ORESLANG_COMPILER to an executable built from SOURCE_REF=$expected_ref" >&2
  exit 69
fi

if [[ -n "${ORESLANG_SOURCE_DIR:-}" ]]; then
  actual_ref="$(git -C "$ORESLANG_SOURCE_DIR" rev-parse HEAD)"
  if [[ "$actual_ref" != "$expected_ref" ]]; then
    echo "ORESLANG_SOURCE_DIR is at $actual_ref" >&2
    echo "expected SOURCE_REF $expected_ref" >&2
    exit 65
  fi
fi

entry="$repo_root/tests/router_semantics.ores"
actual="$(mktemp "${TMPDIR:-/tmp}/oreslang-http-routing.XXXXXX")"
trap 'rm -f "$actual"' EXIT

"$compiler" --check --platform=server "$entry"
"$compiler" --platform=server "$entry" > "$actual"

diff -u "$repo_root/tests/expected.txt" "$actual"
echo "router semantics: PASS"
