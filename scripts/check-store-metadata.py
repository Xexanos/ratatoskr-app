#!/usr/bin/env python3
#
# F-Droid store metadata guardrails - the mechanical half of the conventions in
# fastlane/metadata/README.md. Run locally with `python3 scripts/check-store-metadata.py`;
# CI runs it from .github/workflows/pr-release-guards.yml.
#
# Checks only what a machine can decide. Tone, voice and wording stay a review matter.
#
# Why Python and not the shell: the 500 limit is 500 CHARACTERS, and byte-counting tools
# disagree with that on German entries - `wc -c`, and `wc -m` outside a UTF-8 locale, report
# 509 for the 499-character de-DE/10802. A byte-based gate would reject a valid entry.

import glob
import os
import sys

ROOT = "fastlane/metadata/android"
LIMIT = 500
NL = chr(10)
CR = chr(13)

# The locales kept in sync as a pair. F-Droid falls back to en-US for anything missing, so a
# further locale can be added here once it is actually maintained - until then a stray
# third-locale file is left alone rather than dragging the other locales along with it.
PAIRED_LOCALES = ("en-US", "de-DE")


def changelog_paths(locale):
    pattern = os.path.join(ROOT, locale, "changelogs", "*.txt")
    return sorted(p.replace(os.sep, "/") for p in glob.glob(pattern))


def check_file(path, problems):
    # newline="" keeps universal-newline mode from rewriting CRLF to LF, which would
    # make the CR check below unable to fire.
    with open(path, encoding="utf-8", newline="") as handle:
        text = handle.read()

    if not text.strip():
        problems.append("%s: empty" % path)
        return

    if len(text) > LIMIT:
        problems.append(
            "%s: %d characters, over the %d-character store limit"
            % (path, len(text), LIMIT)
        )

    if CR in text:
        problems.append("%s: contains CR; store metadata is LF-only" % path)

    if not text.endswith(NL):
        problems.append("%s: missing the trailing newline" % path)
    elif text.count(NL) != 1:
        problems.append(
            "%s: %d lines; an entry is one paragraph on one line"
            % (path, text.count(NL))
        )


def check_pairing(problems):
    codes = {}
    for locale in PAIRED_LOCALES:
        codes[locale] = set(
            os.path.basename(p) for p in changelog_paths(locale)
        )

    for locale in PAIRED_LOCALES:
        for other in PAIRED_LOCALES:
            if locale == other:
                continue
            for name in sorted(codes[locale] - codes[other]):
                problems.append(
                    "%s/%s/changelogs/%s has no %s translation"
                    % (ROOT, locale, name, other)
                )


def main():
    problems = []

    checked = 0
    for locale in PAIRED_LOCALES:
        for path in changelog_paths(locale):
            check_file(path, problems)
            checked += 1

    if checked == 0:
        problems.append("no changelog files found under %s - wrong directory?" % ROOT)

    check_pairing(problems)

    if problems:
        annotate = os.environ.get("GITHUB_ACTIONS") == "true"
        for problem in problems:
            print(("::error::" if annotate else "  FAIL: ") + problem)
        print()
        print(
            "Store metadata guardrails failed. See fastlane/metadata/README.md for the "
            "conventions these enforce."
        )
        return 1

    print("Store metadata guardrails passed (%d changelog entries)." % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
