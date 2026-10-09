#!/usr/bin/env python3
"""Fetch and build the pinned Oreslang reference compiler without editing std/rx."""
from pathlib import Path
import subprocess
import shlex

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / '.work' / 'oreslang-source.java'
REVISION = (ROOT / 'compiler.lock').read_text().strip()
SOURCE.parent.mkdir(exist_ok=True)
if not SOURCE.exists():
    subprocess.run(['git', 'clone', '--no-checkout', '--depth', '1', 'https://github.com/ores-truffle-oreslang/oreslang-source.java.git',
                    str(SOURCE)], check=True)
subprocess.run(['git', '-C', str(SOURCE), 'fetch', '--depth', '1', 'origin', REVISION], check=True)
subprocess.run(['git', '-C', str(SOURCE), 'checkout', '--detach', REVISION], check=True)
subprocess.run(['mvn', '-q', '-f', str(SOURCE / 'pom.xml'), '-DskipTests', 'package'], check=True)
classpath_file = ROOT / '.work' / 'classpath.txt'
subprocess.run(['mvn', '-q', '-f', str(SOURCE / 'pom.xml'), 'dependency:build-classpath',
                f'-Dmdep.outputFile={classpath_file}'], check=True)
classpath = str(SOURCE / 'target/classes') + ':' + classpath_file.read_text().strip()
(ROOT / '.work' / 'env.sh').write_text('export ORES_CLASSPATH=' + shlex.quote(classpath) + '\n')
print('Compiler ready. Run: source .work/env.sh && python3 scripts/test.py')
