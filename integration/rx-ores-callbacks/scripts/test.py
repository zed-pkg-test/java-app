#!/usr/bin/env python3
"""Check and run callback-only rx programs with the pinned compiler."""
import os
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

def run(args):
    result = subprocess.run(COMMAND + args, text=True, capture_output=True, timeout=30)
    if result.returncode or ': error:' in result.stderr:
        sys.exit(result.stdout + result.stderr)
    return result.stdout.strip()

run(['--check', str(ROOT / 'src/rx.ores')])
for path in sorted((ROOT / 'tests').glob('*.ores')):
    run(['--check', str(path)])
    actual = run([str(path)])
    expected = path.with_suffix('.out').read_text().strip()
    if actual != expected:
        sys.exit(f'{path.name}: expected {expected!r}, got {actual!r}')
    print(f'PASS {path.stem}')
