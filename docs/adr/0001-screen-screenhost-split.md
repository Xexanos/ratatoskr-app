# Screens split into a public stateless Screen and a ScreenHost; tests reach every state through public seams

Every screen package exposes two composables: a public, stateless `XScreen(state, ...)` — a pure
function of its UI state, and the one surface previews, screenshot goldens, and accessibility
checks render — and an `XScreenHost(viewModel, ...)` that owns ViewModel wiring, navigation
effects, snackbars, and polling, rendered only by the navigation graph. Previews live in sibling
`XScreenPreviews.kt` files in the main source set (see "Why main, not test" below) and may use
only public seams; no composable widens its visibility for tests.

Decided in issue #100 (2026-07-21), replacing the interim approach of marking content composables
`internal` for the extracted preview files.

## Considered options

- **`internal` preview seams** (rejected): extracting previews from production files and widening
  the composables they touch from `private` to `internal`. Works, and in a leaf app module
  `internal` is barely wider than `private` — but test code then dictates production visibility,
  and the pattern degrades: the Now Playing previews could not reach the stateful screen's layout
  at all and re-implemented it in a preview-only scaffold, so its goldens verified a copy that
  could not fail when the real screen drifted.
- **Driving the stateful screen from previews** (rejected): the stateful entry point pulls a
  read-only `StateFlow` from a ViewModel that computes state from network results, and its launch
  effects fire on composition — a preview cannot inject an arbitrary state without a fake
  ViewModel stack, which is the machinery the stateless layer exists to avoid.
- **`XRoute` naming** (rejected): the common Compose name for the stateful wrapper collides with
  this repo's `Route` — the `@Serializable` navigation destination types (SPEC section 13).
  `Host` carries the same intuition (`NavHost`, `SnackbarHost`) without the homonym.

## The loading-gate seam

`rememberDelayedVisible` hides loading indicators for 500 ms, and a paused composition clock
(screenshot goldens, the preview pane) never elapses it — goldens named "loading" silently showed
no loader. The gate reads `LocalImmediateLoading` (default off; previews and tests provide
`true`), the same CompositionLocal-as-seam idiom as `LocalReducedMotion` and
`LocalCoverImageLoader`. Production behavior is unchanged.

## Why main, not test

The previews cannot move into `src/test` (JVM/Robolectric), even though Roborazzi's golden
generator itself does not care - a preview file moved there and re-recorded produces
byte-identical goldens (verified experimentally). The actual blocker is `AccessibilityChecksTest`
(`src/androidTest`): it imports these same preview functions directly, and `test` and `androidTest`
are separate Gradle source sets with no visibility into each other - moving previews to `test`
breaks that suite's compilation outright (confirmed: `Unresolved reference` on every moved
preview). Sharing them would need a `testFixtures` source set on the `app` module, a real
structural addition, not a file move - out of scope here.

`AccessibilityChecksTest` itself cannot move to `test` either, for the reverse reason: its checks
walk a live accessibility node tree that Robolectric doesn't provide, so under Robolectric they
pass silently instead of validating anything (robolectric/robolectric#5642). Confirmed by running
the suite's own canary case (a knowingly inaccessible view that must fail the checks) under
Robolectric: it does not fail - the very regression the canary exists to catch.

## Consequences

- The cover tile's spinner preview goes through the real `CoverImage` with a never-resolving
  loader (public `LocalCoverImageLoader`) plus the gate local — verified deterministic under the
  golden harness (issue #100 prototype).
- The **loaded**-cover state is the one state no public seam can golden: neither a fake loader
  nor coil's preview handler delivers a success state onto the paused-clock frame (falsified
  twice in the issue #100 prototype). Its goldens render a synthetic bitmap through a plain
  `Image` with the same fit — they prove the letterbox treatment, not `CoverImage`'s
  `contentScale` default. Do not "fix" this by widening visibility.
- Snackbars stay in hosts and are not golden-covered; making them previewable would push Compose
  infrastructure types into screen signatures (revisit as its own decision if wanted).
