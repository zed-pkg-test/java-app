#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_file="$repo_root/src/http_routing.ores"
source_ref_file="$repo_root/SOURCE_REF"

if [[ ! -f "$source_file" ]]; then
  echo "missing router source: $source_file" >&2
  exit 66
fi

module_count="$(grep -Ec '^[[:space:]]*define module http_routing as[[:space:]]*$' "$source_file" || true)"
if [[ "$module_count" != "1" ]]; then
  echo "expected exactly one 'define module http_routing as' declaration" >&2
  exit 65
fi

if grep -Eq '^[[:space:]]*define module http_routing[[:space:]]*$' "$source_file"; then
  echo "module declaration must include the 'as' keyword" >&2
  exit 65
fi

# Class methods returning void use the preferred ': void' spelling.
if ! awk '
  /^[[:space:]]*define class[[:space:]]/ { in_class = 1; next }
  in_class && /^[[:space:]]*end[[:space:]]*$/ { in_class = 0; next }
  in_class && /->[[:space:]]*void/ {
    print "class method uses non-preferred -> void syntax at line " NR ": " $0 > "/dev/stderr"
    bad = 1
  }
  END { exit bad ? 1 : 0 }
' "$source_file"; then
  exit 65
fi

expected_ref="$(tr -d '[:space:]' < "$source_ref_file")"
if [[ ! "$expected_ref" =~ ^[0-9a-f]{40}$ ]]; then
  echo "SOURCE_REF must contain exactly one lowercase 40-character git SHA" >&2
  exit 65
fi

echo "router static audit: PASS"
