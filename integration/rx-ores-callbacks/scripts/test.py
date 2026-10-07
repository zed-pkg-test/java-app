#!/usr/bin/env python3
"""Check and run callback-only rx programs with the pinned compiler."""
import os
import re
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = os.environ.get('JAVA', 'java')
CLASSPATH = os.environ.get('ORES_CLASSPATH')
if not CLASSPATH:
    sys.exit('Set ORES_CLASSPATH (run scripts/setup.py first).')
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
    roots = [ROOT / 'src', ROOT / 'tests', ROOT / 'bench']
    for root in roots:
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
for path in sorted((ROOT / 'tests').glob('*.ores')):
    actual = run([str(path)])
    expected = path.with_suffix('.out').read_text().strip()
    if actual != expected:
        sys.exit(f'{path.name}: expected {expected!r}, got {actual!r}')
    print(f'PASS {path.stem}')
