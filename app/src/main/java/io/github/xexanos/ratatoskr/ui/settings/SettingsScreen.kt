/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.R
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
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Spacer(Modifier.height(24.dp))

        SectionLabel(stringResource(R.string.settings_section_server))
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        state.serverUrl ?: stringResource(R.string.settings_not_configured),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            if (state.serverUrl != null) {
                                R.string.settings_connected
                            } else {
                                R.string.settings_not_connected
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        SectionLabel(stringResource(R.string.settings_section_security))
        OutlinedButton(
            onClick = onForgetCertificate,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_forget_cert))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_forget_cert_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(32.dp))
        SectionLabel(stringResource(R.string.settings_section_account))
        OutlinedButton(
            onClick = onSignOut,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_sign_out))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
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

@Preview(name = "Settings -- not configured", widthDp = 360, heightDp = 800)
@Composable
private fun SettingsUnconfiguredPreview() = RatatoskrTheme {
    Surface {
        SettingsContent(
            state = SettingsUiState(serverUrl = null),
            onForgetCertificate = {},
            onSignOut = {},
        )
    }
}
