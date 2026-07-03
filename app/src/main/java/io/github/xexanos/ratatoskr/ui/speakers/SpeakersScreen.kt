/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.speakers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.Speaker
import io.github.xexanos.ratatoskr.ui.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpeakersUiState(
    val loading: Boolean = true,
    val speakers: List<Speaker> = emptyList(),
    val starting: Boolean = false,
    val started: Boolean = false,
    val error: String? = null,
)

class SpeakersViewModel(
    private val connectionManager: ConnectionManager,
    private val itemId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpeakersUiState())
    val uiState: StateFlow<SpeakersUiState> = _uiState.asStateFlow()

    init { loadSpeakers() }

    private fun loadSpeakers() {
        viewModelScope.launch {
            val client = connectionManager.client() ?: run {
                _uiState.value = SpeakersUiState(loading = false, error = "No server configured.")
                return@launch
            }
            _uiState.value = when (val result = client.listSpeakers()) {
                is ApiResult.Success -> SpeakersUiState(loading = false, speakers = result.data)
                is ApiResult.Failure -> SpeakersUiState(loading = false, error = result.error.toMessage())
            }
        }
    }

    fun start(speakerId: String) {
        _uiState.value = _uiState.value.copy(starting = true, error = null)
        viewModelScope.launch {
            val client = connectionManager.client() ?: return@launch
            when (val result = client.startSession(itemId, speakerId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(starting = false, started = true)
                is ApiResult.Failure -> _uiState.value =
                    _uiState.value.copy(starting = false, error = result.error.toMessage())
            }
        }
    }
}

@Composable
fun SpeakersScreen(
    viewModel: SpeakersViewModel,
    onStarted: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.started) {
        if (state.started) onStarted()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Choose a speaker", style = MaterialTheme.typography.headlineSmall)
        when {
            state.loading || state.starting -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error)
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.speakers, key = { it.id }) { speaker ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.start(speaker.id) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(speaker.name, style = MaterialTheme.typography.titleMedium)
                        val subtitle = if (speaker.isGroup) {
                            "Group: ${speaker.members.joinToString().ifEmpty { "—" }}"
                        } else {
                            "Speaker"
                        }
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
