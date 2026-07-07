/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText

/**
 * Data-driven waits for the UI integration suite.
 *
 * Screen content arrives from real MockWebServer responses (background call -> ViewModel
 * StateFlow -> recomposition). [ComposeTestRule.waitUntil] advances the (auto-advancing) clock
 * and re-checks the condition, so it sees the awaited node appear even across the transient
 * Material3 load spinners - which clear quickly because MockWebServer answers in milliseconds.
 * This is the reliable synchronisation primitive here; `waitForIdle` alone would risk hanging
 * while a spinner is briefly on screen.
 */

private const val DEFAULT_TIMEOUT_MS = 10_000L

fun ComposeTestRule.awaitTag(tag: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS) =
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

fun ComposeTestRule.awaitText(text: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS) =
    waitUntil(timeoutMillis) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

fun ComposeTestRule.awaitContentDescription(cd: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS) =
    waitUntil(timeoutMillis) { onAllNodesWithContentDescription(cd).fetchSemanticsNodes().isNotEmpty() }
