/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.test.core.app.ApplicationProvider
import io.github.xexanos.ratatoskr.RatatoskrApp
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Resets the app's persisted state before each test, so every test starts from a clean
 * install (`MainActivity` routes to the connect screen). Must be the OUTER rule in the chain
 * so it runs before the activity launches.
 *
 * The reset goes through the process singleton container's own `reset()` - not by deleting
 * the DataStore files - because DataStore keeps an in-memory cache: a prior test's
 * `saveTrustedServer` lives in that cache, and deleting the file on disk would not evict it,
 * so the next test would still read the stale server (a dead MockWebServer port) and skip
 * connect. Delegating to `AppContainer.reset()` keeps the set of cleared stores defined next
 * to where the stores themselves are declared, so a newly added store is covered there.
 */
class ClearAppStateRule : ExternalResource() {
    override fun before() {
        val app = ApplicationProvider.getApplicationContext<RatatoskrApp>()
        runBlocking { app.container.reset() }
    }
}
