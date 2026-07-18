# Contract proposal: list cover thumbnails + continue-listening ordering

**Status:** draft (app-side). This sketches what the app needs from the server contract for
issues #55 (covers in the list) and #52 (actively-listened books lead the list, specially
marked). It drives a change to `ratatoskr-server/contract/openapi.yaml`, which follows the
server's own versioning rules (server SPEC section 6). Nothing here is built app-side until the
contract lands — the app is a thin remote.

Both parts are **additive and backward-compatible** (new optional field / new endpoint), so they
are oasdiff-clean and fit a **minor** contract bump, like the 1.1.0 refresh-token-rotation change.
They are independent and can ship separately.

## Current contract (for reference)

`GET /library/items` takes `q`, `limit` (≤100, default 50), `cursor` — no ordering control. It
returns a `LibraryItemPage` (`items` + nullable `nextCursor`). `LibraryItemSummary` already has:

```yaml
coverUrl:  { type: string, nullable: true, description: "Absolute URL served by Ratatoskr; may require the token." }
progress:  $ref Progress   # Progress = { positionSeconds: double, isFinished: bool }
```

So covers are **not** missing, and `progress` already tells the client whether a book is in
progress. What is missing is (A) a list-sized cover variant and (B) server-side ordering.

---

## Part A — list cover thumbnails (#55)

**Problem.** `coverUrl` is a single full-size cover. Rendering it in every list row is wasteful on
bandwidth and, on low-end devices (explicitly in scope, see #65), risks large decoded bitmaps. The
list wants a small, **server-scaled** image; full size is only needed on now-playing.

**Proposed change.** Add an optional `thumbnailUrl` to `LibraryItemSummary`:

```yaml
thumbnailUrl:
  type: string
  nullable: true
  description: |
    Absolute URL of a small, server-scaled cover variant for list/grid rows. Full-size
    artwork is coverUrl. Sizing is the server's concern (thin client).
```

- The app uses `thumbnailUrl` in library rows and the continue-listening shelf, `coverUrl` on
  now-playing. If `thumbnailUrl` is null, fall back to `coverUrl`.
- Scaling stays server-side (thin client). Target roughly a list-row cover at ~3x density
  (~200 px wide); the exact size is the server's call.

**Alternative considered.** A size/`?w=` query param on the cover URL. Rejected: `coverUrl` is an
opaque absolute URL to the client, so a second explicit URL keeps the client dumb and the server in
control of sizing/caching.

---

## Part B — continue-listening ordering (#52)

**Goal.** Books the user is actively listening to (progress present, `positionSeconds > 0`,
`isFinished == false`) lead the library and are visibly marked — the app-side equivalent of ABS's
"Continue Listening" shelf.

**What does NOT need the contract.** The *marking* (badge / distinct row) is already derivable
client-side from the existing `progress` field. No change needed for the visual.

**What needs the server.**
1. **Ordering.** With cursor pagination the client only ever holds the loaded pages (see #50), so
   "in-progress first" cannot be done client-side — it must be server-side ordering.
2. **Recency.** "Most recently listened first" needs a timestamp the projection does not expose
   today (`Progress` has no `updatedAt`); only the server has ABS's last-update time.

**Proposed change (recommended): a dedicated shelf endpoint.**

```yaml
/library/continue:
  get:
    operationId: listContinueListening
    summary: Books currently in progress, most-recently-listened first
    parameters:
      - name: limit
        in: query
        schema: { type: integer, minimum: 1, maximum: 50, default: 20 }
    responses:
      "200": { LibraryItemPage or a plain array of LibraryItemSummary }
```

- Server returns only in-progress items (not finished, position > 0), ordered by ABS recency.
- Small and bounded; likely no pagination (a shelf, not the whole library).
- Cleanly decouples the shelf from the browse list, which stays alphabetical and paginated as-is.
- The app renders this as a section on top of the Library screen; the full list follows unchanged.

**Alternative considered.** A `sort=recent` query param on `/library/items`. Lighter (no new path),
but it entangles the shelf with the main list's pagination and still needs the server-side recency
data. The dedicated endpoint mirrors ABS's model and keeps the two concerns separate.

**Optional add-on.** Expose recency for display ("Continue at 3:24 · 2 days ago") by adding an
optional `updatedAt` (date-time) to `Progress`. Additive; only if the shelf UX wants the timestamp.

---

## App-side consumption (once the contract lands)

- Library rows: `thumbnailUrl` (fallback `coverUrl`); now-playing: `coverUrl`.
- A "Continue listening" section at the top of the Library screen, fed by `GET /library/continue`.
- In-progress marking on rows: derived from the existing `progress` (no contract dependency).

## Open questions for the server side

- `/library/continue`: return a `LibraryItemPage` (for symmetry / future paging) or a plain array?
- Does the shelf want `Progress.updatedAt` for a "last listened" label, or is order enough for v1?
- Thumbnail size/format policy (a fixed size vs. a couple of buckets); RGB-565-friendly encoding
  helps low-end decode (#65) but is a server-rendering detail.

## Next step

Take this into `ratatoskr-server` as a contract change (openapi.yaml + server SPEC section 6
versioning), then regenerate the Kotlin client and build the app side against it.
