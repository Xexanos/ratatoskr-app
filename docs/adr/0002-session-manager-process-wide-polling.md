# Session polling and commands centralized in a process-wide SessionManager

Decided in issue #101 (2026-07-23, grilling session on #79), replacing the interim approach of
each screen (Now Playing) driving its own RESUMED-scoped poll loop and holding the
command-epoch guard and `sessionActive` flag itself.

`SessionManager` (`data/SessionManager.kt`) is now the single owner of: the 5-second poll loop,
the transport commands (pause/resume/seek/stop), the command-epoch guard against poll/command
races, `NoActiveSession` handling, and the `sessionActive` token-rotation flag (SPEC section 5)
- the only writer of that flag. It is bound to
[`ProcessLifecycleOwner`](https://developer.android.com/topic/libraries/architecture/lifecycle)
in `AppContainer`, wired once for the process. ViewModels (`NowPlayingViewModel`, and the
library's mini player state in `LibraryViewModel`) are thin adapters over its `state:
StateFlow<SessionSnapshot>`: they map the shared snapshot into their own UI state and forward
control actions, but hold no poll loop or epoch logic of their own.

## Considered options

- **Screen-driven (RESUMED-refcounted) polling** (rejected): each screen that cares about the
  session (Now Playing, and now the library's mini player) would poll while visible, refcounting
  so the loop keeps running if either is RESUMED. Rejected for two reasons: the `sessionActive`
  flag would freeze stale on whichever screen wasn't currently polling (e.g. the library sitting
  idle while a session quietly expires server-side), and background silence - the loop stopping
  the moment the app leaves the foreground - was an accepted limitation of the old design, never
  a hard requirement; a process-wide loop bound to `ProcessLifecycleOwner` gets that for free
  without refcounting machinery.
- **Two independent pollers** (rejected): letting the library and Now Playing each poll
  independently would double the request rate for no benefit and the `sessionActive` flag
  tolerates only one writer - two pollers racing to set it is exactly the class of bug the
  command-epoch guard exists to prevent for commands, and polling needs the same discipline.

## Extending the epoch guard to `stop()`

The pre-#101 `NowPlayingViewModel` guarded pause/resume/seek with the command-epoch counter but
`stop()` was unguarded, relying instead on a screen-local `stopped` boolean to reject any poll
that raced past it. That boolean doesn't generalize to a process-wide manager with no per-screen
concept of "the user just left this screen" - so `stop()` now bumps the epoch like every other
command. A poll already in flight when `stop()` lands carries a stale epoch and is dropped by the
same guard that already protected pause/resume/seek, which subsumes what the `stopped` flag did
without needing a dedicated flag at all.

## Consequences

- `NowPlayingScreenHost` no longer drives a poll loop; it just collects `NowPlayingViewModel`'s
  state, which itself collects `SessionManager.state`.
- A failed poll keeps the last known session in `SessionSnapshot.session` and only records the
  error in `SessionSnapshot.error` - consumers decide whether to surface it. Now Playing maps it
  to its own banner; the mini player (`LibraryViewModel`) never reads it at all (no error state
  for the mini player - a transient blip must not blank a live session).
- Control actions (`pause`/`resume`/`seek`/`stop`) return `ApiResult<...>?` directly to the
  caller (null = superseded by a newer action) instead of writing an error into the shared
  state, so a failure from the mini player's toggle surfaces as a library-only snackbar while an
  identical failure from Now Playing's own controls surfaces as its own inline banner - the same
  underlying command, two different presentations, decided by whoever called it.
- A related but separate addition, `SpeakerManager` (id -> name cache with refetch-on-miss), was
  needed because the mini player also needs to resolve `Session.speakerId` to a display name, but
  it does not participate in this ADR's polling/epoch story.
