/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.data.SignInPrompt
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.ui.BannerKind
import io.github.xexanos.ratatoskr.ui.ChipLeading
import io.github.xexanos.ratatoskr.ui.InlineBanner
import io.github.xexanos.ratatoskr.ui.StatusChip
import io.github.xexanos.ratatoskr.ui.UiError
import io.github.xexanos.ratatoskr.ui.text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI

sealed interface SignInUiState {
    data object Idle : SignInUiState
    data object Submitting : SignInUiState
    data object Success : SignInUiState
    data class Error(val error: UiError) : SignInUiState
}

/**
 * Why the user is on sign-in when they did not choose to be - a 401 or the /v1 -> /v2 update
 * (SPEC section 5). It varies only the explanatory notice's copy - the recovery is one path
 * either way. Absent on an ordinary sign-in.
 */
enum class SignInNotice {
    /** `code: UPSTREAM_SESSION_LOST` - the server's own media-server sign-in expired. */
    MEDIA_SERVER_EXPIRED,

    /** Any other 401 - the token was revoked or the session ended elsewhere. */
    SESSION_ENDED,

    /** First launch after the /v1 -> /v2 update - the one-time re-login (SPEC section 5). */
    APP_UPDATED,
}

/**
 * The pre-fill the sign-in screen opens with: a remembered username, an optional 401 notice, and
 * the trusted server's host for the trust chip. All three arrive on the one asynchronous pass, so
 * the screen stays a pure function of its inputs (ADR 0001).
 */
data class SignInPrefill(
    val username: String = "",
    val notice: SignInNotice? = null,
    val serverHost: String? = null,
)

// The contract's machine-readable code for "the server lost its Audiobookshelf session" (SPEC
// section 5). Every other - or absent - code lands on [SignInNotice.SESSION_ENDED].
private const val UPSTREAM_SESSION_LOST = "UPSTREAM_SESSION_LOST"

// The one port an HTTPS URL already implies, so naming it on the chip would add a digit group
// that tells the user nothing.
private const val HTTPS_PORT = 443

/**
 * The trusted server's host as the trust chip states it: the hostname, plus `:<port>` when the
 * port is anything but 443 - a self-hosted server on an odd port is common enough that dropping
 * it would hide the half of the address the user recognises their own machine by. The scheme,
 * path and query never appear.
 *
 * Null when [baseUrl] carries no parseable host, which the screen renders as no chip at all
 * rather than a placeholder.
 */
internal fun serverDisplayHost(baseUrl: String): String? {
    val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    return if (uri.port == -1 || uri.port == HTTPS_PORT) host else "$host:${uri.port}"
}

class SignInViewModel(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SignInUiState>(SignInUiState.Idle)
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    // Loaded once from storage: the remembered username to pre-fill, if the user got here via a
    // 401 which notice to show, and the host of the server they trusted. All of it survives the
    // dead token (SPEC section 5).
    private val _prefill = MutableStateFlow(SignInPrefill())
    val prefill: StateFlow<SignInPrefill> = _prefill.asStateFlow()

    init {
        viewModelScope.launch {
            val notice = when (val prompt = connectionManager.consumeSignInPrompt()) {
                is SignInPrompt.Reauth ->
                    if (prompt.code == UPSTREAM_SESSION_LOST) SignInNotice.MEDIA_SERVER_EXPIRED
                    else SignInNotice.SESSION_ENDED
                SignInPrompt.AppUpdated -> SignInNotice.APP_UPDATED
                null -> null
            }
            _prefill.value = SignInPrefill(
                username = connectionManager.prefillUsername().orEmpty(),
                notice = notice,
                // The stored config the client itself is built from is the single source for the
                // host. The pinned fingerprint stays out of it: the chip states *that* the server
                // is trusted, not *what* was trusted.
                serverHost = connectionManager.serverBaseUrl()?.let(::serverDisplayHost),
            )
        }
    }

    fun signIn(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) return
        _uiState.value = SignInUiState.Submitting
        viewModelScope.launch {
            val client = connectionManager.client()
            if (client == null) {
                _uiState.value = SignInUiState.Error(UiError.NoServer)
                return@launch
            }
            _uiState.value = when (val result = client.login(username, password)) {
                is ApiResult.Success -> SignInUiState.Success
                is ApiResult.Failure -> SignInUiState.Error(
                    // A 401 here rejects the just-entered credentials (ux-design: Sign in,
                    // decision 4) - unlike a 401 on an authenticated call, where the shared
                    // mapping's "sign-in expired" copy is right.
                    if (result.error is RatatoskrError.Unauthorized) UiError.WrongCredentials
                    else UiError.Domain(result.error),
                )
            }
        }
    }
}

