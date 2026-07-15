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
    // Style rules relaxed for dependabot, which capitalizes subjects ("deps: Bump ...")
    // and writes long changelog URLs into bodies. The gate exists so the TYPE parses -
    // subject cosmetics are not what the release pipeline reads.
    'subject-case': [0],
    'body-max-line-length': [0],
    // Dependabot subjects (scoped package + path) routinely exceed the 100-char default.
    'header-max-length': [0],
  },
}
