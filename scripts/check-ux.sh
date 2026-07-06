#!/usr/bin/env bash
#
# UX design guardrails — the deterministic checks the ux-review skill mandates
# (.claude/skills/ux-review). The "judged by the skill, stopped by CI" floor: they catch
# drift that never needs a human eye. Run locally with `bash scripts/check-ux.sh`; CI runs
# it on every push/PR.
#
# All five rules are FATAL: the screens have been migrated to theme tokens (colors + shapes),
# strings.xml and Material icons, so the gate now holds the whole codebase to them. The
# STRICT_* env vars remain only so a work-in-progress branch can temporarily downgrade a rule
# (e.g. STRICT_STRINGS=0) while mid-refactor.
#
set -uo pipefail

UI="app/src/main/java/io/github/xexanos/ratatoskr/ui"
STRICT_SHAPES="${STRICT_SHAPES:-1}"
STRICT_STRINGS="${STRICT_STRINGS:-1}"
STRICT_EMOJI="${STRICT_EMOJI:-1}"
fail=0

echo "== 1. Hardcoded colors outside theme/ (use MaterialTheme.colorScheme) [FATAL] =="
if grep -rEn --include='*.kt' 'Color\(0x[0-9a-fA-F]{6,8}\)' "$UI" | grep -v '/theme/'; then
  echo "  FAIL: use theme tokens, not literal Color(0x…)."; fail=1
else echo "  ok"; fi

echo "== 2. Hardcoded corner radii in dp (prefer MaterialTheme.shapes) [$( [ "$STRICT_SHAPES" = 1 ] && echo FATAL || echo advisory )] =="
if grep -rEn --include='*.kt' 'RoundedCornerShape\(\s*[0-9.]+\s*\.dp\s*\)' "$UI" | grep -v '/theme/'; then
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
# Range-based all-emoji detection via scripts/lint_emoji.py (see there for the Unicode blocks
# and the explicit allowlist). Catches every emoji/pictograph — not a rot-prone denylist — and
# is locale-independent. Skips gracefully if no Python interpreter is available.
PY="$(command -v python3 || command -v python || true)"
emoji_hits="$([ -n "$PY" ] && "$PY" scripts/lint_emoji.py "$UI" || true)"
if [ -n "$emoji_hits" ]; then
  echo "$emoji_hits"
  if [ "$STRICT_EMOJI" = 1 ]; then echo "  FAIL: replace emoji with Material icons (Icon + contentDescription)."; fail=1
  else echo "  warn: replace emoji with Material icons; then flip STRICT_EMOJI=1."; fi
else echo "  ok"; fi

if [ "$fail" -ne 0 ]; then
  echo; echo "UX guardrails failed."; exit 1
fi
echo; echo "UX guardrails passed."
