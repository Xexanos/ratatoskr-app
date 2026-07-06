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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.xexanos.ratatoskr.di.AppContainer
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
            RatatoskrTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Surface(modifier = Modifier.padding(innerPadding)) {
                        // Resolve the start route off the main thread (see decideStartDestination),
                        // showing a brief loader instead of blocking onCreate.
                        var startDestination by remember { mutableStateOf<Route?>(null) }
                        LaunchedEffect(Unit) {
                            startDestination = decideStartDestination(container)
                        }
                        when (val dest = startDestination) {
                            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            else -> RatatoskrNavHost(container = container, startDestination = dest)
                        }
                    }
                }
            }
        }
    }

    /**
     * Launch routing (SPEC section 13): no trusted server → connect; no stored tokens →
     * sign-in; otherwise the library. Runs off the main thread: on a cold start the DataStore
     * reads and the Keystore-backed decrypt in authSession() are blocking, so resolving this
     * inside onCreate would risk dropped launch frames or an ANR.
     */
    private suspend fun decideStartDestination(container: AppContainer): Route =
        withContext(Dispatchers.IO) {
            val hasTrustedServer = container.connectionStore.currentServerConfig() != null &&
                container.connectionStore.fingerprint() != null
            when {
                !hasTrustedServer -> Route.Connect
                container.tokenStore.authSession() == null -> Route.SignIn
                else -> Route.Library
            }
        }
}
