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

**Cover bucket**:
One of the quantized heights {128, 256, 512, 1024} the app requests covers at (`?h=`),
rounded up from the rendered size and capped at 1024. Surfaces whose rows land in the same
bucket share one cached image; nothing ever requests an unquantized or original size.
_Avoid_: thumbnail size, resolution

**Placeholder tile**:
The title-initial tile shown where a cover would be - identical while a cover loads and
when none exists, so a coverless book looks finished, not broken. (Its visual design is
under review: issue #78.)
_Avoid_: fallback image, error state (it is deliberately not a distinct state)
