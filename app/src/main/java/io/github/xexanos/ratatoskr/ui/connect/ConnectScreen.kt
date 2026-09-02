/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.connect

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.xexanos.ratatoskr.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.CertificateInfo
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
import io.github.xexanos.ratatoskr.network.tls.CertificateInspector
import io.github.xexanos.ratatoskr.ui.BannerKind
import io.github.xexanos.ratatoskr.ui.InlineBanner
import io.github.xexanos.ratatoskr.ui.KnotLoader
import io.github.xexanos.ratatoskr.ui.UiTestTags
import io.github.xexanos.ratatoskr.ui.rememberDelayedVisible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

// The stateful host (ADR 0001): owns the ViewModel wiring and the trusted navigation effect.
// The navigation graph renders this; previews and goldens render [ConnectScreen].
@Composable
fun ConnectScreenHost(
    viewModel: ConnectViewModel,
    onTrusted: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is ConnectUiState.Trusted) onTrusted()
    }

    ConnectScreen(
        state = state,
        onInspect = viewModel::inspect,
        onConfirm = viewModel::confirm,
        onReset = viewModel::reset,
    )
}

// The screen itself: a pure function of [state], previewable without a ViewModel or server.
@Composable
fun ConnectScreen(
    state: ConnectUiState,
    onInspect: (String) -> Unit,
    onConfirm: (String, String) -> Unit,
    onReset: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("https://") }

    // The form scrolls; the action does not. Whichever action the state offers sits in the
    // bottom thumb zone (ux-design: Connect), and imePadding lifts it clear of the software
    // keyboard - under enableEdgeToEdge the window is not resized for the IME, so the inset is
    // applied here.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // "Once the certificate shows, the brand steps aside - that screen belongs to the
            // facts" (ux-design: Connect, decision 4). The welcome block costs 330 dp, which is
            // what pushed the card's closing instruction - compare this fingerprint before
            // trusting it, the copy decision 2 rests on - off screen once the actions were
            // pinned. The card's own title carries the confirm step instead.
            if (state !is ConnectUiState.Confirm) {
                Image(
                    painter = painterResource(R.drawable.ratatoskr_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(180.dp)
                        .padding(top = 16.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.connect_welcome_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.connect_welcome_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))
            }
            // readOnly, not disabled: while inspecting/confirming the certificate the URL
            // must stay legible (disabled text is 38% alpha and fails contrast checks),
            // it just must not change under the certificate being confirmed.
            val urlLocked = !(state is ConnectUiState.Idle || state is ConnectUiState.Error)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.connect_server_url_label)) },
                singleLine = true,
                readOnly = urlLocked,
                // A plain readOnly field still looks editable; the lock icon signals the URL is
                // held fixed to the certificate being confirmed (the same lock the card uses).
                trailingIcon = if (urlLocked) {
                    {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = stringResource(R.string.connect_url_locked_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                // 8 dp - the design's text-field radius (ux-design: Shape tokens).
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().testTag(UiTestTags.CONNECT_SERVER_URL),
            )

            // What the state adds above the action: the wait, the certificate, or the failure.
            // The action itself is pinned below, outside this scroll region.
            when (val s = state) {
                ConnectUiState.Idle, ConnectUiState.Trusted -> Unit

                ConnectUiState.Inspecting -> {
                    Spacer(Modifier.height(24.dp))
                    // Reading the certificate is normally sub-second; only escalate to the loader
                    // once the wait is long enough to be worth showing, so it never flashes.
                    if (rememberDelayedVisible(active = true)) {
                        KnotLoader(
                            size = 72.dp,
                            label = stringResource(R.string.connect_inspecting),
                        )
                    }
                }

                is ConnectUiState.Confirm -> {
                    Spacer(Modifier.height(16.dp))
                    CertificateCard(info = s.info)
                }

                is ConnectUiState.Error -> {
                    Spacer(Modifier.height(16.dp))
                    InlineBanner(kind = BannerKind.ERROR, text = s.message)
                }
            }
        }

        ConnectActions(
            state = state,
            onInspect = { onInspect(url) },
            onConfirm = onConfirm,
            onReset = onReset,
        )
    }
}

// The bottom thumb zone (ux-design: Connect - "the action in the thumb zone"): whichever action
// the current state offers, pinned outside the scroll region so it stays under the thumb however
// far the content above grows. The slot belongs to the screen rather than to one state; pinning
// only the idle action would move the primary action between the thumb zone, a card and the end
// of the scroller across three states of the same screen (issue #155).
@Composable
private fun ConnectActions(
    state: ConnectUiState,
    onInspect: () -> Unit,
    onConfirm: (String, String) -> Unit,
    onReset: () -> Unit,
) {
    when (val s = state) {
        // The one state with no action of its own: the certificate read cannot be re-triggered,
        // and the knot loader above already carries the wait. A disabled Connect button in its
        // place fails the accessibility floor - M3 renders a disabled label at 38% alpha, which
        // measures 1.88 against this surface - for the same reason the URL field above is held
        // readOnly rather than disabled.
        ConnectUiState.Inspecting -> Unit

        ConnectUiState.Idle, ConnectUiState.Trusted -> ThumbZone {
            Button(
                onClick = onInspect,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.connect_action_connect)) }
        }

        // The screen's one decision and its way out (ux-design: Connect, decision 3): copper for
        // trusting, text-only for cancelling. Both sit here rather than inside the certificate
        // card, which leaves the card to the facts alone and puts the decision on screen at every
        // height - inside the card, Cancel fell below the fold at 360x800.
        is ConnectUiState.Confirm -> ThumbZone {
            Button(
                onClick = { onConfirm(s.baseUrl, s.info.sha256Fingerprint) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.connect_action_trust)) }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.connect_action_cancel)) }
        }

        // A top-level failure blocks the flow, so retry is its own full-width button rather than
        // a tap on the banner (ux-design: Patterns).
        is ConnectUiState.Error -> ThumbZone {
            Button(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.connect_action_retry)) }
        }
    }
}

// The slot's frame: the 24 dp screen margin, and a 32 dp group break away from the scroll region
// above - 8 dp of its bottom padding plus 24 dp here, group breaks being twice the in-group gap
// (ux-design: Spacing). Only wraps a state that has an action, so the actionless one leaves no
// empty band along the bottom edge.
@Composable
private fun ThumbZone(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = 16.dp),
        content = content,
    )
}

// The certificate as a readable artifact and nothing else: the two actions it used to carry now
// sit in the screen's pinned slot (issue #155).
@Composable
private fun CertificateCard(info: CertificateInfo) {
    Surface(
        shape = MaterialTheme.shapes.large,
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
                    stringResource(R.string.connect_confirm_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(16.dp))
            CertField(stringResource(R.string.connect_cert_subject_label), info.subject)
            CertField(stringResource(R.string.connect_cert_issuer_label), info.issuer)
            CertField(
                stringResource(R.string.connect_cert_valid_until_label),
                info.notAfter.format(DateTimeFormatter.ISO_LOCAL_DATE),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.connect_cert_fingerprint_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
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
                stringResource(R.string.connect_cert_compare_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

