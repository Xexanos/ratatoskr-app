#!/usr/bin/env python3
"""Flag emoji / pictographic glyphs used as UI icons in Kotlin sources.

Rule: any character from U+2190 upward — arrows, symbols, dingbats and the emoji
planes — is treated as an icon glyph and rejected. A single threshold instead of a
list of Unicode blocks, so there are no gaps as new emoji are assigned. The few
non-emoji symbols that legitimately appear in source (flow arrows and math relations
in comments/KDoc) are named explicitly in ALLOWLIST.

Why U+2190 as the cut and not lower: just below it live characters that are NOT icon
glyphs but would be noisy to flag — the © in every license header, ™, typographic
punctuation, accented Latin / CJK in sample data. The handful of true emoji that sit
below U+2190 (© ® ™ ‼ ⁉ ℹ) are not used as icons here, so the simple cut is worth it.

Usage:  python3 scripts/lint_emoji.py <dir>
Prints `path:line: text` for each offending line; exits 1 if any were found. The bash
gate decides whether that is fatal or advisory (STRICT_EMOJI).
"""
import sys
from pathlib import Path

# Force UTF-8 so a non-UTF-8 console (e.g. Windows cp1252) can't crash the linter
# printing an offending glyph and silently let a violation through.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):
    pass

THRESHOLD = 0x2190  # arrows and up == emoji / pictographic territory

# Non-emoji symbols above the threshold that are allowed to appear in source. These
# show up in comments/KDoc (flow arrows, math relations), never as UI glyphs. Keep the
# set short and add to it deliberately — a failing build is the prompt to review a new one.
ALLOWLIST = set("→←↑↓↔↕⇒⇐≤≥≠≈≡")


def is_flagged(ch: str) -> bool:
    return ord(ch) >= THRESHOLD and ch not in ALLOWLIST


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
