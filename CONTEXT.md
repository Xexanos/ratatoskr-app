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
