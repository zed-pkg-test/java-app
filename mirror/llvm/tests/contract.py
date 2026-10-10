#!/usr/bin/env python3
"""CTest harness: verify safe rejection, verified IR, and optional native execution."""
import argparse
import pathlib
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--compiler", required=True)
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--ir")
    parser.add_argument("--diagnostic")
    parser.add_argument("--run", type=int)
    parser.add_argument("--clang")
    args = parser.parse_args()
    compile_result = subprocess.run(
        [args.compiler, args.fixture], capture_output=True, text=True, check=False, timeout=20
    )
    if args.diagnostic:
        assert compile_result.returncode != 0, "invalid source unexpectedly accepted"
        assert args.diagnostic in compile_result.stderr, compile_result.stderr
        assert not compile_result.stdout.strip(), "emitted IR after rejection"
        return
    assert compile_result.returncode == 0, compile_result.stderr
    assert compile_result.stdout.startswith("; ModuleID"), compile_result.stdout
    assert not compile_result.stderr, compile_result.stderr
    if args.ir:
        assert args.ir in compile_result.stdout, compile_result.stdout
    if args.run is None:
        return
    assert args.clang, "--run requires a clang executable"
    with tempfile.TemporaryDirectory(prefix="oreslang-llvm-test-") as directory:
        executable = str(pathlib.Path(directory) / "guest")
        native = subprocess.run(
            [args.clang, "-x", "ir", "-", "-o", executable],
            input=compile_result.stdout, capture_output=True, text=True,
            check=False, timeout=30
        )
        assert native.returncode == 0, native.stderr
        result = subprocess.run(
            [executable], capture_output=True, text=True, check=False, timeout=10
        )
        # The native platform truncates main's i64 return to its process exit code.
        assert result.returncode == args.run, (
            f"native exit {result.returncode}, expected {args.run}: {result.stderr}"
        )


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, subprocess.TimeoutExpired) as error:
        print(f"contract test failed: {error}", file=sys.stderr)
        sys.exit(1)
