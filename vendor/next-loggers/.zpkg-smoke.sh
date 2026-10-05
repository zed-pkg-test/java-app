#!/bin/sh
set -eu

test -f "$ZED_PKG_TEST_TARGET/.zpkg.toml"
test -f "$ZED_PKG_TEST_TARGET/Cargo.toml"
test -f "$ZED_PKG_TEST_TARGET/src/lib.rs"
grep -q 'name = "oresoftware-next-loggers"' "$ZED_PKG_TEST_TARGET/Cargo.toml"
