/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.covers

/**
 * The cover height buckets requested from the server's cover proxy (`?h=`). The server passes
 * `h` straight to Audiobookshelf's on-the-fly scaler without clamping, so bucket discipline is
 * the app's job: quantizing to a few sizes keeps any future server/proxy cache effective and
 * avoids asking ABS for a new scale per device density. Rounded UP so the image is never
 * upscaled; Coil still decodes down to the actual view size, which is what bounds bitmap
 * memory on low-end devices.
 */
private val COVER_HEIGHT_BUCKETS = intArrayOf(128, 256, 512, 1024)

/**
 * The bucket for a resolved view height in pixels. Heights above the largest bucket clamp to
 * it - a deliberate deviation from the contract's "omit h for full size" hint, because an
 * unclamped original can be 3000x3000 for no visible gain even on the now-playing screen.
 */
internal fun coverHeightBucket(heightPx: Int): Int =
    COVER_HEIGHT_BUCKETS.firstOrNull { heightPx <= it } ?: COVER_HEIGHT_BUCKETS.last()

/** Appends the bucketed height for [heightPx] as the cover proxy's `h` query parameter. */
internal fun appendCoverHeightParam(url: String, heightPx: Int): String {
    val separator = if ('?' in url) '&' else '?'
    return "$url${separator}h=${coverHeightBucket(heightPx)}"
}
