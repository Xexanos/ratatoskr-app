/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString

/**
 * Data-driven waits for the UI integration suite.
 *
 * Screen content arrives from real MockWebServer responses (background call -> ViewModel
 * StateFlow -> recomposition). [ComposeTestRule.waitUntil] advances the (auto-advancing) clock
 * and re-checks the condition, so it sees the awaited node appear even across the transient
 * Material3 load spinners - which clear quickly because MockWebServer answers in milliseconds.
 * This is the reliable synchronisation primitive here; `waitForIdle` alone would risk hanging
 * while a spinner is briefly on screen.
 *
 * Every wait dumps the current semantics tree into the failure when it times out: a bare
 * ComposeTimeoutException does not say which screen the app was on, whereas the tree turns any
 * CI failure into a self-explaining one (it is what pinpointed the on-device certificate bug).
 */

private const val DEFAULT_TIMEOUT_MS = 10_000L

fun ComposeTestRule.awaitTag(tag: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS) =
    awaitCondition("node with testTag '$tag'", timeoutMillis) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

fun ComposeTestRule.awaitText(text: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS) =
    awaitCondition("node with text '$text'", timeoutMillis) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

fun ComposeTestRule.awaitContentDescription(cd: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS) =
    awaitCondition("node with contentDescription '$cd'", timeoutMillis) {
        onAllNodesWithContentDescription(cd).fetchSemanticsNodes().isNotEmpty()
    }

/** Waits for [condition], and on timeout fails with the rendered screen instead of a bare timeout. */
private fun ComposeTestRule.awaitCondition(
    description: String,
    timeoutMillis: Long,
    condition: () -> Boolean,
) {
    try {
        waitUntil(timeoutMillis) { condition() }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError(
            "Timed out after ${timeoutMillis}ms waiting for $description. Current screen:\n" +
                onRoot(useUnmergedTree = true).printToString(),
            e,
        )
    }
}
