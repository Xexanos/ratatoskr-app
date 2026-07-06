#!/usr/bin/env python3
"""Fail on non-ASCII characters in the given files.

The repo is English throughout, so anything outside 7-bit ASCII is either an icon
glyph that belongs in a Material icon, or typography/text that belongs in a
translation. Translations, the rendered design doc and vendored files are kept out
of scope by the caller (see scripts/check-ux.sh), not here.

Usage:  python3 scripts/lint_ascii.py <file> [<file> ...]
Prints `path:line:col: U+XXXX 'c'  <line>` for the first non-ASCII char on each
offending line; exits 1 if any were found.
"""
import sys
from pathlib import Path

# Force UTF-8 so printing an offending glyph can't crash on a non-UTF-8 console
# (e.g. Windows cp1252) and silently let a violation through.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):
    pass


def main() -> int:
    hits = 0
    for arg in sys.argv[1:]:
        path = Path(arg)
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeDecodeError):
            continue
        for n, line in enumerate(lines, 1):
            for col, ch in enumerate(line, 1):
                if ord(ch) > 0x7F:
                    print(f"{path.as_posix()}:{n}:{col}: U+{ord(ch):04X} {ch!r}  {line.strip()}")
                    hits += 1
                    break
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