// The stateful host (ADR 0001): owns the ViewModel wiring and the signed-in navigation effect.
// The navigation graph renders this; previews and goldens render [SignInScreen].
@Composable
fun SignInScreenHost(
    viewModel: SignInViewModel,
    onSignedIn: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefill by viewModel.prefill.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is SignInUiState.Success) onSignedIn()
    }

    SignInScreen(
        state = state,
        initialUsername = prefill.username,
        notice = prefill.notice,
        serverHost = prefill.serverHost,
        onSignIn = viewModel::signIn,
    )
}

// The screen itself: a pure function of its inputs, previewable without a ViewModel or server.
@Composable
fun SignInScreen(
    state: SignInUiState,
    initialUsername: String = "",
    notice: SignInNotice? = null,
    serverHost: String? = null,
    onSignIn: (String, String) -> Unit,
) {
    // Keyed on the remembered username: it loads asynchronously (empty, then the stored value), so
    // the field re-initialises once when it arrives. The password is never pre-filled (SPEC
    // section 5). User edits still survive config changes - the key is stable after the load.
    var username by rememberSaveable(initialUsername) { mutableStateOf(initialUsername) }
    // Plain remember, not rememberSaveable: the password must not be written to the
    // saved-instance-state Bundle (persisted to disk on process death). Losing it across
    // process death is the right trade-off for a credential.
    var password by remember { mutableStateOf("") }
    // Plain remember for the same reason, and because a revealed field has to come back masked:
    // showing a password is a decision for the moment it was made, not state to restore into a
    // room the user may since have walked away from.
    var passwordVisible by remember { mutableStateOf(false) }

    // The form scrolls; the action does not. The button sits in the bottom thumb zone
    // (ux-design: Sign in), and imePadding lifts it clear of the software keyboard - under
    // enableEdgeToEdge the window is not resized for the IME, so the inset is applied here.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // Outside the scroll region on purpose: the chip answers "where are these credentials
        // going", which is a question the user has while typing them - and with the keyboard open
        // the form above scrolls far enough to carry a scrolling chip off screen. Absent when no
        // server config could be read; there is no honest placeholder for a host we don't have.
        if (serverHost != null) {
            // The trust chip (ux-design: Sign in, decision 1): links the credentials about to be
            // typed to the certificate confirmed one step earlier, so the form is not anonymous.
            // It leads with the same lock glyph the certificate card does; the pill's tone, shape
            // and metrics belong to [StatusChip], not here.
            StatusChip(
                label = stringResource(R.string.signin_server_trusted, serverHost),
                leading = ChipLeading.Glyph(Icons.Outlined.Lock),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 24.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ratatoskr_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .padding(top = 16.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.signin_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.signin_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            // The explanatory notice (SPEC section 5): shown only when the user was sent here by a
            // dead token or the /v1 -> /v2 update, explaining why. Distinct from the
            // [SignInUiState.Error] banner below, which reports a failed sign-in attempt.
            if (notice != null) {
                SignInNoticeBanner(notice)
                Spacer(Modifier.height(24.dp))
            }

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.signin_username_label)) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                // 8 dp - the design's text-field radius (ux-design: Shape tokens).
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.signin_password_label)) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                // Audiobookshelf passwords are commonly long and generated, and typing one blind
                // is what produces the wrong-credentials error below (ux-design: Sign in,
                // decision 3).
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            // Names the action this tap performs, not the state the field is in:
                            // the button is what the user is standing on when TalkBack reads it.
                            contentDescription = stringResource(
                                if (passwordVisible) {
                                    R.string.signin_password_hide
                                } else {
                                    R.string.signin_password_show
                                },
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                singleLine = true,
                visualTransformation =
                    if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            // Promises the session, not the mechanism (ux-design: Sign in, decision 2). Takes the
            // M3 supporting-text indent, so it reads as the field's own helper line.
            Text(
                stringResource(R.string.signin_stay_signed_in),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            if (state is SignInUiState.Error) {
                Spacer(Modifier.height(24.dp))
                InlineBanner(kind = BannerKind.ERROR, text = state.error.text())
            }
        }

        // Pinned, so it stays under the thumb however long the form above grows. Enabled while an
        // error is showing: the input is still there to correct and resubmit.
        Button(
            onClick = { onSignIn(username, password) },
            enabled = state !is SignInUiState.Submitting,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 16.dp)
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (state is SignInUiState.Submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.signin_action))
            }
        }
    }
}

// The explanatory notice for the sign-ins the user did not choose (a 401, or the one-time
// /v1 -> /v2 re-login). Being signed out is an unexpected but routine heads-up to act on, not a
// success and not a failure the user caused, which is what BannerKind.NOTICE means.
@Composable
private fun SignInNoticeBanner(notice: SignInNotice) {
    val message = when (notice) {
        SignInNotice.MEDIA_SERVER_EXPIRED -> stringResource(R.string.signin_notice_media_server_expired)
        SignInNotice.SESSION_ENDED -> stringResource(R.string.signin_notice_session_ended)
        SignInNotice.APP_UPDATED -> stringResource(R.string.signin_notice_app_updated)
    }
    InlineBanner(kind = BannerKind.NOTICE, text = message)
}
