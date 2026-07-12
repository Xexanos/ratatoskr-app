# Ratatoskr app - specification and implementation brief

This document is the ground truth for implementing the Ratatoskr Android app. It captures
the goal, the scope of the first version, and the decisions that are already fixed by the
server and its API contract, so they do not have to be rediscovered.

The app is the client end of the Ratatoskr project. The server's brief and API contract
are the authoritative descriptions of the other side:

- Server spec: `ratatoskr-server/docs/SPEC.md` (referenced below as "server section N").
- API contract: `ratatoskr-server/contract/openapi.yaml` (OpenAPI 3.0.3). This is the
  single source of truth for all client/server communication.

Note: the technology stack and the screen/module structure were intentionally left open
in the original brief. They have now been decided together with the implementing agent
and are recorded in sections 12 and 13.

## 1. Goal

Give an Android user a simple remote for playing Audiobookshelf audiobooks on their Sonos
speakers through the Ratatoskr server, with the listening progress synced back to
Audiobookshelf. The app is deliberately thin: it holds no audio, no library logic, and no
Sonos knowledge. It sends commands and shows state.

## 2. Scope

### In scope for v1
- Connect to a Ratatoskr server by URL, establishing trust in its certificate
  (see section 6).
- Sign in with Audiobookshelf credentials via the server, and stay signed in across app
  restarts using the stored refresh token.
