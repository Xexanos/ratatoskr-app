// Commit types are load-bearing here: the version-bump guard (pr-release-guards.yml) classifies
// a PR's commits to decide whether a versionName/versionCode bump is REQUIRED - feat!/BREAKING
// CHANGE -> major, feat -> minor, fix/perf -> patch, anything else -> no bump required. CI
// enforces this shape on every PR commit (the "commitlint" job in the same workflow). Mirrors the
// server repo's convention so the two pipelines read commits the same way (SPEC section 8).
export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // The standard types plus this repo's established ones - all of the extras are
    // release-neutral (the guard only requires a bump for feat/fix/perf/breaking).
    'type-enum': [
      2,
      'always',
      [
        'build',
        'chore',
        'ci',
        'docs',
        'feat',
        'fix',
        'perf',
        'refactor',
        'revert',
        'style',
        'test',
        // repo-specific:
        'deps', // dependency bumps (dependabot's configured prefix); use fix(deps): when a runtime bump should ship a release
        'review', // addressing PR review feedback
        'spec', // docs/SPEC.md changes
        'comment', // comment-only corrections
      ],
    ],
    // These three style checks are disabled for ALL commits, not just Dependabot's - only the
    // commit TYPE is load-bearing (the guard and the release pipeline read nothing else), so
    // subject/header/body cosmetics are deliberately unenforced. Dependabot is what forces the
    // issue (it capitalizes subjects like "deps: Bump ..." and writes long changelog URLs and
    // scoped-package headers well past 100 chars), but the relaxation is global: a 120-char
    // header on a hand-written commit passes too. Kept off on purpose; revisit if noisy.
    'subject-case': [0],
    'body-max-line-length': [0],
    'header-max-length': [0],
  },
}
