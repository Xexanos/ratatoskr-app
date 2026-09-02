# Ratatoskr app

Android remote control for the Ratatoskr server: browse the Audiobookshelf library, pick a
Sonos speaker, control the single active playback session. All domain logic lives on the
server; the app renders server state.

## Language

### Library

**Browse list**:
The complete, alphabetically ordered, cursor-paginated library list (`GET /library/items`).
Always complete — no item is ever removed from it because it appears elsewhere.
_Avoid_: main list, full list

**Continue-listening shelf**:
The bounded, most-recently-listened-first set of in-progress books
(`GET /library/in-progress`), rendered as a vertical section above the browse list. A view
onto the library, not a partition: shelf books also keep their place in the browse list.
The primary entry point into the library — resuming a book is the app's main job, so a
missing shelf is an error state worth reporting, not a cosmetic absence.
_Avoid_: pinned section, in-progress list

**In progress**:
A book with progress present, position > 0 and not finished. Defined by the server; the
app never re-derives shelf membership itself.
_Avoid_: active, started, listening

### Playback

**Active session**:
A playback session in state playing, paused, or buffering — the states in which the server
owns token rotation (SPEC section 5). One definition for every consumer: it gates the
client's own token refresh and it decides whether the mini player exists. A session seen in
a stopped or finished tail is already *not* active: the server tears the session down at a
natural end itself, so the polled truth converges on "no session" within one sync tick.
_Avoid_: running session, session present, current session (as synonyms for active — a
session can be present without being active)

### Covers

**Cover**:
A book's artwork, always served by the Ratatoskr server's cover proxy under the app's own
auth - never fetched from Audiobookshelf directly. "No cover" shows as a failed load (404),
or as a null cover URL once the server implements it; it is never a sentinel value.
_Avoid_: thumbnail, artwork (as distinct concepts - there is only the cover, at different sizes)

**Cover URL** (`coverUrl`):
In the app's domain model: the absolute, bearer-authenticated URL a cover is loadable from.
On the wire the server sends an origin-relative path; resolving it is the wrapper's job, so
nothing above the wrapper ever sees a relative value.

**Cover endpoint** (`CoverEndpoint`):
The wrapper's single owner of cover-URL knowledge: it resolves the origin-relative `coverUrl`
to an absolute one (bound once to the server origin) and recognises a URL that points at the
cover proxy (the shape the `?h=` height parameter applies to). Recognition is host- and
version-agnostic, so the app's cover-image interceptor consumes it without learning the origin.
The path shape is mirrored from the contract's `GET /library/items/{id}/cover`; a drift-guard
test keeps them in step.

**Cover bucket**:
One of the quantized heights {128, 256, 512, 1024} the app requests covers at (`?h=`),
rounded up from the rendered size and capped at 1024. Surfaces whose rows land in the same
bucket share one cached image; nothing ever requests an unquantized or original size.
_Avoid_: thumbnail size, resolution

**Loading tile**:
The bare tonal tile shown while a cover request is in flight - and kept when the request
fails for reasons other than "no cover" (timeout, server error), because claiming "no
cover" for a book that has one would be a lie. Quiet by design: distinguishing rows is the
text column's job, not the tile's.
_Avoid_: skeleton, shimmer, error state (a failed load is not a distinct visual state)

**No-cover tile**:
The tinted Ratatoskr knot mark on the tonal tile, shown when a book has no cover - a null
cover URL or a 404 from the cover proxy. A deliberate "this book has no artwork" mark, the
same for every coverless book: repeated identical marks read as a pattern, not a bug
(decided in issue #78, replacing the title-initial tile).
_Avoid_: placeholder tile (the pre-#78 term), fallback image, initials

### Notices and errors

**Notice**:
A heads-up about a state the user did not choose - an expired sign-in, or the one-time
re-login after an app update. Neither a success nor a failure the user caused, so it is
carried by a neutral surface rather than an error one, and it is never announced: a notice
is already on screen when the screen opens.
_Avoid_: warning, error, info message

**Error banner**:
The inline report that an action or a load failed, shown where the action was. Announced
when it appears, because unlike a notice it arrives mid-session. Retry follows the failure's
scope: a failure confined to one section is retried by tapping the banner itself, a failure
that blocks the flow gets its own button below it.
_Avoid_: alert, toast, dialog (an error is never global)

### Actions

**Pinned action**:
A screen's action held in the bottom thumb zone, outside the scroll region, so it stays under
the thumb however far the content above grows. The slot belongs to the screen rather than to
one of its states: it carries whichever action the current state offers, including a retry for
a failure that blocks the whole flow. What a state does while it waits depends on whose wait
it is. A wait that *is* the action keeps its button and puts the progress indicator inside it
(sign-in submitting). A wait the knot loader owns gives the slot up entirely (Connect reading
the certificate): two progress signals on one screen is one too many, and a disabled button
left in the slot fails the contrast floor, because M3 renders a disabled label at 38% alpha.
Connect and sign-in, the two first-run screens, take one branch each (issues #133 and #155).
_Avoid_: sticky footer, bottom bar (that is navigation), CTA
