# Ratatoskr app

Android client for the Ratatoskr server. This app is a thin remote: it sends commands and
shows state. All domain logic (Audiobookshelf, Sonos control, position mapping, progress
sync) lives in the server.

Authoritative context — read before doing anything:
- `docs/SPEC.md` — goal, scope, architecture, auth, certificate trust, constraints.
- The server's API contract is the single source of truth for communication:
  `ratatoskr-server/contract/openapi.yaml` (OpenAPI 3.0.3). The Kotlin client is generated
  from it and must not be hand-edited.
- The server's brief, `ratatoskr-server/docs/SPEC.md`, is referenced throughout the app spec
  (especially auth in section 8 and security in section 14).

Working agreements:
- Talk only to the Ratatoskr server, over HTTPS. Never call Audiobookshelf or Sonos directly.
- No domain logic on the phone; if it needs Sonos/ABS knowledge, it is a server concern.
- Free-software dependencies only: no Google Play Services, no trackers. Must build
  reproducibly for F-Droid. License is GPL-3.0-or-later.
- Never log tokens, passwords, or Authorization headers.
- The technology stack (SPEC section 12) and the screen and module structure (SPEC
  section 13) are decided and recorded in the SPEC; changes to them go through the SPEC
  first.