- Browse and search the library (the server's per-user projection).
- List the Sonos speakers and groups the server discovered, and pick one.
- Start a book on the chosen speaker; it resumes from the stored position automatically.
- A now-playing view with play/pause, seek (absolute position within the book), and stop,
  showing the current position and duration.
- Reflect that there is exactly one active session at a time (server section 8).
- Sign out (clear stored tokens).

### Explicitly out of scope for v1
- Playing audio on the phone itself. The app is a remote; audio is server-to-speaker only.
- Offline use, downloads, or any local library cache beyond what a screen needs while open.
- Chapter navigation UI, bookmarks, playback-speed control, sleep timers.
- Podcasts, multiple simultaneous sessions, Ratatoskr-controlled multiroom grouping
  (all out of scope on the server side too).
- Widgets, Android Auto, Wear, media-session/notification transport controls. These are
  natural later additions; do not build them now, but do not architect against them.

## 3. Architecture and how it fits

- The app talks ONLY to the Ratatoskr server, over HTTPS, using the API in the contract.
  It never talks to Audiobookshelf or to the Sonos speakers directly.
- All domain logic (position mapping, the sync loop, Sonos control) lives in the server.
  The app maps server responses to what the screens show, and user actions to API calls.
- Playback state is owned by the server. The app is not the source of truth for position;
  it displays the session the server reports and may advance the displayed position locally
  between polls for smoothness, correcting to the server's value when it arrives.
- Single active session: the UI treats "what is playing" as one server-owned session,
  fetched from `/v1/sessions/current` (which is 404 when nothing plays).

## 4. API client

- The Kotlin client is generated from `contract/openapi.yaml` with openapi-generator. It is
  never hand-edited, and the domain/UI layer depends on a thin wrapper over it, not on the
  generated types directly, so a contract change is absorbed in one place.
- The contract is consumed via a git submodule of the server repository
  (`https://github.com/Xexanos/ratatoskr-server`), pinned to a tag or commit, and the
  client is generated from it at Gradle build time. F-Droid's build server supports
  submodules (`submodules: yes` in the fdroiddata recipe), which keeps the build hermetic
  and deterministic - unlike jitpack, which F-Droid's inclusion policy rejects. The
  submodule pointer is only ever moved deliberately (reviewing the contract diff), never
  automatically during a build. Do not depend on a private or authenticated package
  registry (that breaks F-Droid's build server). A human-readable rendering of the
  contract on the server repo's GitHub Pages is welcome documentation but is not part of
  the build.
- The generated client's HTTP stack must be free software; the decision is Retrofit on
  OkHttp (section 12). No proprietary dependencies, no Google Play Services (server
  section 12, F-Droid).
- Treat unknown response fields leniently: an older app must keep working against a newer
  server (server section 6). Do not fail deserialization on unexpected fields.

## 5. Authentication

Auth is per-user and backed by Audiobookshelf, proxied through Ratatoskr (server section 8).

- Sign-in collects the Audiobookshelf username and password and posts them to
  `POST /v1/auth/login`. The server returns an access token, a refresh token, and the
  identified user. The password is never stored.
- The access token is sent as a bearer token on every request. It is short-lived; when it
  is rejected, exchange the refresh token via `POST /v1/auth/refresh` and retry once. A
  single-flight guard must prevent concurrent refreshes from racing.
- Tokens are stored encrypted at rest (Android Keystore-backed storage, for example
  EncryptedSharedPreferences or DataStore with a Keystore key), never in plain preferences
  or logs.
- Sign-out clears the stored tokens.

### Refresh-token rotation (contract 1.1.0)

Audiobookshelf rotates the refresh token on every use, and the server holds the listening
user's tokens in memory for the active session (server section 8 and section 14). The app
and the server must not both consume the same refresh token independently. This is resolved
in contract 1.1.0 and mirrors the server's section 8; the app's obligations:

- The `Session` schema carries an optional `rotatedTokens` object (`accessToken` +
  `refreshToken`, both or neither). It is present only when the server has rotated the
  caller's tokens since the last `Session` it returned; the app must adopt the pair
  immediately, discard the previous one, and send the new access token on its next request.
  Because every playback response (`getCurrentSession`, `startSession`, `pauseSession`,
  `resumeSession`, `seekSession`) returns a `Session`, rotated tokens reach the app through
  the polling it does anyway. The app must tolerate the fields being absent (an older server
  never sends them).
- While a session is active, the app never calls `POST /v1/auth/refresh` on its own. It
  hands its refresh token to the server in `startSession` and from then on only adopts
  tokens arriving in `Session` responses (including the 200 body from `stopSession`, below).
  The server confirms delivery by *adoption*: it keeps including `rotatedTokens` until the
  app authenticates with the new access token, so a dropped or half-read response cannot
  strand the app.
- A 401 on a non-session call during an active session first triggers an immediate
  `getCurrentSession` to pick up a rotated pair; only if none is offered does the app fall
  back to a regular `/auth/refresh`. This re-fetch is guaranteed to authenticate because
  Audiobookshelf access tokens are stateless (validated by signature and expiry only) and
  the app's current one stays valid until its own expiry even after rotation - the server
  refreshes proactively, before that expiry, precisely to leave this window open.
- `stopSession` returns 200 with a final `Session` (instead of 204) when a rotated pair is
  still pending, so the app adopts it as the session ends; the app treats any 2xx from
  `stopSession` as success and adopts `rotatedTokens` if the body carries it. The one
  irreducible residual - the single stop response that carries the final pair being lost in
  transit - is recovered by asking the user to sign in again; the app must not loop
  `/auth/refresh` on a token the server has already rotated away.

## 6. Server connection and certificate trust

The server serves HTTPS with a self-signed certificate or a local CA (server section 14).
There is no public CA in the chain, and each user's server has its own certificate, so the
certificate cannot be pinned at build time.

- The user enters the server's URL (host and port). There is no discovery in v1.
- Establish trust on first connection: fetch the server certificate, show its fingerprint,
  and ask the user to confirm it (trust-on-first-use). Persist the confirmed fingerprint
  and pin it for all later connections; warn loudly if it ever changes (with the scope
  caveat below).
- Decided mechanics: the connect screen fetches the certificate chain with a short-lived
  OkHttp client whose trust manager accepts anything but is used ONLY to read the chain -
  never for real requests. It shows the leaf certificate's SHA-256 fingerprint plus
  subject, issuer, and validity; on confirmation the app persists
  `{host, port, sha256 fingerprint}`. All real requests go through a custom
  `X509TrustManager` (in `core-network`) that first tries the platform trust chain - which
  covers the reverse-proxy-with-public-CA case - and only on its failure compares the leaf
  fingerprint against the stored pin. Matching neither is a hard failure with a loud
  "certificate changed" warning and an explicit re-trust flow (settings → forget
  certificate).
- Scope of the change guarantee (deliberate trade-off): the "warn loudly if it changes"
  guarantee applies to the self-signed / local-CA deployment, which is the primary one and
  where the platform chain fails so the stored pin is always the deciding factor. When the
  server instead presents a **publicly trusted** certificate (e.g. TLS terminated at a
  reverse proxy), the platform chain validates and the pin is not consulted - so a different
  but validly issued certificate for the same host is accepted without re-confirmation. That
  is an accepted consequence of putting platform validation first: in that deployment trust
  is delegated to the public CA, and requiring the pin to match as well would reject every
  routine certificate renewal (e.g. Let's Encrypt's ~90-day rotation) and force a manual
  re-trust each time. Strict leaf-pinning against publicly trusted certificates was
  considered and rejected on those grounds.
- This runtime, user-confirmed pinning is implemented with the platform TLS stack and
  OkHttp only, so it survives a reproducible F-Droid build. A build-time
  network-security-config pin cannot express a per-user certificate and must not be relied
  on as the mechanism.

## 7. User-provided configuration

The app stores, per install: the server URL, the confirmed server-certificate fingerprint,
and the auth tokens (encrypted). It does not store the Audiobookshelf password. A settings
screen exposes the server URL, a re-trust/forget action for the certificate, and sign-out.

## 8. Distribution constraints (F-Droid and Play Store)

- License is GPL-3.0-or-later (matches the whole project).
- `applicationId` is `io.github.xexanos.ratatoskr`.
- Free-software dependencies only: no Google Play Services, no proprietary SDKs, no
  analytics or tracking libraries. F-Droid will reject non-free dependencies and flag
  anti-features.
- Aim for a reproducible build. Keep the dependency set small and the build free of
  non-deterministic steps.
- Provide F-Droid metadata under `fastlane/metadata/android/<locale>/` (title, short and
  full description, changelogs, images). The F-Droid build recipe itself lives in F-Droid's
  `fdroiddata` repository, not here.
- Choose a reasonable `minSdk` that covers the encrypted-storage and TLS requirements; the
  agent proposes the exact value.
- Release shrinking (R8) is **enabled**, and the conditions that once blocked it are resolved:
  - The generated API client uses Moshi **codegen** adapters (`moshiCodeGen=true` in the
    generator config, processed by KSP/`moshi-kotlin-codegen`), so no reflective Kotlin
    adapter — and no `kotlin-reflect` — ships at all. Enums keep Moshi's built-in handling,
    so the unknown-`PlaybackState` fallback (section 4) is unaffected.
  - Keep rules are deliberately minimal: the libraries' own consumer rules (Retrofit, OkHttp,
    Moshi) plus the per-model adapter keeps moshi-kotlin-codegen generates, mirrored
    explicitly in `app/src/main/keepRules/rules.keep` as insurance in case the library-module
    resource rules do not propagate.
  - **No obfuscation** (`-dontobfuscate`): shrinking and optimization stay fully active, but
    names are kept readable. F-Droid users never receive a mapping file, so crash reports
    must stay legible; unobfuscated output is also easier to verify for the reproducible
    build and keeps the minified instrumented run free of test-APK mapping fragility.
  - Validation is on-device and post-merge (section 9): every push to `main` builds the
    `minified` variant — the release R8 configuration, debug-signed and debuggable so
    instrumentation can attach (R8 in a debuggable variant still enforces shrinking and keep
    rules fully; only code optimizations are reduced) — and drives the whole-app integration
    flow plus a wire-level smoke mirror against it. On success the workflow publishes the
    tested APKs to a per-commit `testing-<short-sha>` pre-release (pruned by a scheduled
    cleanup job) and (once the `ratatoskr-e2e` repository exists) triggers the cross-component
    E2E suite. The per-PR gate is `assembleRelease` **and** `assembleMinified` in CI, which
    catch keep-rule/shrinker config errors cheaply on the JVM without an emulator —
    `assembleMinified` is what parses the minified-only keep rules.

## 9. Testing

Testing is layered by what each layer can honestly verify. The layers are complementary, not
redundant: lower layers go deep on mechanics and edge cases, the integration layer goes broad
across the assembled user flow. Any overlap between them is deliberately thin.

### 1. Unit tests (JVM)

Pure logic with the platform pieces faked. JVM (`testDebugUnitTest`), no device, fast; most
tests live here. In place: the auth/token logic (bearer attachment, refresh-on-401,
single-flight guard, secure-storage read/write with the platform faked) and the mapping
between generated contract types and the domain/UI models (including unknown-field tolerance).

### 2. Component tests (instrumented)

The network / trust / persistence stack *as it is actually assembled*, driven through
`RatatoskrClientFactory.create(...)` against an in-process `MockWebServer` over HTTPS. These
run **instrumented on an emulator**, not on the JVM, because the paths that matter here are
exactly the ones that diverge between the JVM and Android: TLS trust goes through Android's
real system trust store and TLS provider (Conscrypt), and token persistence through the real
Keystore-backed store — a JVM run would validate a stand-in (JDK `cacerts`, an in-memory token
fake), not what ships.

They exist because a hand-wired unit test can pass while the real wiring is broken: the
unknown-enum fallback Moshi was once attached to the client but not to the Retrofit converter
`RatatoskrClientFactory` builds, so it never ran on real responses, yet a unit test that wired
its own Moshi stayed green. Driving the real factory closes that gap. In place
(`core-network`, `Factory*ComponentTest`):

- response deserialization through the factory's own converter — the unknown-`PlaybackState`
  fallback and unknown-field tolerance on a real response body;
- the TLS pin (section 6): a matching fingerprint connects and a changed/absent one is
  rejected, against Android's real trust manager. The primary self-signed / local-CA path is
  covered (the platform chain fails, so the pin decides). The publicly-trusted-cert branch of
  the section-6 trade-off (platform validates, pin is skipped) is not reachable from a
  self-signed `MockWebServer` — a documented gap, not a covered case;
- bearer attachment and the 401 → refresh → retry flow, including single-flight under
  concurrent 401s (which caught a real dispatcher deadlock — see `RatatoskrClientFactory`);
- session-token rotation adopted end-to-end, and the `stopSession` 200-with-body vs 204 paths
  (section 5), against the real Keystore-backed token store;
- error mapping (401 / 404 / 502 / 500 / TLS failure) to the right `RatatoskrError`.

### 3. Integration tests (instrumented, whole-app UI)

The **complete app driven through its UI** — the Playwright analogue. An instrumented Compose
UI suite launches the real app (`MainActivity` / the navigation graph) and taps and types
through the actual screens, so the app itself drives its real navigation → ViewModels →
`ConnectionManager` → `core-network`, against a `MockWebServer` standing in for the Ratatoskr
server. It covers the assembled user flow — connect and confirm the certificate, sign in,
browse the library, pick a speaker, start playback, pause/resume/seek/stop — plus key
alternates (an unreachable server at connect, a sign-in failure, an empty library).

This layer asserts **user-visible outcomes and flow**, and deliberately does *not* re-assert
the wire-level mechanics the component layer already owns (enum fallback, error taxonomy,
token-rotation precision, concurrency). The thin, healthy overlap — a matching pin connects, a
normal session renders — is checked from each layer's own angle.

### 4. End-to-end tests

The app against the *real* stack (Ratatoskr server + Audiobookshelf + the official Sonos
simulator), no mocks. Out of scope for now; a separate later layer.

### CI

Component and integration suites run instrumented, not the JVM `testDebugUnitTest` step,
across several parallel emulator CI jobs (alongside the `AccessibilityChecksTest`
accessibility suite in light and dark themes), after a shared AVD-warming job populates the
emulator snapshot cache. The component suite runs on both API 26 (minSdk) and API 36
(target) — the layer whose behaviour diverges by API level (Conscrypt/TLS, Keystore); the
whole-app UI and accessibility suites run on API 36 only, as the hosted API-26 emulator
cannot reliably bring up the full UI / accessibility stack. The shared harness is a reusable
`MockWebServer`-over-HTTPS fixture (OkHttp's `okhttp-tls` `HeldCertificate`) in `core-network`
test fixtures. `ConnectionManager`'s caching is thin and orthogonal; a small test for it can
follow separately.

Post-merge, a separate workflow (`release-validation.yml`) validates the **shrunk** build
(section 8) on every push to `main`: it runs the whole-app integration flow plus
`MinifiedWireSmokeTest` against the `minified` variant on an API 36 emulator, then publishes
the tested APKs to a per-commit `testing-<short-sha>` pre-release. `MinifiedWireSmokeTest` lives in
`app` (not `core-network`) because R8 runs only at the app level, so the component suite can
never execute against shrunk output; it re-drives just the wire-level behaviours most exposed
to R8 — the reflectively looked-up Moshi codegen adapters and the unknown-enum fallback —
through the real factory. This is the same deliberate, thin overlap policy as above, extended
to the minified context.

## 10. Definition of done for v1

- A user can, from a clean install: enter a server URL, confirm its certificate, sign in
  with Audiobookshelf credentials, search and browse the library, pick a speaker, start a
  book that resumes at the right spot, pause/resume/seek/stop, and see the reached position
  reflected in Audiobookshelf afterward.
- The session survives closing and reopening the app (it is re-fetched from the server).
- The app stays signed in across restarts and refreshes its access token transparently.
- Builds from source with only free-software dependencies; README and this spec match what
  was built.

## 11. Coding constraints for the implementing agent

- The app holds no domain logic that belongs on the server. If a feature needs Sonos or
  Audiobookshelf knowledge, it is a server concern reached through the contract.
- Do not hand-edit generated client or type code; wrap it.
- No proprietary dependencies, no Google Play Services, no trackers (section 8).
- Never log tokens, passwords, or Authorization headers.
- License headers, where used, are GPL-3.0-or-later.

## 12. Technology stack

Decided with the implementing agent. Rationale in brief, so it is not re-litigated.

- **Language / UI:** Kotlin, Jetpack Compose, Material 3.
- **Asynchrony and state:** coroutines and Flow; one ViewModel per screen; unidirectional
  data flow (immutable UI state exposed as a `StateFlow`).
- **Dependency injection:** manual constructor injection via a single `AppContainer`
  created in the `Application` class. No Hilt/Koin - the app is too small to pay for a
  framework, and the container keeps wiring in one visible place.
- **API client:** openapi-generator with the Kotlin `jvm-retrofit2` template on Retrofit +
  OkHttp, generated at Gradle build time from the contract git submodule (section 4).
  Retrofit's Kotlin generator template is the maturest option, supports suspend functions
  natively, and OkHttp accepts a runtime-configured `X509TrustManager`/`SSLSocketFactory`,
  which is exactly what the TOFU pinning in section 6 needs - without touching generated
  code.
- **JSON:** tolerant deserialization (unknown fields ignored) as section 4 requires; the
  serialization library is whatever the chosen generator template uses (Moshi for
  `jvm-retrofit2`), configured leniently.
- **Token storage:** Keystore-backed DataStore (encrypted at rest, section 5).
- **`minSdk` 26** (Android 8.0): safely covers the Keystore-backed encrypted storage and
  TLS requirements and provides `java.time` (for `Session.updatedAt`) without core-library
  desugaring. Raising coverage to older devices (minSdk 23 + desugaring) is possible but
  not planned.
- Keep the door open for Kotlin Multiplatform later (this repo is `ratatoskr-app`, not
  `-android`), but no KMP structure in v1.

## 13. Screen and module structure

Decided with the implementing agent. Two Gradle modules - the app is too small for more,
but the "generated client only behind a wrapper" rule (section 4) is enforced
architecturally, not by convention: UI code cannot import generated types because they
live in `core-network` and only the wrapper's domain models are exposed.

```
ratatoskr-app/
├── app/                    # Compose UI, ViewModels, navigation, AppContainer
│   ├── connect/             #   connect-and-trust screen (URL entry, fingerprint confirm)
│   ├── auth/                #   sign-in screen
│   ├── library/             #   browse and search
│   ├── speakers/            #   speaker picker
│   ├── nowplaying/          #   play/pause/seek/stop, position display
│   └── settings/            #   server URL, certificate re-trust/forget, sign-out
│
└── core-network/           # everything that talks to the server; app depends on this
    ├── generated/           #   openapi-generator output - NEVER hand-edited
    ├── api/                 #   thin wrapper over the generated client: maps generated
    │                        #   DTOs to domain models, absorbs contract changes in one
    │                        #   place, tolerant to unknown fields; also the bearer/refresh
    │                        #   interceptors and the single-flight, session-aware refresh
    │                        #   guard (section 5)
    ├── domain/              #   domain models (library item, speaker, session, tokens) and
    │                        #   the ApiResult type — the only types the app module sees
    ├── tls/                 #   TOFU trust manager and certificate-fetch helper (section 6)
    └── persist/             #   Keystore-backed encrypted token store and the trusted-
                             #   server / fingerprint store (sections 5, 6)
```

- Single activity; Compose Navigation with a sealed, type-safe route graph — each
  destination is a `@Serializable` `Route` type, so arguments (e.g. the speaker picker's
  item id) are carried by the type system rather than stringly-typed keys that could be
  missing. Start destination is resolved at launch off the main thread: no stored server →
  connect; no stored tokens → sign-in; otherwise library.
- One package per screen under `app/`, each with its composable screen, ViewModel, and
  UI-state type. The now-playing screen polls `getCurrentSession`, advances the displayed
  position locally between polls, and corrects to the server's value on each response
  (section 3).
- Domain models (library item, speaker, session, tokens) live in `core-network`'s wrapper
  layer and are the only types the `app` module sees.

## 14. Planned features (post-v1)

Ideas intentionally deferred beyond v1. These are **not commitments** — they are captured so
the reasoning is not lost and so the v1 design does not *actively prevent* them (section 2).
Mirrors the server SPEC's section 16 (Planned features); the app-side counterparts are here.

Most of the section 2 "out of scope for v1" items are the natural first post-v1 candidates and
are not repeated in full: **chapter-navigation UI**, **bookmarks**, **playback-speed control**,
**sleep timers**, **podcasts**, **multiple simultaneous sessions**, **Ratatoskr-controlled
multiroom grouping**, and the platform integrations (**widgets, Android Auto, Wear,
media-session / notification transport controls**). The following are the additional features on
the radar:

- **Connect to multiple Ratatoskr servers.** For users with Sonos in more than one location, each
  behind its own Ratatoskr instance. The app would keep a list of trusted servers and let the user
  switch between them — a natural generalization of today's single stored server + pinned
  certificate (sections 5 and 6), which already persist one trusted server; this extends the
  persist layer and the connect flow to several. Needs each server to be identifiable (a stable,
  admin-set name) so instances can be labelled in the switcher; tracked on the server side too
  (server SPEC section 16).

- **Multiple simultaneous sessions (app side).** v1 reflects exactly one active session (section 2).
  If the server later holds a session per user/speaker (server SPEC section 16), the now-playing
  experience would show and switch between concurrent sessions rather than assuming a single one —
  e.g. a session list, or per-speaker now-playing.

- **Cover art.** The library projection carries no cover URL today. Once the server serves cover
  images (server SPEC section 16), show artwork in the library and now-playing views.
