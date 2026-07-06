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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import io.github.xexanos.ratatoskr.ui.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SignInUiState {
    data object Idle : SignInUiState
    data object Submitting : SignInUiState
    data object Success : SignInUiState
    data class Error(val message: String) : SignInUiState
}

class SignInViewModel(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SignInUiState>(SignInUiState.Idle)
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    fun signIn(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) return
        _uiState.value = SignInUiState.Submitting
        viewModelScope.launch {
            val client = connectionManager.client()
            if (client == null) {
                _uiState.value = SignInUiState.Error("No server configured.")
                return@launch
            }
            _uiState.value = when (val result = client.login(username, password)) {
                is ApiResult.Success -> SignInUiState.Success
                is ApiResult.Failure -> SignInUiState.Error(result.error.toMessage())
            }
        }
    }
}

@Composable
fun SignInScreen(
    viewModel: SignInViewModel,
    onSignedIn: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is SignInUiState.Success) onSignedIn()
    }

    SignInContent(state = state, onSignIn = viewModel::signIn)
}

@Composable
private fun SignInContent(
    state: SignInUiState,
    onSignIn: (String, String) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    // Plain remember, not rememberSaveable: the password must not be written to the
    // saved-instance-state Bundle (persisted to disk on process death). Losing it across
    // process death is the right trade-off for a credential.
    var password by remember { mutableStateOf("") }

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
                .size(120.dp)
                .padding(top = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Sign in",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Use your Audiobookshelf username and password.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            leadingIcon = {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            leadingIcon = {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))

        if (state is SignInUiState.Error) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Button(
            onClick = { onSignIn(username, password) },
            enabled = state !is SignInUiState.Submitting,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (state is SignInUiState.Submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Sign in")
            }
        }
    }
}

// --- Previews (render in Android Studio without a running server) --------------------------

@Preview(name = "Sign in — idle", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInIdlePreview() = RatatoskrTheme {
    Surface { SignInContent(SignInUiState.Idle) { _, _ -> } }
}

@Preview(name = "Sign in — error", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInErrorPreview() = RatatoskrTheme {
    Surface { SignInContent(SignInUiState.Error("Sign-in expired. Please sign in again.")) { _, _ -> } }
}

@Preview(name = "Sign in — submitting", widthDp = 360, heightDp = 800)
@Composable
private fun SignInSubmittingPreview() = RatatoskrTheme {
    Surface { SignInContent(SignInUiState.Submitting) { _, _ -> } }
}
