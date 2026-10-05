#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
oreslang_bin="${ORESLANG_BIN:-oreslang}"

for source in "$root"/examples/*.ores "$root"/tests/*.ores; do
  echo "==> check ${source#$root/}"
  "$oreslang_bin" --check "$source"
done
