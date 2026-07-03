/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.CertificateInfo
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
import io.github.xexanos.ratatoskr.network.tls.CertificateInspector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConnectUiState {
    data object Idle : ConnectUiState
    data object Inspecting : ConnectUiState
    data class Confirm(val baseUrl: String, val info: CertificateInfo) : ConnectUiState
    data object Trusted : ConnectUiState
    data class Error(val message: String) : ConnectUiState
}

class ConnectViewModel(
    private val inspector: CertificateInspector,
    private val connectionStore: ConnectionStore,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ConnectUiState>(ConnectUiState.Idle)
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    fun inspect(rawUrl: String) {
        val baseUrl = rawUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) return
        _uiState.value = ConnectUiState.Inspecting
        viewModelScope.launch {
            runCatching { inspector.inspect(baseUrl) }
                .onSuccess { _uiState.value = ConnectUiState.Confirm(baseUrl, it) }
                .onFailure {
                    _uiState.value = ConnectUiState.Error(
                        it.message ?: "Could not read the server certificate.",
                    )
                }
        }
    }

    fun confirm(baseUrl: String, fingerprint: String) {
        viewModelScope.launch {
            connectionStore.saveTrustedServer(baseUrl, fingerprint)
            connectionManager.invalidate()
            _uiState.value = ConnectUiState.Trusted
        }
    }

    fun reset() { _uiState.value = ConnectUiState.Idle }
}

@Composable
fun ConnectScreen(
    viewModel: ConnectViewModel,
    onTrusted: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("https://") }

    androidx.compose.runtime.LaunchedEffect(state) {
        if (state is ConnectUiState.Trusted) onTrusted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect to your Ratatoskr server", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            modifier = Modifier.fillMaxWidth(),
        )

        when (val s = state) {
            ConnectUiState.Idle, ConnectUiState.Trusted ->
                Button(onClick = { viewModel.inspect(url) }) { Text("Connect") }

            ConnectUiState.Inspecting -> CircularProgressIndicator()

            is ConnectUiState.Confirm -> {
                Text("Confirm this certificate", style = MaterialTheme.typography.titleMedium)
                Text("Subject: ${s.info.subject}")
                Text("Issuer: ${s.info.issuer}")
                Text("Valid until: ${s.info.notAfter}")
                Text("SHA-256 fingerprint:", style = MaterialTheme.typography.labelLarge)
                Text(s.info.sha256Fingerprint, fontFamily = FontFamily.Monospace)
                Button(onClick = { viewModel.confirm(s.baseUrl, s.info.sha256Fingerprint) }) {
                    Text("Trust and continue")
                }
            }

            is ConnectUiState.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = { viewModel.reset() }) { Text("Try again") }
            }
        }
    }
}
