/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.connect

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import java.time.format.DateTimeFormatter

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
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ratatoskr_logo),
            contentDescription = null,
            modifier = Modifier
                .size(180.dp)
                .padding(top = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Welcome to Ratatoskr",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect to your Ratatoskr server to play audiobooks on your Sonos speakers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        // readOnly, not disabled: while inspecting/confirming the certificate the URL
        // must stay legible (disabled text is 38% alpha and fails contrast checks),
        // it just must not change under the certificate being confirmed.
        val urlLocked = !(state is ConnectUiState.Idle || state is ConnectUiState.Error)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            singleLine = true,
            readOnly = urlLocked,
            // A plain readOnly field still looks editable; the lock icon signals the URL is
            // held fixed to the certificate being confirmed (the same lock the card uses).
            trailingIcon = if (urlLocked) {
                {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "URL locked while confirming the certificate",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        when (val s = state) {
            ConnectUiState.Idle, ConnectUiState.Trusted ->
                Button(
                    onClick = { onInspect(url) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Connect") }

            ConnectUiState.Inspecting -> {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Reading the server certificate…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is ConnectUiState.Confirm -> CertificateCard(
                info = s.info,
                onTrust = { onConfirm(s.baseUrl, s.info.sha256Fingerprint) },
                onCancel = onReset,
            )

            is ConnectUiState.Error -> {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        s.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun CertificateCard(
    info: CertificateInfo,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "First connection — confirm this certificate",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(16.dp))
            CertField("Subject", info.subject)
            CertField("Issuer", info.issuer)
            CertField("Valid until", info.notAfter.format(DateTimeFormatter.ISO_LOCAL_DATE))
            Spacer(Modifier.height(8.dp))
            Text(
                "SHA-256 fingerprint",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    info.sha256Fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Compare this fingerprint with the one shown by your server before trusting it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onTrust,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Trust and continue") }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun CertField(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
internal fun ConnectIdlePreview() = RatatoskrTheme {
    Surface { ConnectContent(ConnectUiState.Idle, {}, { _, _ -> }, {}) }
}

@Preview(name = "Connect — confirm certificate", widthDp = 360, heightDp = 800)
@Composable
internal fun ConnectConfirmPreview() = RatatoskrTheme {
    Surface { ConnectContent(ConnectUiState.Confirm("https://ratatoskr.home:8080", previewCert), {}, { _, _ -> }, {}) }
}

@Preview(name = "Connect — error", widthDp = 360, heightDp = 800)
@Composable
internal fun ConnectErrorPreview() = RatatoskrTheme {
    Surface { ConnectContent(ConnectUiState.Error("Could not read the server certificate."), {}, { _, _ -> }, {}) }
}
