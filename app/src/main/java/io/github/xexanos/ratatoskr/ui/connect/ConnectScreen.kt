/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.connect

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.xexanos.ratatoskr.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.CertificateInfo
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
import io.github.xexanos.ratatoskr.network.tls.CertificateInspector
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

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

    LaunchedEffect(state) {
        if (state is ConnectUiState.Trusted) onTrusted()
    }

    ConnectContent(
        state = state,
        onInspect = viewModel::inspect,
        onConfirm = viewModel::confirm,
        onReset = viewModel::reset,
    )
}

@Composable
private fun ConnectContent(
    state: ConnectUiState,
    onInspect: (String) -> Unit,
    onConfirm: (String, String) -> Unit,
    onReset: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("https://") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ratatoskr_logo),
            contentDescription = null,
            modifier = Modifier
                .size(180.dp)
                .align(Alignment.CenterHorizontally)
                .padding(top = 16.dp),
        )
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
                Button(onClick = { onInspect(url) }) { Text("Connect") }

            ConnectUiState.Inspecting -> CircularProgressIndicator()

            is ConnectUiState.Confirm -> {
                Text("Confirm this certificate", style = MaterialTheme.typography.titleMedium)
                Text("Subject: ${s.info.subject}")
                Text("Issuer: ${s.info.issuer}")
                Text("Valid until: ${s.info.notAfter}")
                Text("SHA-256 fingerprint:", style = MaterialTheme.typography.labelLarge)
                Text(s.info.sha256Fingerprint, fontFamily = FontFamily.Monospace)
                Button(onClick = { onConfirm(s.baseUrl, s.info.sha256Fingerprint) }) {
                    Text("Trust and continue")
                }
            }

            is ConnectUiState.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onReset) { Text("Try again") }
            }
        }
    }
}

// --- Previews (render in Android Studio without a running server) --------------------------

private val previewCert = CertificateInfo(
    subject = "CN=ratatoskr.home",
    issuer = "CN=ratatoskr.home",
    notBefore = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    notAfter = OffsetDateTime.parse("2027-01-01T00:00:00Z"),
    sha256Fingerprint = "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90:" +
        "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
)

@Preview(name = "Connect — idle", widthDp = 360, heightDp = 800)
@Composable
private fun ConnectIdlePreview() = RatatoskrTheme {
    Surface { ConnectContent(ConnectUiState.Idle, {}, { _, _ -> }, {}) }
}

@Preview(name = "Connect — confirm certificate", widthDp = 360, heightDp = 800)
@Composable
private fun ConnectConfirmPreview() = RatatoskrTheme {
    Surface { ConnectContent(ConnectUiState.Confirm("https://ratatoskr.home:8080", previewCert), {}, { _, _ -> }, {}) }
}

@Preview(name = "Connect — error", widthDp = 360, heightDp = 800)
@Composable
private fun ConnectErrorPreview() = RatatoskrTheme {
    Surface { ConnectContent(ConnectUiState.Error("Could not read the server certificate."), {}, { _, _ -> }, {}) }
}
