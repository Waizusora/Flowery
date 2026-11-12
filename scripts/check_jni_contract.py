#!/usr/bin/env python3
"""Verify that Kotlin JNI declarations match the native exported symbols."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def declarations(path: Path) -> set[str]:
    source = path.read_text(encoding="utf-8")
    return set(re.findall(r"external\s+fun\s+(native[A-Za-z0-9_]+)\s*\(", source))


def exports(paths: list[Path], pattern: str) -> set[str]:
    symbols: set[str] = set()
    for path in paths:
        source = path.read_text(encoding="utf-8")
        symbols.update(re.findall(pattern, source))
    return symbols


def verify(label: str, declared: set[str], exported: set[str]) -> bool:
    missing = sorted(declared - exported)
    stale = sorted(exported - declared)
    if not missing and not stale:
        print(f"{label}: {len(declared)} JNI declarations match native exports")
        return True
    if missing:
        print(f"{label}: missing native exports: {', '.join(missing)}", file=sys.stderr)
    if stale:
        print(f"{label}: native exports without Kotlin declarations: {', '.join(stale)}", file=sys.stderr)
    return False


rust_sources = sorted((ROOT / "native" / "src").rglob("*.rs"))
rust_symbols = exports(
    rust_sources,
    r"Java_com_waizusora_flowery_NativeAudio_(native[A-Za-z0-9_]+)",
)
ok = verify(
    "NativeAudio",
    declarations(ROOT / "app/src/main/java/com/waizusora/flowery/NativeAudio.kt"),
    rust_symbols,
)

raise SystemExit(0 if ok else 1)
