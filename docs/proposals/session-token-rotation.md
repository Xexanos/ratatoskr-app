# Contract proposal: return rotated tokens in `Session` responses

Target: `ratatoskr-server/contract/openapi.yaml` (and server SPEC sections 8 and 14).
Origin: app-side auth design (app SPEC section 5). Additive, non-breaking (minor bump
within `/v1`); oasdiff-clean.

## Problem

`StartSessionRequest.refreshToken` lets a client hand its Audiobookshelf refresh token to
the server so the sync loop can renew the access token during long unattended playback.
Audiobookshelf rotates the refresh token on every use — so once the server refreshes, the
client's stored refresh token is dead, and there is currently no channel through which the
client learns the new one. After a long session the client's next `/auth/refresh` fails
and the user is forced to sign in again. This is the open risk flagged in server SPEC
section 14 ("Refresh-token rotation").

## Proposed change

Add two optional fields to the `Session` schema:

```yaml
    Session:
      type: object
      required: [itemId, speakerId, state, positionSeconds, durationSeconds, updatedAt]
      properties:
        # ... existing properties unchanged ...
        accessToken:
          type: string
          description: |
            Present only when Ratatoskr has rotated the caller's Audiobookshelf tokens
            since the last Session response it returned. Clients must adopt this token
            immediately and discard the previous one.
        refreshToken:
          type: string
          description: |
            Present only when Ratatoskr has rotated the caller's Audiobookshelf tokens
            since the last Session response it returned. Clients must adopt this token
            immediately and discard the previous one.
```

Because every playback operation (`getCurrentSession`, `startSession`, `pauseSession`,
`resumeSession`, `seekSession`) already returns a `Session`, rotated tokens reach the
client through the polling it does anyway for the now-playing view. No new endpoint.

## Server-side behavior

- When the sync loop refreshes the session user's tokens, mark the new pair as
  "not yet delivered".
- Include `accessToken`/`refreshToken` in `Session` responses **to that user** until one
  such response has been sent, then omit them again. (v1 has exactly one session and one
  session user, so "to that user" is simply the authenticated caller of a session
  endpoint.)
- Never log these fields (they fall under the existing log-redaction rule, server SPEC
  section 14).

## Client-side protocol (for reference; implemented in the app)

- While a session is active the client never calls `/auth/refresh` itself; it adopts
  tokens only from `Session` responses.
- On a 401 during an active session the client first re-fetches `getCurrentSession` to
  pick up a rotated token, and only falls back to `/auth/refresh` if that yields nothing.
- Immediately before `stopSession` the client fetches `getCurrentSession` once to adopt
  the final token state before the server discards its in-memory copy. The narrow race
  between that poll and the stop is accepted; the client recovers with a single
  `/auth/refresh` attempt and, failing that, a re-login prompt.

## Compatibility

- Old app / new server: old clients ignore unknown fields (contract requirement) — they
  keep their current behavior, including the pre-existing rotation risk. No regression.
- New app / old server: the fields simply never appear; the app must not assume them
  (app SPEC section 5).
