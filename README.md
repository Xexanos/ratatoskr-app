# Ratatoskr (Android app)

The Android client for [Ratatoskr](https://github.com/Xexanos/ratatoskr-server), a
companion service that plays [Audiobookshelf](https://www.audiobookshelf.org/)
audiobooks on [Sonos](https://www.sonos.com/) speakers and keeps your listening
progress in sync.

This app is a thin remote. It lets you sign in with your Audiobookshelf account, browse
your library, pick a Sonos speaker, and control playback. All the real work (talking to
Audiobookshelf, driving the speakers, mapping and syncing the position) happens in the
Ratatoskr server; the audio itself streams directly from Audiobookshelf to the speaker
and never passes through your phone.

## About the name

In Norse mythology, Ratatoskr is the squirrel that runs up and down the world tree
Yggdrasil, carrying messages between the eagle at the crown and the serpent at the roots
- a quick messenger between two parties that cannot talk directly. That is what the
project does between Audiobookshelf and your Sonos speakers. This repository is the phone
end of that link.

## Why an app at all, on Android

iPhone users can already send Audiobookshelf audio to Sonos with AirPlay. Android has no
native AirPlay sender and Sonos does not support Google Cast, so there is no built-in
path. Ratatoskr solves this from the server side, which is exactly why an Android client
is worth building: it is the platform where nothing else works.

## Requirements

- A running [Ratatoskr server](https://github.com/Xexanos/ratatoskr-server) on your local
  network.
- An Audiobookshelf account on the server that Ratatoskr points at. You sign in with those
  credentials; the app never stores your password, only the tokens the server returns.
- Sonos or IKEA SYMFONISK speakers on the same network as the server.

## Status

Early work in progress. The first version targets: connect to a server, sign in, browse
and search the library, start a book on a speaker, and control playback (pause, resume,
seek, stop) with the reached position synced back to Audiobookshelf.

## Building

Open the project in Android Studio and build the `app` module, or build from the command
line with Gradle. The app is designed to build reproducibly from source with only
free-software dependencies, so it can be published on F-Droid; it is also intended for the
Play Store.

## API

The app talks only to the Ratatoskr server, over the HTTP API defined in the server's
[`contract/openapi.yaml`](https://github.com/Xexanos/ratatoskr-server/blob/main/contract/openapi.yaml).
The Kotlin client is generated from that contract; see [`docs/SPEC.md`](docs/SPEC.md).

## Contributing

See [`docs/SPEC.md`](docs/SPEC.md) for the design, the scope of the first version, and the
constraints the implementation must respect.

## License

Licensed under the GNU General Public License, version 3 or later (GPL-3.0-or-later).
See [`LICENSE`](LICENSE).

## Disclaimer

This is an independent project. It is not affiliated with, endorsed by, or associated with
Sonos Inc. or the Audiobookshelf project. "Sonos" and "Audiobookshelf" are used only to
describe compatibility.
