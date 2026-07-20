/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.covers

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverHeightBucketsTest {

    @Test
    fun `rounds up to the nearest bucket`() {
        assertEquals(128, coverHeightBucket(1))
        assertEquals(128, coverHeightBucket(128))
        assertEquals(256, coverHeightBucket(129))
        assertEquals(256, coverHeightBucket(256))
        assertEquals(512, coverHeightBucket(257))
        assertEquals(512, coverHeightBucket(512))
        assertEquals(1024, coverHeightBucket(513))
    }

    @Test
    fun `caps at the largest bucket instead of requesting the original`() {
        // Deliberate deviation from the contract's "omit h for full size" hint: an unclamped
        // original can be 3000x3000 for no visible gain at now-playing size.
        assertEquals(1024, coverHeightBucket(1024))
        assertEquals(1024, coverHeightBucket(1025))
        assertEquals(1024, coverHeightBucket(5000))
    }

    @Test
    fun `appends the bucketed height as the h query parameter`() {
        assertEquals(
            "https://srv/v1/library/items/42/cover?h=256",
            appendCoverHeightParam("https://srv/v1/library/items/42/cover", 168),
        )
    }

    @Test
    fun `appends with an ampersand when the url already has a query`() {
        assertEquals(
            "https://srv/v1/library/items/42/cover?x=1&h=128",
            appendCoverHeightParam("https://srv/v1/library/items/42/cover?x=1", 96),
        )
    }
}
