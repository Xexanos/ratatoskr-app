/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import io.github.xexanos.ratatoskr.ui.theme.reducedMotionFromScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnotLoaderTest {

    @Test
    fun `reduced motion only when animator scale is zero`() {
        assertTrue(reducedMotionFromScale(0f))
        assertFalse(reducedMotionFromScale(1f))
        assertFalse(reducedMotionFromScale(0.5f))
        assertFalse(reducedMotionFromScale(2f))
    }

    @Test
    fun `wrapped range that stays inside the path is returned unchanged`() {
        val ranges = wrappedRanges(start = 100f, stop = 150f, length = 1000f)
        assertEquals(listOf(100f to 150f), ranges)
    }

    @Test
    fun `range straddling the seam is split into two in-bounds ranges`() {
        // start 980, stop 1020 -> wraps to 980..1000 and 0..20
        val ranges = wrappedRanges(start = 980f, stop = 1020f, length = 1000f)
        assertEquals(listOf(980f to 1000f, 0f to 20f), ranges)
    }

    @Test
    fun `negative distances wrap into the path before the seam check`() {
        // start -30, stop 10 -> 970..1000 and 0..10
        val ranges = wrappedRanges(start = -30f, stop = 10f, length = 1000f)
        assertEquals(listOf(970f to 1000f, 0f to 10f), ranges)
    }

    @Test
    fun `all emitted range endpoints lie within zero to length`() {
        val length = 1000f
        for (i in 0 until 30) {
            val stop = 40f - i * 5f // sweeps well below zero to exercise wrapping
            val start = stop - 8f
            for ((s, e) in wrappedRanges(start, stop, length)) {
                assertTrue("start $s in bounds", s in 0f..length)
                assertTrue("stop $e in bounds", e in 0f..length)
                assertTrue("start <= stop", s <= e)
            }
        }
    }

    @Test
    fun `zero length yields no ranges`() {
        assertTrue(wrappedRanges(0f, 10f, 0f).isEmpty())
    }
}
