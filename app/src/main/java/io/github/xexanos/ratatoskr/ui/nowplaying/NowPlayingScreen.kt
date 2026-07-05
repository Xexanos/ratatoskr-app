/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.LibraryItemSummary
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.domain.Session
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import io.github.xexanos.ratatoskr.ui.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import java.time.OffsetDateTime

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
                delay(POLL_INTERVAL_MS.milliseconds)
            }
        }
    }

    private suspend fun refresh() {
        val client = connectionManager.client() ?: return
        when (val result = client.currentSession()) {
            is ApiResult.Success -> applySession(result.data)
            is ApiResult.Failure -> when (result.error) {
                is RatatoskrError.NoActiveSession ->
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
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = "NOW PLAYING",
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        val session = state.session
        when {
            state.loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

            session == null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        state.error ?: "Nothing is playing right now.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

            else -> NowPlayingContent(
                session = session,
                error = state.error,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onSeek = viewModel::seek,
                onStop = viewModel::stop,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.NowPlayingContent(
    session: Session,
    error: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    CoverArt(title = session.item?.title ?: session.itemId)

    Spacer(Modifier.height(28.dp))
    Text(
        text = session.item?.title ?: session.itemId,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    session.item?.author?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        StateChip(session.state)
    }

    // Push transport controls into the thumb zone (bottom third).
    Spacer(Modifier.weight(1f))

    var sliderPosition by remember(session.positionSeconds) {
        mutableFloatStateOf(session.positionSeconds.toFloat())
    }
    val duration = session.durationSeconds.toFloat().coerceAtLeast(1f)
    Slider(
        value = sliderPosition.coerceIn(0f, duration),
        onValueChange = { sliderPosition = it },
        onValueChangeFinished = { onSeek(sliderPosition.toDouble()) },
        valueRange = 0f..duration,
        colors = SliderDefaults.colors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TimeLabel(sliderPosition.toDouble(), MaterialTheme.colorScheme.onSurface)
        TimeLabel(session.durationSeconds, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(Modifier.height(24.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val playing = session.state == PlaybackState.PLAYING
        CircleControl(
            glyph = if (playing) "⏸" else "▶",
            size = 76.dp,
            glyphSize = 30.sp,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            elevation = 6.dp,
            onClick = { if (playing) onPause() else onResume() },
        )
        CircleControl(
            glyph = "⏹",
            size = 56.dp,
            glyphSize = 22.sp,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            elevation = 0.dp,
            onClick = onStop,
        )
    }

    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CoverArt(title: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(260.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shadowElevation = 8.dp,
            tonalElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = title.trim().firstOrNull()?.uppercase() ?: "♪",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StateChip(state: PlaybackState) {
    val (label, dot) = when (state) {
        PlaybackState.PLAYING -> "Playing" to MaterialTheme.colorScheme.primary
        PlaybackState.PAUSED -> "Paused" to MaterialTheme.colorScheme.onSurfaceVariant
        PlaybackState.BUFFERING -> "Buffering" to MaterialTheme.colorScheme.onSurfaceVariant
        PlaybackState.FINISHED -> "Finished" to MaterialTheme.colorScheme.primary
        PlaybackState.STOPPED -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
        PlaybackState.UNKNOWN -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeLabel(seconds: Double, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = formatTime(seconds),
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
}

@Composable
private fun CircleControl(
    glyph: String,
    size: androidx.compose.ui.unit.Dp,
    glyphSize: androidx.compose.ui.unit.TextUnit,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    elevation: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = container,
        contentColor = content,
        shadowElevation = elevation,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(glyph, fontSize = glyphSize)
        }
    }
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// --- Previews (render in Android Studio without a running server) --------------------------

private fun previewSession(state: PlaybackState) = Session(
    itemId = "1",
    item = LibraryItemSummary(
        id = "1",
        title = "The Hobbit",
        author = "J. R. R. Tolkien",
        durationSeconds = 39_600.0,
        coverUrl = null,
        progress = null,
    ),
    speakerId = "living-room",
    state = state,
    positionSeconds = 12_600.0,
    durationSeconds = 39_600.0,
    updatedAt = OffsetDateTime.parse("2026-07-04T12:00:00Z"),
)

@Composable
private fun NowPlayingPreviewScaffold(
    session: Session?,
    error: String? = null,
    loading: Boolean = false,
) {
    RatatoskrTheme {
        Surface {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(
                    text = "NOW PLAYING",
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    session == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            error ?: "Nothing is playing right now.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> NowPlayingContent(session, error, {}, {}, {}, {})
                }
            }
        }
    }
}

@Preview(name = "Now playing — playing", widthDp = 360, heightDp = 800)
@Composable
internal fun NowPlayingPlayingPreview() =
    NowPlayingPreviewScaffold(previewSession(PlaybackState.PLAYING))

@Preview(name = "Now playing — paused", widthDp = 360, heightDp = 800)
@Composable
internal fun NowPlayingPausedPreview() =
    NowPlayingPreviewScaffold(previewSession(PlaybackState.PAUSED))

@Preview(name = "Now playing — nothing playing", widthDp = 360, heightDp = 800)
@Composable
internal fun NowPlayingEmptyPreview() =
    NowPlayingPreviewScaffold(session = null)
