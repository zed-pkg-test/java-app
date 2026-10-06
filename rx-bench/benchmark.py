#!/usr/bin/env python3
from __future__ import annotations
import os, statistics, subprocess, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ITERATIONS = 20_000
RAW_ITEMS = 5
EXPECTED = {"pull":"60000","callbacks":"100000","channels":"60000"}
PROGRAMS = {
    "pull": ROOT / "pull/bench/pipeline.ores",
    "callbacks": ROOT / "callbacks/bench/pipeline.ores",
    "channels": ROOT / "channels/bench/pipeline.ores",
}
JAVA = os.environ.get("JAVA","java")
CP = os.environ["ORES_CLASSPATH"]
CMD=[JAVA,"--enable-native-access=ALL-UNNAMED","-Dpolyglot.engine.WarnInterpreterOnly=false","-cp",CP,"dev.oreslang.launcher.OresMain"]

def once(program: Path, expected: str) -> float:
    t=time.perf_counter()
    p=subprocess.run(CMD+[str(program)],capture_output=True,text=True,check=False)
    dt=time.perf_counter()-t
    if p.returncode:
        raise SystemExit(p.stdout+p.stderr)
    if p.stdout.strip()!=expected:
        raise SystemExit(f"{program}: expected {expected}, got {p.stdout.strip()!r}")
    return dt

for name,program in PROGRAMS.items():
    samples=[once(program,EXPECTED[name]) for _ in range(7)]
    med=statistics.median(samples)
    ordered=sorted(samples)
    p95=ordered[-1]
    print(f"{name} median_ms={med*1000:.3f} p95_ms={p95*1000:.3f} pipelines_s={ITERATIONS/med:.1f} ns_source_item={med*1e9/(ITERATIONS*RAW_ITEMS):.1f}")
