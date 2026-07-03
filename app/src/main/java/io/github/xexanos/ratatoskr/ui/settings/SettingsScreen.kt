/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String? = null,
    val certForgotten: Boolean = false,
    val signedOut: Boolean = false,
)

class SettingsViewModel(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                serverUrl = connectionManager.connectionStore.currentServerConfig()?.baseUrl,
            )
        }
    }

    fun forgetCertificate() {
        viewModelScope.launch {
            connectionManager.connectionStore.forgetFingerprint()
            connectionManager.invalidate()
            _uiState.value = _uiState.value.copy(certForgotten = true)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            connectionManager.tokenStore.clear()
            _uiState.value = _uiState.value.copy(signedOut = true)
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onReTrust: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.certForgotten) { if (state.certForgotten) onReTrust() }
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }

    SettingsContent(
        state = state,
        onForgetCertificate = viewModel::forgetCertificate,
        onSignOut = viewModel::signOut,
    )
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onForgetCertificate: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Server", style = MaterialTheme.typography.labelLarge)
        Text(state.serverUrl ?: "Not configured")

        OutlinedButton(
            onClick = onForgetCertificate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Forget certificate and re-trust")
        }
        Button(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign out")
        }
    }
}

// --- Previews (render in Android Studio without a running server) --------------------------

@Preview(name = "Settings", widthDp = 360, heightDp = 800)
@Composable
private fun SettingsPreview() = RatatoskrTheme {
    Surface {
        SettingsContent(
            state = SettingsUiState(serverUrl = "https://ratatoskr.home:8080"),
            onForgetCertificate = {},
            onSignOut = {},
        )
    }
}
