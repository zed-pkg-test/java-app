#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

fail() {
  echo "audit: $*" >&2
  exit 1
}

if find . -type f -name '*.java' -print -quit | grep -q .; then
  fail "Java source is forbidden in the SSR repository"
fi

if grep -R -nE --include='*.ores' 'java:|(^|[[:space:]])java[[:space:]]*\{|do[[:space:]]+java[[:space:]]*\{' src examples tests 2>/dev/null; then
  fail "Java interop/source islands are forbidden in native SSR code"
fi

if grep -R -n --include='*.ores' 'DynamicStruct' src examples tests 2>/dev/null; then
  fail "DynamicStruct is not part of the SSR representation model"
fi

# New code follows the canonical module declaration even while validation
# temporarily runs against a compatibility parser.
if grep -R -nE --include='*.ores' '^[[:space:]]*define module [A-Za-z_][A-Za-z0-9_]*[[:space:]]*$' src examples tests 2>/dev/null; then
  fail "module declarations must use 'define module <name> as'"
fi

# Until #246 lands, safe-looking dynamic string conversion APIs are forbidden.
if grep -R -nE --include='*.ores' 'pub fnc (text|attr|element|void_element|bool_attr)\(' src 2>/dev/null; then
  fail "dynamic string boundaries must remain explicitly trusted until #246"
fi

if grep -R -nE --include='*.ores' 'pub fnc (style|script)\(' src 2>/dev/null; then
  fail "style/script contexts must remain explicitly trusted"
fi

echo "audit: native SSR invariants passed"
