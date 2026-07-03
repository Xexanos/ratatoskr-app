/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.ApiResult
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
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is SignInUiState.Success) onSignedIn()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sign in", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Use your Audiobookshelf username and password.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        when (val s = state) {
            SignInUiState.Submitting -> CircularProgressIndicator()
            is SignInUiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        Button(
            onClick = { viewModel.signIn(username, password) },
            enabled = state !is SignInUiState.Submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign in")
        }
    }
}
