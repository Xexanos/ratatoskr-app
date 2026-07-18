# Contract proposal: cover-proxy endpoint + continue-listening ordering

**Status:** draft (app-side). This sketches what the app needs from the server contract for
issues #55 (covers in the list) and #52 (actively-listened books lead the list, specially
marked). It drives a change to `ratatoskr-server/contract/openapi.yaml`, which follows the
server's own versioning rules (server SPEC section 6). Nothing here is built app-side until the
contract lands — the app is a thin remote.

Both parts are **additive and backward-compatible** (each adds a new endpoint), so they are
oasdiff-clean and fit a **minor** contract bump, like the 1.1.0 refresh-token-rotation change.
They are independent and can ship separately.

## Current contract (for reference)

`GET /library/items` takes `q`, `limit` (≤100, default 50), `cursor` — no ordering control. It
returns a `LibraryItemPage` (`items` + nullable `nextCursor`). `LibraryItemSummary` already has:

```yaml
coverUrl:  { type: string, nullable: true, description: "Absolute URL served by Ratatoskr; may require the token." }
progress:  $ref Progress   # Progress = { positionSeconds: double, isFinished: bool }
```

`progress` already tells the client whether a book is in progress. Covers, **despite the field,
are not served yet**: no cover-image path exists among the ten defined paths, and the server does
not populate `coverUrl` today (it is effectively always null). So the two gaps are (A) a
cover-proxy endpoint with scaling and (B) server-side ordering.

---

## Part A — cover delivery: a size-parameterized cover-proxy endpoint (#55)

**Current state.** `LibraryItemSummary.coverUrl` exists in the schema (nullable, "served by
Ratatoskr"), but the server does **not implement cover serving yet** — `coverUrl` is effectively
always null today, and no cover-image path is defined in the contract. So this is *build new*, not
*document existing*.

**Why `coverUrl` must be a Ratatoskr URL, not ABS.** The phone has no Audiobookshelf credentials
(the whole projection exists so it never needs them) and is network-isolated to Ratatoskr (ABS may
sit on a private network it cannot reach). A direct ABS link — or a redirect to ABS — breaks both.
So Ratatoskr **proxies** covers: it fetches the image from ABS server-side with its own ABS
credentials and serves it under its own auth (the "may require the token" in the field description).

**Proposed change: a cover-proxy endpoint, scaled by height.**

```yaml
/library/items/{itemId}/cover:
  get:
    operationId: getLibraryItemCover
    summary: Cover image for an item, proxied from Audiobookshelf (optionally scaled)
    parameters:
      - $ref: "#/components/parameters/ItemId"
      - name: h
        in: query
        description: |
          Target max height in pixels for a scaled variant. The client sets it from the row
          height × device density, so low-density devices request fewer pixels and high-density
          devices more. Omitted = full/original. The server clamps to a small set of buckets.
        schema: { type: integer, minimum: 1, maximum: 2048 }
    responses:
      "200": { description: Image bytes, content: { image/*: {} } }
      "401": { $ref: Unauthorized }
      "404": { $ref: NotFound }
      "502": { $ref: UpstreamError }
```

`coverUrl` becomes an absolute URL pointing at this path (server-provided, so the client stays
dumb); the client appends `?h=<px>` for a scaled variant and omits it for full size on now-playing.

**Scale by height, not width.** Covers are portrait; in a list the row height is the fixed
dimension and width follows the aspect ratio, so a height target keeps row heights consistent
across covers of varying ratios. The client requests `h = rowHeightDp × density`, which is what
makes the result density-correct — low-density devices ask for fewer pixels, exactly as expected.

**Scaling is delegable to ABS.** ABS's own cover endpoint accepts a `height` parameter and scales
on the fly, so Ratatoskr can pass the client's `h` straight upstream (`?height=`) instead of doing
image work itself. It should still **cache** the result and **clamp `h` to a few buckets** (e.g.
nearest of {128, 256, 384, original}) so it neither hammers ABS nor caches unbounded sizes. (Worth
confirming `height` support against the deployed ABS version.)

**Rejected alternatives.**
- A fixed `thumbnailUrl` field (one pixel size for every device) — fails the density point above.
- A direct ABS link or a redirect to ABS — breaks the thin-client / no-ABS-credentials / network
  isolation model.

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
/library/in-progress:
  get:
    operationId: listInProgressItems
    summary: Books currently in progress, most-recently-listened first
    parameters:
      - name: limit
        in: query
        required: false
        schema: { type: integer, minimum: 1, maximum: 50, default: 25 }
    responses:
      "200": { $ref: "#/components/schemas/LibraryItemPage" }
```

- **Name: `/library/in-progress`** — a self-describing REST noun. ABS's own term is "Continue
  Listening", but that is a personalized *shelf* served via `/api/libraries/{id}/personalized`,
  not a REST path — so the vocabulary is ABS's while the path is ours, and a generic name reads
  clearly without ABS knowledge. (`/library/continue` was verb-y and vague.)
- **Response = `LibraryItemPage`**, reused for consistency with `/library/items` (same client
  type and rendering). The shelf returns one complete page — `nextCursor` is null — so the
  envelope honestly says "that's all". If shelf-level fields are ever needed later, a superset
  schema (`allOf [LibraryItemPage, { …new optional fields }]`) stays non-breaking and
  oasdiff-clean; a bare array could never gain a sibling field, which is why it was rejected.
- Server returns only in-progress items (not finished, position > 0), ordered by ABS recency.
- A shelf, not the whole library: bounded by `limit`, so no pagination in practice.
- Cleanly decouples the shelf from the browse list, which stays alphabetical and paginated as-is.
- The app renders this as a section on top of the Library screen; the full list follows unchanged.

**Alternative considered.** A `sort=recent` query param on `/library/items`. Lighter (no new path),
but it entangles the shelf with the main list's pagination and still needs the server-side recency
data. The dedicated endpoint keeps the two concerns separate.

**Optional add-on.** Expose recency for display ("Continue at 3:24 · 2 days ago") by adding an
optional `updatedAt` (date-time) to `Progress`. Additive; only if the shelf UX wants the timestamp.

---

## App-side consumption (once the contract lands)

- Library rows and the shelf: `coverUrl` with `?h=<rowHeightPx>` (a small Coil interceptor sets
  `h` from the resolved row height); now-playing: `coverUrl` at full size (no `h`).
- A "Continue listening" section at the top of the Library screen, fed by `GET /library/in-progress`.
- In-progress marking on rows: derived from the existing `progress` (no contract dependency).

## Open questions for the server side

- Cover buckets: which height buckets does the server clamp `h` to, and does it re-encode
  (JPEG quality/format) for smaller payloads? Delegate `?height=` to ABS vs. scale in Ratatoskr.
- Confirm the deployed ABS version exposes a `height` parameter on its cover endpoint.
- Does the shelf want `Progress.updatedAt` for a "last listened" label, or is order enough for v1?

## Next step

Take this into `ratatoskr-server` as a contract change (openapi.yaml + server SPEC section 6
versioning), then regenerate the Kotlin client and build the app side against it.
