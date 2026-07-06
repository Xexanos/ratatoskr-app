#!/usr/bin/env python3
"""Flag emoji / pictographic glyphs used as UI icons in Kotlin sources.

Range-based (catches *all* emoji + symbol pictographs), not a denylist — a denylist
misses glyphs (e.g. ⧉ ◉ 🔇) and rots as new emoji appear. Anything genuinely allowed
goes in ALLOWLIST, kept explicit and empty by default.

Usage:  python3 scripts/lint_emoji.py <dir>
Prints `path:line: text` for each offending line and exits 1 if any were found.
The bash gate decides whether that is fatal or advisory (STRICT_EMOJI).
"""
import sys
from pathlib import Path

# The lines we print contain the offending glyphs; force UTF-8 so a non-UTF-8 console
# (e.g. Windows cp1252) can't crash the linter and silently let a violation through.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):
    pass

# Pictographic / emoji / icon-like symbol blocks. Deliberately excludes General
# Punctuation (U+2000–206F) and Latin, so em-dash, curly quotes and umlauts never match.
RANGES = [
    (0x2190, 0x21FF),   # arrows
    (0x2300, 0x23FF),   # misc technical (⏸ ⏹ ⏭ …)
    (0x25A0, 0x25FF),   # geometric shapes (▶ ◉ …)
    (0x2600, 0x27BF),   # misc symbols + dingbats (♪ ✅ …)
    (0x2900, 0x29FF),   # supplemental arrows-B / misc math-B (⧉ …)
    (0x2B00, 0x2BFF),   # misc symbols and arrows (⭐ …)
    (0xFE00, 0xFE0F),   # variation selectors (emoji presentation)
    (0x1F000, 0x1FAFF), # emoji planes (📚 🔍 🔇 🎧 …)
]

# Glyphs that are explicitly permitted (none today). Add sparingly, with a reason.
ALLOWLIST: set[str] = set()


def is_flagged(ch: str) -> bool:
    if ch in ALLOWLIST:
        return False
    cp = ord(ch)
    return any(lo <= cp <= hi for lo, hi in RANGES)


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    hits = []
    for path in sorted(root.rglob("*.kt")):
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeDecodeError):
            continue
        for n, line in enumerate(lines, 1):
            if any(is_flagged(c) for c in line):
                hits.append(f"{path.as_posix()}:{n}: {line.strip()}")
    for h in hits:
        print(h)
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
