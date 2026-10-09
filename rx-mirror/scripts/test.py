#!/usr/bin/env python3
"""Check and run real .ores programs, with bounded execution and exact output."""
import os
import re
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = os.environ.get('JAVA', 'java')
CLASSPATH = os.environ.get('ORES_CLASSPATH')
if not CLASSPATH:
    sys.exit('Set ORES_CLASSPATH to the reference compiler classes and dependencies (see README).')
COMMAND = [JAVA, '--enable-native-access=ALL-UNNAMED',
           '-Dpolyglot.engine.WarnInterpreterOnly=false', '-cp', CLASSPATH,
           'dev.oreslang.launcher.OresMain']

POINTER_STYLE = (
    (re.compile(r'Fnc\s*<\s*&'), 'borrow marker inside Fnc<...>'),
    (re.compile(r'&mut\b'), 'pointer-style &mut'),
    (re.compile(r'(^|[\(\[,=])\s*&\s*[A-Za-z_(]', re.MULTILINE),
     'unary address-of style &value'),
    (re.compile(r'\b(?:[A-Z][A-Za-z0-9_]*(?:<[^>\n]+>)?|int|bool|string|String)\s*\*\s+[A-Za-z_]'),
     'pointer-style T* declaration'),
)

def assert_pointerless():
    for directory in ('src', 'tests', 'bench', 'examples'):
        root = ROOT / directory
        for path in sorted(root.glob('*.ores')):
            text = path.read_text()
            for pattern, label in POINTER_STYLE:
                match = pattern.search(text)
                if match:
                    line = text.count('\n', 0, match.start()) + 1
                    sys.exit(f'{path}:{line}: forbidden {label}; use pointerless rt ownership semantics')

def run(args):
    result = subprocess.run(COMMAND + args, text=True, capture_output=True, timeout=30)
    if result.returncode or ': error:' in result.stderr:
        sys.exit(result.stdout + result.stderr)
    return result.stdout.strip()

assert_pointerless()
run(['--check', str(ROOT / 'src/rx.ores')])
for example in sorted((ROOT / 'examples').glob('*.ores')):
    run(['--check', str(example)])
for path in sorted((ROOT / 'tests').glob('*.ores')):
    check_error_file = path.with_suffix('.check.err')
    if check_error_file.exists():
        result = subprocess.run(COMMAND + ['--check', str(path)], text=True, capture_output=True, timeout=30)
        expected_check_error = check_error_file.read_text().strip()
        if result.returncode == 0 or expected_check_error not in result.stderr:
            sys.exit(f'{path.name}: expected check failure containing {expected_check_error!r}\n'
                     + result.stdout + result.stderr)
        print(f'PASS {path.stem} (expected check failure)')
        continue

    run(['--check', str(path)])
    error_file = path.with_suffix('.err')
    if error_file.exists():
        result = subprocess.run(COMMAND + [str(path)], text=True, capture_output=True, timeout=30)
        if result.returncode == 0 or error_file.read_text().strip() not in result.stderr:
            sys.exit(f'{path.name}: expected failure containing {error_file.read_text().strip()!r}\n'
                     + result.stdout + result.stderr)
        print(f'PASS {path.stem} (expected failure)')
        continue
    actual = run([str(path)])
    expected = path.with_suffix('.out').read_text().strip()
    if actual != expected:
        sys.exit(f'{path.name}: expected {expected!r}, got {actual!r}')
    print(f'PASS {path.stem}')
