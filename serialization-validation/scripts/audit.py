#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = [ROOT / "src", ROOT / "tests", ROOT / "examples"]
IMPORT_RE = re.compile(r"^\s*import\s+(class|fnc|interface|trait|struct|types)\s+(.+?)\s+from\s+['\"]([^'\"]+)['\"]\s*;\s*$")
DECL_PATTERNS = {
    "class": lambda name: re.compile(rf"\b(?:pub\s+define\s+class|define\s+(?:pub\s+)?class)\s+{re.escape(name)}\b"),
    "fnc": lambda name: re.compile(rf"\b(?:pub\s+)?(?:async\s+|nlex\s+|pure\s+)*fnc\s+{re.escape(name)}\b"),
    "interface": lambda name: re.compile(rf"\b(?:pub\s+define\s+interface|define\s+(?:pub\s+)?interface)\s+{re.escape(name)}\b"),
    "trait": lambda name: re.compile(rf"\b(?:pub\s+define\s+trait|define\s+(?:pub\s+)?trait)\s+{re.escape(name)}\b"),
    "struct": lambda name: re.compile(rf"\b(?:pub\s+define\s+struct|define\s+(?:pub\s+)?struct)\s+{re.escape(name)}\b"),
    "types": lambda name: re.compile(rf"\b(?:type|define\s+(?:pub\s+)?(?:class|interface|trait|struct))\s+{re.escape(name)}\b"),
}

def code_only(line: str) -> str:
    out = []
    quote = None
    escaped = False
    i = 0
    while i < len(line):
        ch = line[i]
        if quote is not None:
            if escaped: escaped = False
            elif ch == "\\": escaped = True
            elif ch == quote: quote = None
            out.append(" "); i += 1; continue
        if ch in ("'", '"', "`"):
            quote = ch; out.append(" "); i += 1; continue
        if ch == "/" and i + 1 < len(line) and line[i + 1] == "/": break
        out.append(ch); i += 1
    return "".join(out)

def source_files():
    for base in SOURCE_ROOTS:
        if base.exists(): yield from sorted(base.rglob("*.ores"))

def imported_names(raw: str):
    raw = raw.strip()
    if raw == "*": return []
    if raw.startswith("(") and raw.endswith(")"): raw = raw[1:-1]
    names = []
    for part in raw.split(","):
        token = part.strip()
        if not token: continue
        if " as " in token: token = token.split(" as ", 1)[0].strip()
        names.append(token)
    return names

def fail(errors, path, line_no, message):
    errors.append(f"{path.relative_to(ROOT)}:{line_no}: {message}")

def main() -> int:
    errors = []
    files = list(source_files())
    for path in files:
        for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            code = code_only(raw)
            if re.search(r"&\s*mut\b", code):
                fail(errors, path, line_no, "Rust-style &mut ownership syntax is forbidden")
            if re.search(r"(?:^|[=(,:;<\[]|\breturn\s+)\s*&\s*[A-Za-z_][A-Za-z0-9_]*\b", code):
                fail(errors, path, line_no, "pointer-style unary/type ampersand ownership syntax is forbidden; use rt borrow/take/copy/share")
            if re.search(r"\b[A-Z][A-Za-z0-9_<>,]*\*\s*[A-Za-z_][A-Za-z0-9_]*\b", code) or re.search(r"\b(?:int|float|bool|String|string)\s*\*\s*[A-Za-z_][A-Za-z0-9_]*\b", code):
                fail(errors, path, line_no, "C-style pointer declaration syntax is forbidden")
            if re.search(r"(?:^|[=(,:;])\s*\*[A-Za-z_][A-Za-z0-9_]*\b", code):
                fail(errors, path, line_no, "unary pointer dereference syntax is forbidden")
            if re.search(r"Channel\s*<[^>]*(?:Fnc|routine|->)", code):
                fail(errors, path, line_no, "callable/executable channel payload is forbidden")

            m = IMPORT_RE.match(raw)
            if not m: continue
            kind, raw_names, target = m.groups()
            if not target.startswith("."): continue
            target_path = (path.parent / target).resolve()
            if target_path.suffix != ".ores": target_path = target_path.with_suffix(".ores")
            try: target_path.relative_to(ROOT)
            except ValueError:
                fail(errors, path, line_no, f"relative import escapes repository: {target}"); continue
            if not target_path.is_file():
                fail(errors, path, line_no, f"relative import target does not exist: {target}"); continue
            target_text = target_path.read_text(encoding="utf-8")
            for name in imported_names(raw_names):
                pattern_factory = DECL_PATTERNS.get(kind)
                if pattern_factory is not None and not pattern_factory(name).search(target_text):
                    fail(errors, path, line_no, f"imported {kind} {name!r} is not declared in {target_path.relative_to(ROOT)}")

    if errors:
        print("static audit failed:", file=sys.stderr)
        for error in errors: print(f"  {error}", file=sys.stderr)
        return 1
    print(f"static audit passed: {len(files)} Oreslang source/test/example files")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
