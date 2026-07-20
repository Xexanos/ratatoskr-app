/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

/**
 * The single owner of what the app knows about the server's cover endpoint: how to reach it
 * ([resolve]) and how to recognise a URL that points at it ([matches]). Bound once to a server
 * origin, so nothing above the wrapper re-derives the cover path or the origin (SPEC section 4).
 */
class CoverEndpoint internal constructor(private val baseUrl: String) {

    /**
     * Resolves the contract's `coverUrl` into a loadable absolute URL. Since contract 1.3.x the
     * server sends a path relative to its own origin (e.g. `/v1/library/items/{id}/cover`); this
     * absorbs [baseUrl] so the domain model always carries an absolute, bearer-authenticated URL
     * and no UI code needs to know the server origin. An already-absolute value passes through
     * untouched (tolerant reader: contract 1.1.0 documented an absolute URL, a future server could
     * again send one).
     */
    internal fun resolve(coverUrl: String?): String? = when {
        coverUrl == null -> null
        coverUrl.startsWith("http://") || coverUrl.startsWith("https://") -> coverUrl
        else -> baseUrl.trimEnd('/') + (if (coverUrl.startsWith("/")) coverUrl else "/$coverUrl")
    }

    companion object {
        // The cover proxy's path shape, mirrored from the contract's `GET
        // /library/items/{id}/cover`. The generated LibraryApi is regenerated from that contract,
        // so CoverEndpointTest's drift guard turns red if a contract rename leaves these stale.
        private const val ITEMS_SEGMENT = "/library/items/"
        private const val COVER_SUFFIX = "/cover"

        /**
         * Whether [url] points at the cover endpoint - the only URL shape the `?h=` height
         * parameter applies to. Matched host- and version-agnostically (path substring only):
         * knowing the server origin is [resolve]'s concern, not this predicate's, so the app's
         * cover-image interceptor can stay a lifetime singleton that survives a server change
         * without rebuilding, an already-absolute cover URL from a future server still matches,
         * and a `/v1` -> `/v2` bump does not break recognition.
         */
        fun matches(url: String): Boolean {
            val path = url.substringBefore('?')
            return path.endsWith(COVER_SUFFIX) && path.contains(ITEMS_SEGMENT)
        }
    }
}
