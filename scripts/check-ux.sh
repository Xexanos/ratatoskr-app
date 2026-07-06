#!/usr/bin/env bash
#
# UX design guardrails - the deterministic checks the ux-review skill mandates
# (.claude/skills/ux-review). The "judged by the skill, stopped by CI" floor: they catch
# drift that never needs a human eye. Run locally with `bash scripts/check-ux.sh`; CI runs
# it on every push/PR.
#
# All five rules are FATAL: the codebase was migrated to theme tokens (colors + shapes),
# strings.xml, Material icons, and ASCII-only source. The STRICT_* env vars remain only so a
# work-in-progress branch can temporarily downgrade a rule (e.g. STRICT_STRINGS=0) mid-refactor.
#
set -uo pipefail

UI="app/src/main/java/io/github/xexanos/ratatoskr/ui"
STRICT_SHAPES="${STRICT_SHAPES:-1}"
STRICT_STRINGS="${STRICT_STRINGS:-1}"
STRICT_ASCII="${STRICT_ASCII:-1}"
fail=0

echo "== 1. Hardcoded colors outside theme/ (use MaterialTheme.colorScheme) [FATAL] =="
if grep -rEn --include='*.kt' 'Color\(0x[0-9a-fA-F]{6,8}\)' "$UI" | grep -v '/theme/'; then
  echo "  FAIL: use theme tokens, not literal Color(0x...)."; fail=1
else echo "  ok"; fi

echo "== 2. Hardcoded corner radii in dp (prefer MaterialTheme.shapes) [$( [ "$STRICT_SHAPES" = 1 ] && echo FATAL || echo advisory )] =="
if grep -rEn --include='*.kt' 'RoundedCornerShape\(\s*[0-9.]+\s*\.dp\s*\)' "$UI" | grep -v '/theme/'; then
  if [ "$STRICT_SHAPES" = 1 ]; then echo "  FAIL: use MaterialTheme.shapes, not RoundedCornerShape(n.dp)."; fail=1
  else echo "  warn: migrate to MaterialTheme.shapes (RoundedCornerShape(50) percent pills are fine)."; fi
else echo "  ok"; fi

echo "== 3. Material-2 component imports (use androidx.compose.material3) [FATAL] =="
# material.icons.* is the correct icon package for Material 3 - only real M2 components count.
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

echo "== 5. Non-ASCII characters (English code is ASCII; non-ASCII belongs in docs/translations) [$( [ "$STRICT_ASCII" = 1 ] && echo FATAL || echo advisory )] =="
# Scope: Kotlin + Gradle/shell/python + the base strings.xml + repo config. UTF-8 (non-ASCII)
# is allowed in prose docs (*.md, so proper nouns like author names survive), localized
# resources (values-*/), fastlane locale metadata, the rendered design doc, and SVGs; the
# vendored gradlew is also skipped. lint_ascii.py flags any character > U+007F.
PY="$(command -v python3 || command -v python || true)"
ascii_files="$(git ls-files '*.kt' '*.kts' '*.sh' '*.py' 'app/src/main/res/values/strings.xml' '.gitignore' '.gitattributes' \
  | grep -vE '^(docs/ux-design\.html|gradlew)$|\.svg$|/values-|^fastlane/')"
ascii_hits="$([ -n "$PY" ] && echo "$ascii_files" | xargs "$PY" scripts/lint_ascii.py || true)"
if [ -n "$ascii_hits" ]; then
  echo "$ascii_hits"
  if [ "$STRICT_ASCII" = 1 ]; then echo "  FAIL: keep source ASCII (arrows -> '->', em-dash -> '-', ...); non-ASCII text belongs in a translation."; fail=1
  else echo "  warn: non-ASCII found; keep source ASCII."; fi
else echo "  ok"; fi

if [ "$fail" -ne 0 ]; then
  echo; echo "UX guardrails failed."; exit 1
fi
echo; echo "UX guardrails passed."
