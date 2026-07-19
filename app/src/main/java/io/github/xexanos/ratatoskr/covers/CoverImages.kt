/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.covers

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okio.Path.Companion.toOkioPath
import java.io.IOException

/**
 * Owns cover-image loading: one app-lifetime Coil [ImageLoader] and its disk cache.
 *
 * The loader itself never rebuilds; it follows server/certificate changes through
 * [currentCallFactory], a per-request delegate to the current [RatatoskrClient]'s
 * coversCallFactory (TOFU trust + bearer/refresh auth, own dispatcher). A cover request
 * with no configured client fails that single request - Coil renders the error state,
 * which is the placeholder tile.
 *
 * Caching policy (decided in the #55 design session):
 * - Disk: 50 MB LRU under cacheDir/covers - over a thousand scaled covers; one long-lived
 *   [DiskCache] instance, because two loaders on the same directory are not allowed.
 * - HTTP cache headers are IGNORED (Coil's default): verified against the ABS source, its
 *   cover endpoint sends no Cache-Control/ETag/Last-Modified on the proxy's request path
 *   (only `?ts=`-stamped web-client requests get max-age), so "respect headers" would mean
 *   "never cache". Consequence, accepted for v1: a cover replaced in ABS stays stale here
 *   until LRU eviction or a manual/sign-out cache clear.
 * - Memory: Coil's default size (scales with the device's memory class, #65-friendly).
 * - [clear] wipes both caches: used by sign-out and forget-server (a signed-out device
 *   keeps no artwork of the library it left) and by the Settings "clear image cache" row.
 */
class CoverImages(
    private val appContext: Context,
    private val currentCallFactory: () -> Call.Factory?,
) {

    private val diskCache: DiskCache by lazy {
        DiskCache.Builder()
            .directory(appContext.cacheDir.resolve("covers").toOkioPath())
            .maxSizeBytes(50L * 1024 * 1024)
            .build()
    }

    // The fetcher factory memoizes its callFactory lambda, so hand it one stable delegating
    // factory that re-reads the current client per call - this is what makes a server or
    // certificate change take effect without rebuilding the loader.
    private val delegatingCallFactory = Call.Factory { request ->
        val delegate = currentCallFactory()
            ?: throw IOException("No server configured; cannot load covers")
        delegate.newCall(request)
    }

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .components {
                add(CoverHeightInterceptor())
                add(OkHttpNetworkFetcherFactory(callFactory = { delegatingCallFactory }))
            }
            .diskCache { diskCache }
            .crossfade(true)
            .build()
    }

    /** Empties the memory and disk caches. DiskCache.clear does file IO - keep it off main. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        imageLoader.memoryCache?.clear()
        diskCache.clear()
    }
}
