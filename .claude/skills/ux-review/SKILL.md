---
name: ux-review
description: Review Ratatoskr Android UI changes against the checked-in UX design (docs/ux-design.html) and Material 3. Use when reviewing a diff, PR, or screen for UX/design drift, when someone asks "does this match the design", or before merging UI work. Complements the deterministic CI checks (screenshot golden tests, lint) -- this skill covers the judgment calls those cannot.
---

# Ratatoskr UX review

The single source of truth for what "UX-compliant" means in this app. Both an
interactive session and the `ux-review` subagent load this; keep the rules here, not
duplicated in the agent.

## Sources of truth (read these first)

1. `docs/ux-design.html` -- the v1 UX design: principles, per-screen decisions, tokens,
   states, the knot loader, and the implementation/localization plan. **This is the
   contract.** When a screen's implementation disagrees with it, that is the drift.
2. `docs/SPEC.md` section 12 (tech stack) and section 13 (screen/module structure) -- architectural
   guardrails. A UI change that needs a new screen/module or a stack change is a SPEC
   change first, not a review nit.
3. `app/src/main/java/io/github/xexanos/ratatoskr/ui/theme/` -- `Color.kt`, `Type.kt`,
   `Theme.kt`. Every color/typography value must resolve to these, never to inline literals.
4. The `android-skills:android-ux` skill (if available) -- its M3 compliance audit is the
   checklist below in more detail. Load it for the full 10-category audit.

## What this review is, and is not

- **In scope:** judgment calls a machine cannot make -- is there a designed empty/error/
  loading state? Is the primary action in the thumb zone? Is copper reserved for exactly
  one primary action per screen? Does copy match the conventions (English source, generic
  examples, no walk-back-able promises)? Does the screen match its section in the design doc?
- **Out of scope (leave to deterministic checks):** hardcoded-color/shape detection, M2
  imports, pixel drift -- those are enforced by lint/detekt + screenshot golden tests in CI
  and should already be green before a human or this skill looks. If they are not wired up
  yet, say so; do not hand-audit what a grep can guarantee.

## Procedure

1. **Scope the diff.** `git diff --stat` against the base branch; list the changed
   `ui/**` files and which of the six screens (connect, auth, library, speakers,
   nowplaying, settings) each belongs to.
2. **Run the cheap deterministic greps** (report hits as findings, do not fix here):
   ```bash
   UI=app/src/main/java/io/github/xexanos/ratatoskr/ui
   # -g '*.kt' (not --type kt: some rg builds lack the type) and glob-exclude theme/,
   # which is the one legitimate home for Color(0x...) literals. Globs match Windows paths;
   # a piped `grep -v /theme/` does NOT (backslash separators).
   rg -n -g '*.kt' -g '!**/theme/**' 'Color\(0x[0-9a-fA-F]{6,8}\)' "$UI"
   rg -n -g '*.kt' 'RoundedCornerShape\(\s*[0-9.]+\s*\.dp\s*\)' "$UI"
   rg -n -g '*.kt' 'import androidx\.compose\.material\.[^3]' "$UI"
   rg -n -g '*.kt' '"[A-Za-z].{6,}"' "$UI"   # candidate hardcoded UI strings (should be in strings.xml)
   ```
3. **Per changed screen, check against its design-doc section:**
   - **States:** are loading, empty, and error all handled and designed (not a bare
     spinner / blank / toast)? Every empty state must name whose state it mirrors (server /
     network / search) and offer the next action (retry, adjust search).
   - **Hierarchy & thumb zone:** primary action in the bottom third; exactly one copper
     (`primary`) action per screen; destructive actions are not equal-weight buttons.
   - **Tokens:** colors/type/shape resolve to `MaterialTheme.*` / the theme files. Numbers
     (positions, durations, fingerprints) are tabular/monospace.
   - **Touch targets** >= 48 dp; composite rows merged for TalkBack; section titles marked
     as headings; meaningful icons have `contentDescription`.
   - **Copy** (see conventions below).
   - **Motion:** felt waits (cert inspect, session start, first library load) use the knot
     loader; short inline waits use the standard M3 indicator; reduced motion respected.
4. **Check the screen-specific decisions** listed in that screen's card in the design doc
   (e.g. Library must carry the mini player off the existing session polling; Now playing
   header leads with "PLAYING ON <speaker>"; Speakers shows the resume position). A missing
   or altered decision is drift -- flag it with the doc section it violates.
5. **Report** most-severe-first: `file:line -- what drifted -- which design-doc/SPEC rule --
   suggested fix`. Separate hard violations (token/a11y/state) from judgment notes.

## Copy conventions (repo: docs are English; see the copy-and-example convention)

- UI strings live in `strings.xml`, never hardcoded in composables; English is the source
  language, German is the first added locale; layouts must survive +30% text.
- Examples are generic and English (user "alex", well-known English audiobooks, English
  room names, neutral hostnames like `ratatoskr.home.arpa`).
- **Never advertise a behavior that might be walked back.** Promise outcomes ("You'll stay
  signed in"), not implementation details ("your password is never stored").
- Orient playback controls on the official Audiobookshelf app (chapter-first progress,
  +/-10 s default jumps that later become a client setting).

## The bigger picture: drift is stopped by CI, judged by this skill

This skill catches what needs a human-like read. The actual *enforcement* that prevents
drift is deterministic and belongs in CI, run on the existing `@Preview` composables:

- **Screenshot golden tests** (Roborazzi or Paparazzi) over the `@Preview`s -- CI fails on
  pixel drift. This is the real anti-drift mechanism.
- **Lint / detekt rules** for the grep checks above (no hardcoded `Color(0x...)`, no
  `RoundedCornerShape(n.dp)`, no Material-2 imports, no hardcoded UI strings).

If those are not yet configured, recommending/adding them is higher-leverage than any
amount of manual review. Note their absence in the review output.
