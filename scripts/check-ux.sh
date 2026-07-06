#!/usr/bin/env bash
#
# UX design guardrails — the deterministic checks the ux-review skill mandates
# (.claude/skills/ux-review). The "judged by the skill, stopped by CI" floor: they catch
# drift that never needs a human eye. Run locally with `bash scripts/check-ux.sh`; CI runs
# it on every push/PR.
#
# Posture: this gate was introduced onto an existing codebase, so it enforces only what the
# code already satisfies and *reports* the rest, exactly as you'd adopt any linter:
#   - colors and real Material-2 component imports are FATAL (the code is already clean);
#   - corner radii, hardcoded strings, and emoji-as-icons are ADVISORY for now — the screens
#     predate these rules (custom radii like 20.dp have no M3 shape token; copy is not yet in
#     strings.xml; some empty states use emoji). Flip STRICT_SHAPES / STRICT_STRINGS /
#     STRICT_EMOJI to 1 per rule as the screens are migrated.
#
set -uo pipefail

UI="app/src/main/java/io/github/xexanos/ratatoskr/ui"
STRICT_SHAPES="${STRICT_SHAPES:-0}"
STRICT_STRINGS="${STRICT_STRINGS:-0}"
STRICT_EMOJI="${STRICT_EMOJI:-0}"
fail=0

echo "== 1. Hardcoded colors outside theme/ (use MaterialTheme.colorScheme) [FATAL] =="
if grep -rEn --include='*.kt' 'Color\(0x[0-9a-fA-F]{6,8}\)' "$UI" | grep -v '/theme/'; then
  echo "  FAIL: use theme tokens, not literal Color(0x…)."; fail=1
else echo "  ok"; fi

echo "== 2. Hardcoded corner radii in dp (prefer MaterialTheme.shapes) [$( [ "$STRICT_SHAPES" = 1 ] && echo FATAL || echo advisory )] =="
if grep -rEn --include='*.kt' 'RoundedCornerShape\(\s*[0-9.]+\s*\.dp\s*\)' "$UI"; then
  if [ "$STRICT_SHAPES" = 1 ]; then echo "  FAIL: use MaterialTheme.shapes, not RoundedCornerShape(n.dp)."; fail=1
  else echo "  warn: migrate to MaterialTheme.shapes (RoundedCornerShape(50) percent pills are fine)."; fi
else echo "  ok"; fi

echo "== 3. Material-2 component imports (use androidx.compose.material3) [FATAL] =="
# material.icons.* is the correct icon package for Material 3 — only real M2 components count.
if grep -rEn --include='*.kt' 'import androidx\.compose\.material\.' "$UI" | grep -v 'androidx\.compose\.material\.icons'; then
  echo "  FAIL: Material 2 component import found; use material3."; fail=1
else echo "  ok"; fi

echo "== 4. Hardcoded user-facing strings (use stringResource) [$( [ "$STRICT_STRINGS" = 1 ] && echo FATAL || echo advisory )] =="
hits="$(grep -rEn --include='*.kt' '(Text|OutlinedButton|Button|label|placeholder|contentDescription)[[:space:]]*[=(][[:space:]]*"[^"]*[A-Za-z][^"]*"' "$UI" | grep -v 'stringResource' || true)"
if [ -n "$hits" ]; then
  echo "$hits"
  if [ "$STRICT_STRINGS" = 1 ]; then echo "  FAIL: move UI copy into strings.xml and use stringResource()."; fail=1
  else echo "  warn: move UI copy into strings.xml (English source), then flip STRICT_STRINGS=1."; fi
else echo "  ok"; fi

echo "== 5. Emoji used as UI glyphs (use Material icons) [$( [ "$STRICT_EMOJI" = 1 ] && echo FATAL || echo advisory )] =="
# Fixed-string (byte-wise) match on a denylist of icon-like glyphs — locale-independent, so
# it behaves the same on git-bash and the CI runner, and never touches umlauts/em-dash/quotes.
emoji_hits="$(grep -rnF --include='*.kt' \
  -e '▶' -e '⏸' -e '⏹' -e '⏭' -e '⏮' -e '⏩' -e '⏪' -e '♪' -e '♫' -e '♬' \
  -e '🔀' -e '🔁' -e '🔊' -e '🔈' -e '🔇' -e '🎵' -e '🎶' -e '🎧' -e '📚' -e '📖' \
  -e '🔍' -e '✅' -e '❌' -e '⚠' -e '➕' -e '➖' -e '🔒' -e '🔑' -e '⚙' -e '🏠' -e '⭐' "$UI" || true)"
if [ -n "$emoji_hits" ]; then
  echo "$emoji_hits"
  if [ "$STRICT_EMOJI" = 1 ]; then echo "  FAIL: replace emoji with Material icons (Icon + contentDescription)."; fail=1
  else echo "  warn: replace emoji with Material icons; then flip STRICT_EMOJI=1."; fi
else echo "  ok"; fi

if [ "$fail" -ne 0 ]; then
  echo; echo "UX guardrails failed."; exit 1
fi
echo; echo "UX guardrails passed."
