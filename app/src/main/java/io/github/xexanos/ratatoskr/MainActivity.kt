/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.runtime.CompositionLocalProvider
import io.github.xexanos.ratatoskr.di.AppContainer
import io.github.xexanos.ratatoskr.ui.KnotLoader
import io.github.xexanos.ratatoskr.ui.common.LocalCoverImageLoader
import io.github.xexanos.ratatoskr.ui.rememberDelayedVisible
import io.github.xexanos.ratatoskr.ui.navigation.RatatoskrNavHost
import io.github.xexanos.ratatoskr.ui.navigation.Route
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as RatatoskrApp).container

        setContent {
            CompositionLocalProvider(LocalCoverImageLoader provides container.coverImages.imageLoader) {
                RatatoskrTheme {
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            // Bridges Compose testTags to UiAutomator resource-ids so the
                            // black-box E2E harness (ratatoskr-e2e, Maestro/UiAutomator) can
                            // locate the same elements the Compose UI tests find by testTag.
                            .semantics { testTagsAsResourceId = true },
                    ) { innerPadding ->
                        Surface(
                            modifier = Modifier
                                .padding(innerPadding)
                                // Marks the system-bar insets as spent, so a screen that applies
                                // one of its own (sign-in lifts its pinned action with
                                // imePadding) adds only what is left rather than the bars twice.
                                .consumeWindowInsets(innerPadding),
                        ) {
                            // Resolve the start route off the main thread (see decideStartDestination),
                            // showing a brief loader instead of blocking onCreate.
                            var startDestination by remember { mutableStateOf<Route?>(null) }
                            LaunchedEffect(Unit) {
                                startDestination = decideStartDestination(container)
                            }
                            when (val dest = startDestination) {
                                // Resolving the start route is normally sub-second; only show the
                                // loader if it takes long enough to be worth it, so it never flashes.
                                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (rememberDelayedVisible(active = true)) {
                                        KnotLoader(label = stringResource(R.string.app_loading))
                                    }
                                }
                                else -> RatatoskrNavHost(container = container, startDestination = dest)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Launch routing (SPEC section 13): no trusted server -> connect; no stored tokens ->
     * sign-in; otherwise the library. Runs off the main thread: on a cold start the DataStore
     * reads and the Keystore-backed decrypt behind hasCredential() are blocking, so resolving
     * this inside onCreate would risk dropped launch frames or an ANR.
     */
    private suspend fun decideStartDestination(container: AppContainer): Route =
        withContext(Dispatchers.IO) {
            // The one-time /v1 -> /v2 migration (SPEC section 5) runs before the credential
            // check: on the first launch after the update it discards the stranded
            // Audiobookshelf tokens, so the routing below lands on a pre-filled sign-in with
            // its "app updated" notice. Every other launch it is a no-op.
            container.connectionManager.migrateFromV1()
            val hasTrustedServer = container.connectionStore.currentServerConfig() != null &&
                container.connectionStore.fingerprint() != null
            when {
                !hasTrustedServer -> Route.Connect
                !container.credentialStore.hasCredential() -> Route.SignIn
                else -> Route.Library
            }
        }
}
