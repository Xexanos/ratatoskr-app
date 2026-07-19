/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.covers

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension

/**
 * Appends the bucketed `?h=` parameter to cover requests from the size Coil resolved out of
 * the layout, so call sites stay dumb: every surface renders `CoverImage` and the right
 * server-side scale falls out of the composable's measured height (list row, shelf,
 * now-playing - no per-screen size constants that could drift). An unresolved height
 * (Dimension.Undefined) leaves the URL untouched, which the server serves as full size.
 *
 * Runs before Coil's engine, so the rewritten URL is also the memory/disk cache key: two
 * surfaces whose heights land in the same bucket share one cached image.
 */
internal class CoverHeightInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val height = chain.size.height
        if (data !is String || !isCoverUrl(data) || height !is Dimension.Pixels) {
            return chain.proceed()
        }
        val request = chain.request.newBuilder()
            .data(appendCoverHeightParam(data, height.px))
            .build()
        return chain.withRequest(request).proceed()
    }

    /**
     * Only the cover proxy understands `h`; leave any other image URL alone. Matched on the
     * proxy's path shape rather than a bare `/cover` suffix, so a pass-through absolute URL
     * from a foreign origin (the wrapper's tolerant-reader case) is not decorated with a
     * parameter only Ratatoskr defines.
     */
    private fun isCoverUrl(url: String): Boolean {
        val path = url.substringBefore('?')
        return path.endsWith("/cover") && path.contains("/library/items/")
    }
}
