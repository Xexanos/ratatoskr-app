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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.data.ConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String? = null,
    val certForgotten: Boolean = false,
    val signedOut: Boolean = false,
    val imageCacheCleared: Boolean = false,
)

class SettingsViewModel(
    private val connectionManager: ConnectionManager,
    /**
     * Empties the cover caches (CoverImages.clear, injected as a function so this ViewModel
     * stays JVM-unit-testable). Run on sign-out and forget-server too: a signed-out device
     * keeps no artwork of the library it left.
     */
    private val clearCoverCache: suspend () -> Unit,
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
            clearCoverCache()
            _uiState.value = _uiState.value.copy(certForgotten = true)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            connectionManager.tokenStore.clear()
            clearCoverCache()
            _uiState.value = _uiState.value.copy(signedOut = true)
        }
    }

    /** Immediate and dialog-free: clearing is lossless, covers simply re-download. */
    fun clearImageCache() {
        viewModelScope.launch {
            clearCoverCache()
            _uiState.value = _uiState.value.copy(imageCacheCleared = true)
        }
    }

    /** Called once the snackbar confirming the clear has been shown. */
    fun imageCacheClearedShown() {
        _uiState.value = _uiState.value.copy(imageCacheCleared = false)
    }
}

// The stateful host (ADR 0001): owns the ViewModel wiring, the navigation effects, and the
// cache-cleared snackbar (snackbars stay in hosts, ADR 0001). The navigation graph renders
// this; previews and goldens render [SettingsScreen].
@Composable
fun SettingsScreenHost(
    viewModel: SettingsViewModel,
    onReTrust: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val cacheClearedMessage = stringResource(R.string.settings_image_cache_cleared)

    LaunchedEffect(state.certForgotten) { if (state.certForgotten) onReTrust() }
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }
    LaunchedEffect(state.imageCacheCleared) {
        if (state.imageCacheCleared) {
            viewModel.imageCacheClearedShown()
            snackbarHostState.showSnackbar(cacheClearedMessage)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsScreen(
            state = state,
            onForgetCertificate = viewModel::forgetCertificate,
            onSignOut = viewModel::signOut,
            onClearImageCache = viewModel::clearImageCache,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// The screen itself: a pure function of [state], previewable without a ViewModel or server.
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onForgetCertificate: () -> Unit,
    onSignOut: () -> Unit,
    onClearImageCache: () -> Unit,
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
            shape = MaterialTheme.shapes.large,
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
                    shape = MaterialTheme.shapes.large,
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
        SectionLabel(stringResource(R.string.settings_section_storage))
        OutlinedButton(
            onClick = onClearImageCache,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_clear_image_cache))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_clear_image_cache_hint),
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

