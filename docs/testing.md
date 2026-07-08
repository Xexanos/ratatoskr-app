# Testing — ratatoskr-app

> Repo-local test strategy for the Ratatoskr **Android app**. The overarching,
> cross-component strategy — including end-to-end tests across the app, server,
> Audiobookshelf and a fake Sonos — is defined centrally:
> → [ratatoskr-e2e/test-concept.md](https://github.com/Xexanos/ratatoskr-e2e/blob/main/test-concept.md)
>
> This describes the **target** strategy; where the current code does not yet
> match, see [Status](#status--alignment).

## Scope

The app is a thin remote: it talks **only** to the Ratatoskr server over HTTPS and
holds no domain logic. Everything here tests the **app in isolation** — on the JVM
or against a mock server, never a real backend. Tests that exercise the real app +
server + ABS + Sonos together are **E2E** and live in the central repo, not here.

## Levels

### Unit — JVM, no device (JUnit4)
- DTO → domain mappers (incl. tolerance for unknown fields)
- error mapping (transport/HTTP → `RatatoskrError`)
- single-flight token refresh logic
- TLS fingerprint comparison / trust-on-first-use decision
- per-screen ViewModel state transitions

Runner: **JUnit4** (chosen for one framework across all levels — the instrumented
layers below are JUnit4-bound anyway). Networking-adjacent units use OkHttp
**MockWebServer**.

### Component — instrumented on emulator, real TLS + Keystore
`core-network` against MockWebServer over HTTPS with a held certificate. This is
what the JVM cannot do: it exercises the **real** Android TLS trust-on-first-use
pinning, the Keystore-backed encrypted token store, and refresh-on-401.

### Integration — instrumented, whole app
Drive `MainActivity` through the real Compose screens against a MockWebServer
backend (no real server): the full flow connect → login → browse → now-playing.
Tools: **Compose UI Test** + Espresso.

## Cross-cutting types

- **Accessibility:** per-screen checks with the Accessibility Test Framework, run
  in both light and dark themes.
- **Security:** trust-on-first-use TLS pinning and Keystore-encrypted token
  storage, validated on-device at the Component level.
- **Compatibility:** the instrumented suite runs on **API 26 (minSdk)** and
  **API 36 (target)** to catch regressions at both ends of the supported range.

## Test tags for black-box driving

Screens expose stable `Modifier.testTag`s with `testTagsAsResourceId = true` set
high in the hierarchy. These serve the Compose integration tests here **and** are
required by the central E2E harness (Maestro) to drive the installed `.apk`
black-box. See the central concept's open points.

## Running

```sh
./gradlew testDebugUnitTest         # unit (JVM), all modules
./gradlew connectedDebugAndroidTest # component + integration + a11y on an emulator;
                                    # run from the root, it covers :core-network and :app
```

## Status / alignment

The strategy above is the target. Current state:

- **Present:** `core-network` unit tests (JUnit4 + MockWebServer); per-screen
  ViewModel state-transition unit tests (`:app`, JUnit4, against a real
  `ConnectionStore`/DataStore on the JVM and `HttpsMockServer`); instrumented
  component tests (auth, deserialization, error mapping, session rotation, TLS
  pinning); accessibility checks across every screen (ATF, WARNING threshold incl.
  contrast) in the emulator's default theme.
- **In progress:** the whole-app Compose integration flow.
- **To add:** running the accessibility checks in both light and dark themes
  (today each screen preview is checked once, in the system default theme);
  exposing `testTagsAsResourceId` + `testTag`s for black-box driving; running the
  instrumented suite on both API 26 and API 36.
