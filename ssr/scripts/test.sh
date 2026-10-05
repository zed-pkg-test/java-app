#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
oreslang_bin="${ORESLANG_BIN:-oreslang}"

bash "$root/scripts/audit.sh"
bash "$root/scripts/check.sh"

expected="$(cat "$root/tests/render-contract.expected")"
actual="$("$oreslang_bin" "$root/tests/render-contract.ores")"

if [[ "$actual" != "$expected" ]]; then
  echo "render contract mismatch" >&2
  diff -u <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") >&2 || true
  exit 1
fi

echo "test: render contract passed"
