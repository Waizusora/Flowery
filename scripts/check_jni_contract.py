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


def verify_usb_close_return_type() -> bool:
    kotlin_source = (ROOT / "app/src/main/java/com/waizusora/flowery/NativeAudio.kt").read_text(encoding="utf-8")
    kotlin_match = re.search(
        r"external\\s+fun\\s+nativeCloseUsbAudioSession\\s*\\(\\s*handle:\\s*Long\\s*\\)\\s*:\\s*(\\w+)",
        kotlin_source,
    )
    rust_sources = sorted((ROOT / "native" / "src").rglob("*.rs"))
    rust_source = "\\n".join(path.read_text(encoding="utf-8") for path in rust_sources)
    rust_match = re.search(
        r"fn\\s+Java_com_waizusora_flowery_NativeAudio_nativeCloseUsbAudioSession\\s*\\([\\s\\S]*?\\)\\s*->\\s*([A-Za-z0-9_:]+)",
        rust_source,
    )
    if kotlin_match is None or rust_match is None:
        print("USB close JNI return contract could not be resolved", file=sys.stderr)
        return False
    if kotlin_match.group(1) != "Int" or rust_match.group(1) != "jint":
        print(
            "USB close JNI return contract mismatch: expected Kotlin Int and Rust jint",
            file=sys.stderr,
        )
        return False
    print("USB close JNI return contract matches (Kotlin Int / Rust jint)")
    return True


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
ok = verify_usb_close_return_type() and ok

raise SystemExit(0 if ok else 1)
