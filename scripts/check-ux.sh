#!/usr/bin/env bash
#
# UX design guardrails — the deterministic checks the ux-review skill mandates
# (.claude/skills/ux-review). These are the "judged by the skill, stopped by CI" floor:
# they catch drift that never needs a human eye. Run locally with `bash scripts/check-ux.sh`;
# CI runs it on every push/PR.
#
# All four checks are fatal. STRICT_STRINGS stays configurable only so a work-in-progress
# branch can temporarily downgrade check 4 with STRICT_STRINGS=0; it defaults to on.
#
set -uo pipefail

UI="app/src/main/java/io/github/xexanos/ratatoskr/ui"
STRICT_STRINGS="${STRICT_STRINGS:-1}"
fail=0

echo "== 1. Hardcoded colors outside theme/ (use MaterialTheme.colorScheme) =="
if grep -rEn --include='*.kt' 'Color\(0x[0-9a-fA-F]{6,8}\)' "$UI" | grep -v '/theme/'; then
  echo "  FAIL: use theme tokens, not literal Color(0x…)."; fail=1
else echo "  ok"; fi

echo "== 2. Hardcoded corner radii in dp (use MaterialTheme.shapes) =="
if grep -rEn --include='*.kt' 'RoundedCornerShape\(\s*[0-9.]+\s*\.dp\s*\)' "$UI"; then
  echo "  FAIL: use MaterialTheme.shapes, not RoundedCornerShape(n.dp). (RoundedCornerShape(50) percent pills are fine.)"; fail=1
else echo "  ok"; fi

echo "== 3. Material-2 imports (use androidx.compose.material3) =="
if grep -rEn --include='*.kt' 'import androidx\.compose\.material\.[^3]' "$UI"; then
  echo "  FAIL: Material 2 import found; use material3."; fail=1
else echo "  ok"; fi

echo "== 4. Hardcoded user-facing strings (use stringResource) =="
# Heuristic: common Compose call sites carrying a letter-bearing literal, minus lines that
# already resolve a resource. Preview/tag/route constants may slip through; the goal is to
# catch load-bearing copy left inline.
hits="$(grep -rEn --include='*.kt' '(Text|OutlinedButton|Button|label|placeholder|contentDescription)[[:space:]]*[=(][[:space:]]*"[^"]*[A-Za-z][^"]*"' "$UI" | grep -v 'stringResource' || true)"
if [ -n "$hits" ]; then
  echo "$hits"
  if [ "$STRICT_STRINGS" = "1" ]; then
    echo "  FAIL: move UI copy into strings.xml and use stringResource()."; fail=1
  else
    echo "  warn: not yet fatal (set STRICT_STRINGS=1 once Phase 2 lands)."
  fi
else echo "  ok"; fi

if [ "$fail" -ne 0 ]; then
  echo; echo "UX guardrails failed."; exit 1
fi
echo; echo "UX guardrails passed."
