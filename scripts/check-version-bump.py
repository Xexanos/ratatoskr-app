#!/usr/bin/env python3
#
# Release guard: a PR carrying release-worthy commits must bump versionName/versionCode
# (SPEC section 8) and ship the matching en-US changelog, so the automated tag gate
# (promote.yml) has a fresh version to cut. CI runs it from
# .github/workflows/pr-release-guards.yml; run it locally against any two refs with
#
#   python3 scripts/check-version-bump.py origin/main HEAD
#
# The commit classification is deliberately identical to the server's promote.yml:
# only feat/fix/perf/breaking force a bump, and `revert` is release-neutral.

import os
import re
import subprocess
import sys

GRADLE = "app/build.gradle.kts"
CHANGELOG = "fastlane/metadata/android/en-US/changelogs/{code}.txt"

BREAKING_SUBJECT = re.compile(r"^[a-z]+(\([^)]*\))?!:")
BREAKING_BODY = re.compile(r"^BREAKING[ -]CHANGE:", re.MULTILINE)
FEAT = re.compile(r"^feat(\([^)]*\))?:")
FIX_OR_PERF = re.compile(r"^(fix|perf)(\([^)]*\))?:")
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


def fail(message):
    prefix = "::error::" if os.environ.get("GITHUB_ACTIONS") == "true" else "FAIL: "
    print(prefix + message)
    sys.exit(1)


def git(*args):
    result = subprocess.run(
        ["git"] + list(args),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.stdout if result.returncode == 0 else ""


def classify(base, head):
    """The semver level the PR's commits imply: major, minor, patch or none.

    `revert` is deliberately release-neutral, matching the server's classifier, so
    reverting a shipped feat/fix does not by itself require a bump - rare, and kept
    consistent across the two pipelines on purpose.
    """
    subjects = git("log", "--format=%s", base + ".." + head)
    bodies = git("log", "--format=%b", base + ".." + head)

    if any(BREAKING_SUBJECT.match(line) for line in subjects.splitlines()):
        return "major"
    if BREAKING_BODY.search(bodies):
        return "major"
    if any(FEAT.match(line) for line in subjects.splitlines()):
        return "minor"
    if any(FIX_OR_PERF.match(line) for line in subjects.splitlines()):
        return "patch"
    return "none"


def read_version(rev):
    """versionName/versionCode as declared at `rev`, or (None, None) when unreadable."""
    source = git("show", rev + ":" + GRADLE)
    name = code = None
    for line in source.splitlines():
        if name is None and re.match(r"^\s*versionName\s*=", line):
            found = re.search(r'versionName\s*=\s*"([^"]+)"', line)
            name = found.group(1) if found else None
        if code is None and re.match(r"^\s*versionCode\s*=", line):
            found = re.search(r"versionCode\s*=\s*(\d+)", line)
            code = int(found.group(1)) if found else None
    return name, code


def formula(name):
    """versionCode derived from versionName, per SPEC section 8."""
    major, minor, patch = (int(part) for part in name.split("."))
    return major * 10000 + minor * 100 + patch


def raise_to(name, level):
    major, minor, patch = (int(part) for part in name.split("."))
    if level == "major":
        return "%d.0.0" % (major + 1)
    if level == "minor":
        return "%d.%d.0" % (major, minor + 1)
    return "%d.%d.%d" % (major, minor, patch + 1)


def main(argv):
    if len(argv) == 3:
        base, head = argv[1], argv[2]
    else:
        base = os.environ.get("BASE_SHA", "")
        head = os.environ.get("HEAD_SHA", "")
        if not base or not head:
            print("usage: check-version-bump.py <base-ref> <head-ref>")
            print("       (or set BASE_SHA and HEAD_SHA)")
            return 2

    bump = classify(base, head)
    if bump == "none":
        print(
            "No release-worthy commits (feat/fix/perf/breaking) in this PR - "
            "a version bump is not required."
        )
        return 0

    head_vn, head_vc = read_version(head)
    base_vn, base_vc = read_version(base)
    if head_vn is None or head_vc is None:
        fail("Could not read versionName/versionCode from %s at head (%s)." % (GRADLE, head))
    if base_vn is None or base_vc is None:
        fail("Could not read versionName/versionCode from %s at base (%s)." % (GRADLE, base))

    for label, name in (("head", head_vn), ("base", base_vn)):
        if not SEMVER.match(name):
            fail(
                "versionName '%s' at %s is not MAJOR.MINOR.PATCH (SPEC section 8)."
                % (name, label)
            )

    want_vc = formula(head_vn)
    if head_vc != want_vc:
        fail(
            "versionCode %d does not match versionName %s (expected %d = "
            "MAJOR*10000+MINOR*100+PATCH, SPEC section 8)." % (head_vc, head_vn, want_vc)
        )

    expected_vn = raise_to(base_vn, bump)
    as_tuple = lambda name: tuple(int(part) for part in name.split("."))
    if as_tuple(head_vn) < as_tuple(expected_vn):
        fail(
            "This PR has %s-level commits but versionName is still %s (base %s). Bump to at "
            "least %s (versionCode %d) in %s, and add %s."
            % (
                bump, head_vn, base_vn, expected_vn, formula(expected_vn), GRADLE,
                CHANGELOG.format(code=formula(expected_vn)),
            )
        )

    changelog = CHANGELOG.format(code=head_vc)
    if not os.path.isfile(changelog) or os.path.getsize(changelog) == 0:
        fail(
            "Missing (or empty) changelog %s for versionCode %d (SPEC section 8 requires at "
            "least en-US)." % (changelog, head_vc)
        )

    print(
        "OK: %s-level PR bumps %s (%d) -> %s (%d); changelog present."
        % (bump, base_vn, base_vc, head_vn, head_vc)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
