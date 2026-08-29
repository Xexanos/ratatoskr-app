# Store metadata

F-Droid builds the store listing from `android/<locale>/`: title, descriptions, images and
the per-release changelogs. `docs/SPEC.md` (section 12) covers what belongs here, how the
changelog files are named after the `versionCode`, and what CI enforces. This file covers how
to *write* an entry, which the files alone do not reveal.

## Changelogs

One file per released version code: `android/<locale>/changelogs/<versionCode>.txt`.

### Form

- **500 characters is a hard ceiling**, counting the trailing newline. It is a store limit,
  not a preference - a longer entry gets truncated. Aim for 480 or less, so a later wording
  fix still has room. It is 500 *characters*, not bytes: German text spends extra bytes on
  umlauts, so byte-based tools (`wc -c`, or `wc -m` in the C locale) overcount.
- **One paragraph on one line.** No headings, no lists, no blank lines, no hard wrapping.
- **UTF-8 without BOM, LF, exactly one trailing newline.** `.gitattributes` normalises the
  line ending; the rest is on you.
- **`en-US` and `de-DE` come as a pair.** `pr-release-guards.yml` only checks that `en-US`
  exists and is non-empty, so nothing catches a missing translation. Write both in the same
  commit.

### Voice

- **Second person, about what the reader gets.** An entry describes the visible outcome, not
  the implementation: "session state is now tracked in one place" is a commit message, "the
  shelf refreshes the moment a book stops playing" is a changelog.
- **No jargon and no internal names.** Class names, module names and issue numbers stay out.
- **Only promise what the app really does.** The entry is public and outlives the release, so
  it must not outrun what the SPEC guarantees, and it must not advertise anything a later
  change might walk back.
- **A plain hyphen `-` for an aside**, never an en dash (U+2013) or em dash (U+2014).
- **Quotes follow the locale**: German uses „...“ (U+201E and U+201C), English plain
  straight `"`.
- **Name things the way the UI does.** Take screen and feature wording from
  `app/src/main/res/values/strings.xml` and `values-de/strings.xml` (Weiterhören,
  Lautsprecher, Wiedergabe-Bildschirm) rather than inventing a term the reader cannot find in
  the app.

The quickest way to match the tone is to read the two or three preceding entries in the same
locale before writing.

### Checking an entry

Byte-counting tools misreport the length, so check with Python:

```bash
python - <<'PY'
import glob, io
NL = chr(10)
for p in sorted(glob.glob("fastlane/metadata/android/*/changelogs/*.txt")):
    s = io.open(p, encoding="utf-8").read()
    if len(s) > 500 or s.count(NL) != 1 or not s.endswith(NL):
        print("%s: %d characters, %d lines" % (p, len(s), s.count(NL)))
PY
```

Silence means every entry is within the limit, on a single line, and newline-terminated.
