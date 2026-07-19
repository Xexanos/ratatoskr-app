/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

/**
 * Stable Compose `testTag` handles for elements the UI integration tests must locate but that
 * have no reliable text/semantics handle (dynamic list rows, a placeholder-only search field,
 * an unlabelled slider). Kept in main so production composables and tests reference the same
 * constants. Buttons, text fields with labels, and the transport controls are located by their
 * existing text/contentDescription instead - see the integration suite.
 */
object UiTestTags {
    const val CONNECT_SERVER_URL = "connect_server_url"
    const val LIBRARY_SEARCH = "library_search"
    const val LIBRARY_ROW = "library_row"
    const val SPEAKER_ROW = "speaker_row"
    const val NOWPLAYING_SEEK = "nowplaying_seek"
    const val COVER_PLACEHOLDER = "cover_placeholder"
}
