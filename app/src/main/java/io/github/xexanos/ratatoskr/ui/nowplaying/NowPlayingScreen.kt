/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.Session
import io.github.xexanos.ratatoskr.ui.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class NowPlayingUiState(
    val loading: Boolean = true,
    val session: Session? = null,
    val error: String? = null,
    val stopped: Boolean = false,
)

class NowPlayingViewModel(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (coroutineContext.isActive) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refresh() {
        val client = connectionManager.client() ?: return
        when (val result = client.currentSession()) {
            is ApiResult.Success -> applySession(result.data)
            is ApiResult.Failure -> when (result.error) {
                is io.github.xexanos.ratatoskr.network.domain.RatatoskrError.NoActiveSession ->
                    _uiState.value = _uiState.value.copy(loading = false, session = null)
                else ->
                    _uiState.value = _uiState.value.copy(loading = false, error = result.error.toMessage())
            }
        }
    }

    private fun applySession(session: Session) {
        // The server owns token rotation while a session is active (SPEC section 5).
        val active = session.state in ACTIVE_STATES
        connectionManager.setSessionActive(active)
        _uiState.value = NowPlayingUiState(loading = false, session = session)
    }

    fun pause() = control { it.pause() }
    fun resume() = control { it.resume() }
    fun seek(positionSeconds: Double) = control { it.seek(positionSeconds) }

    fun stop() {
        viewModelScope.launch {
            val client = connectionManager.client() ?: return@launch
            when (val result = client.stopSession()) {
                is ApiResult.Success -> {
                    connectionManager.setSessionActive(false)
                    _uiState.value = _uiState.value.copy(stopped = true)
                }
                is ApiResult.Failure ->
                    _uiState.value = _uiState.value.copy(error = result.error.toMessage())
            }
        }
    }

    private fun control(action: suspend (io.github.xexanos.ratatoskr.network.api.RatatoskrClient) -> ApiResult<Session>) {
        viewModelScope.launch {
            val client = connectionManager.client() ?: return@launch
            when (val result = action(client)) {
                is ApiResult.Success -> applySession(result.data)
                is ApiResult.Failure ->
                    _uiState.value = _uiState.value.copy(error = result.error.toMessage())
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.setSessionActive(false)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
        val ACTIVE_STATES = setOf(PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.BUFFERING)
    }
}

@Composable
fun NowPlayingScreen(
    viewModel: NowPlayingViewModel,
    onStopped: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.stopped) {
        if (state.stopped) onStopped()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val session = state.session
        when {
            state.loading -> CircularProgressIndicator()
            session == null -> Text(state.error ?: "Nothing is playing.")
            else -> NowPlayingContent(session, viewModel)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun NowPlayingContent(session: Session, viewModel: NowPlayingViewModel) {
    Text(session.item?.title ?: session.itemId, style = MaterialTheme.typography.headlineSmall)
    session.item?.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Text("State: ${session.state.name.lowercase()}")

    var sliderPosition by remember(session.positionSeconds) {
        mutableFloatStateOf(session.positionSeconds.toFloat())
    }
    val duration = session.durationSeconds.toFloat().coerceAtLeast(1f)
    Slider(
        value = sliderPosition.coerceIn(0f, duration),
        onValueChange = { sliderPosition = it },
        onValueChangeFinished = { viewModel.seek(sliderPosition.toDouble()) },
        valueRange = 0f..duration,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("${formatTime(sliderPosition.toDouble())} / ${formatTime(session.durationSeconds)}")

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (session.state == PlaybackState.PLAYING) {
            Button(onClick = { viewModel.pause() }) { Text("Pause") }
        } else {
            Button(onClick = { viewModel.resume() }) { Text("Resume") }
        }
        OutlinedButton(onClick = { viewModel.stop() }) { Text("Stop") }
    }
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
