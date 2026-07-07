# Contract proposal: return rotated tokens in `Session` responses - ACCEPTED (landed in server 1.1.0)

**Status:** accepted and merged. The change shipped in the server's OpenAPI contract as
version 1.1.0 (Xexanos/ratatoskr-server#11). This file is kept as a historical record; the
authoritative descriptions now live in:

- `ratatoskr-server/contract/openapi.yaml` - the `RotatedTokens` schema, the optional
  `Session.rotatedTokens` field, and the `stopSession` 200 response.
- `ratatoskr-server/docs/SPEC.md` - section 8 (handover protocol) and section 14 (log
  redaction of token-bearing response bodies).
- This repo's `docs/SPEC.md`, section 5 - the app's half of the protocol.

## What changed between this proposal and the merged design

The original proposal (below, preserved for context) was refined during review:

- **Adoption-based delivery, not send-once.** The server keeps including the rotated pair
  until the client authenticates with the new access token, so a dropped or half-read
  response cannot strand the client. (The proposal marked delivery at send time, which
  could lose the pair permanently.)
- **A nested `rotatedTokens` object** (both members required) instead of two independent
  optional fields, so the schema encodes the both-or-neither invariant.
- **`stopSession` returns 200 with a final `Session`** carrying the pending pair (204 is
  kept for the common case, so the change stays additive/oasdiff-clean). This closes the
  race between the last poll and the stop, which the proposal could only shrink.
- **The 401 recovery is sound and its basis is stated:** Audiobookshelf access tokens are
  stateless and stay valid until their own expiry after rotation, and the sync loop
  refreshes proactively before that expiry - so the client's still-valid old token can
  fetch the rotated pair. TTLs are described version-agnostically.

---

## Problem

`StartSessionRequest.refreshToken` lets a client hand its Audiobookshelf refresh token to
the server so the sync loop can renew the access token during long unattended playback.
Audiobookshelf rotates the refresh token on every use - so once the server refreshes, the
client's stored refresh token is dead, and there is currently no channel through which the
client learns the new one. After a long session the client's next `/auth/refresh` fails
and the user is forced to sign in again. This is the open risk flagged in server SPEC
section 14 ("Refresh-token rotation").

## Proposed change (as originally written; see the merged design above for the final form)

Add optional fields to the `Session` schema so that rotated tokens reach the client through
the playback polling it already does for the now-playing view - no new endpoint. Because
every playback operation (`getCurrentSession`, `startSession`, `pauseSession`,
`resumeSession`, `seekSession`) already returns a `Session`, rotated tokens travel on
responses the client is already fetching.

## Compatibility

- Old app / new server: old clients ignore unknown fields (contract requirement) - they
  keep their current behavior, including the pre-existing rotation risk. No regression.
- New app / old server: the fields simply never appear; the app must not assume them
  (app SPEC section 5).
