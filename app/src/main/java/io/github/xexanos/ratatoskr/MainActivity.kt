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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.xexanos.ratatoskr.di.AppContainer
import io.github.xexanos.ratatoskr.ui.navigation.RatatoskrNavHost
import io.github.xexanos.ratatoskr.ui.navigation.Routes
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as RatatoskrApp).container
        val startDestination = decideStartDestination(container)

        setContent {
            RatatoskrTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Surface(modifier = Modifier.padding(innerPadding)) {
                        RatatoskrNavHost(container = container, startDestination = startDestination)
                    }
                }
            }
        }
    }

    /**
     * Launch routing (SPEC section 13): no trusted server -> connect; no stored tokens ->
     * sign-in; otherwise the library. Reads are one-shot and fast (DataStore caches).
     */
    private fun decideStartDestination(container: AppContainer): String = runBlocking {
        val hasTrustedServer = container.connectionStore.currentServerConfig() != null &&
            container.connectionStore.fingerprint() != null
        when {
            !hasTrustedServer -> Routes.CONNECT
            container.tokenStore.authSession() == null -> Routes.SIGN_IN
            else -> Routes.LIBRARY
        }
    }
}
