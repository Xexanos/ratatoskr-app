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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.data.SessionManager
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.Session
import io.github.xexanos.ratatoskr.ui.common.CoverImage
import io.github.xexanos.ratatoskr.ui.KnotLoader
import io.github.xexanos.ratatoskr.ui.UiError
import io.github.xexanos.ratatoskr.ui.UiTestTags
import io.github.xexanos.ratatoskr.ui.common.formatPlaybackTime
import io.github.xexanos.ratatoskr.ui.common.rememberTickingPositionSeconds
import io.github.xexanos.ratatoskr.ui.rememberDelayedVisible
import io.github.xexanos.ratatoskr.ui.text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NowPlayingUiState(
    val loading: Boolean = true,
    val session: Session? = null,
    val error: UiError? = null,
    val stopped: Boolean = false,
)

/**
 * A thin adapter over [SessionManager] (decision record, issue #79/#101): the poll loop, the
 * command-epoch guard, `NoActiveSession` handling, and the `sessionActive` flag all live there
 * now, shared with the library's mini player. This ViewModel only maps the shared session
 * state into its own [NowPlayingUiState] and forwards control actions, surfacing failures as
 * its own inline banner (a failure from the mini player's toggle is a separate concern - a
 * library snackbar, not this state).
 */
class NowPlayingViewModel(
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.state.collect { snapshot ->
                _uiState.value = _uiState.value.copy(
                    loading = snapshot.loading,
                    session = snapshot.session,
                    error = snapshot.error?.let { UiError.Domain(it) },
                )
            }
        }
    }

    fun pause() = control { sessionManager.pause() }
    fun resume() = control { sessionManager.resume() }
    fun seek(positionSeconds: Double) = control { sessionManager.seek(positionSeconds) }

    fun stop() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            when (val result = sessionManager.stop()) {
                null -> {} // superseded by a newer action - do nothing
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(stopped = true)
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(error = UiError.Domain(result.error))
            }
        }
    }

    private fun control(action: suspend () -> ApiResult<Session>?) {
        viewModelScope.launch {
            // Clear a stale error when the user starts a new action, so a later successful poll
            // is not what makes the old message disappear.
            _uiState.value = _uiState.value.copy(error = null)
            when (val result = action()) {
                // A superseded action (null) or a success both need no local update here: the
                // shared sessionManager.state collector above already reflects any real change.
                null, is ApiResult.Success -> {}
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(error = UiError.Domain(result.error))
            }
        }
    }
}

// The stateful host (ADR 0001): owns the ViewModel wiring and the stopped navigation effect.
// Polling itself is process-wide (SessionManager, ADR 0002), not driven by this screen anymore.
// The navigation graph renders this; previews and goldens render [NowPlayingScreen].
@Composable
fun NowPlayingScreenHost(
    viewModel: NowPlayingViewModel,
    onStopped: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.stopped) {
        if (state.stopped) onStopped()
    }

    NowPlayingScreen(
        state = state,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onSeek = viewModel::seek,
        onStop = viewModel::stop,
    )
}

// The screen itself: a pure function of [state], previewable without a ViewModel or server. Owns
// the whole visual - header, loading / nothing-playing / loaded branches - so goldens render the
// real structure, not a preview-side copy of it (the pre-split scaffold duplicated this layout,
// and drift in the real screen could never fail its goldens).
@Composable
fun NowPlayingScreen(
    state: NowPlayingUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.nowplaying_header),
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        val session = state.session
        when {
            state.loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (rememberDelayedVisible(state.loading)) KnotLoader()
                }

            session == null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        state.error?.text() ?: stringResource(R.string.nowplaying_nothing_playing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

            else -> NowPlayingContent(
                session = session,
                error = state.error,
                onPause = onPause,
                onResume = onResume,
                onSeek = onSeek,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.NowPlayingContent(
    session: Session,
    error: UiError?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    CoverArt(coverUrl = session.item?.coverUrl)

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

    // Ticking, not the raw polled position (decision record, issue #79/#101): creeps forward
    // once a second while playing instead of jumping in 5s poll steps, shared with the mini
    // player so both surfaces tell the same story.
    val tickingPosition = rememberTickingPositionSeconds(session)
    var dragging by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(tickingPosition.toFloat()) }
    // Keyed on the ticking position (not `dragging`): follow it while the user isn't dragging,
    // but do NOT re-run the instant a drag is released - otherwise it would snap the thumb back
    // to the last ticked value before the seek round-trips. After release it resumes following
    // on the next tick (by which time the seek has normally landed).
    LaunchedEffect(tickingPosition) {
        if (!dragging) sliderPosition = tickingPosition.toFloat()
    }
    val duration = session.durationSeconds.toFloat().coerceAtLeast(1f)
    Slider(
        value = sliderPosition.coerceIn(0f, duration),
        onValueChange = {
            dragging = true
            sliderPosition = it
        },
        onValueChangeFinished = {
            onSeek(sliderPosition.toDouble())
            dragging = false
        },
        valueRange = 0f..duration,
        colors = SliderDefaults.colors(),
        modifier = Modifier.fillMaxWidth().testTag(UiTestTags.NOWPLAYING_SEEK),
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
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (playing) R.string.nowplaying_action_pause else R.string.nowplaying_action_play,
            ),
            size = 76.dp,
            iconSize = 36.dp,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            elevation = 6.dp,
            onClick = { if (playing) onPause() else onResume() },
        )
        CircleControl(
            icon = Icons.Filled.Stop,
            contentDescription = stringResource(R.string.nowplaying_action_stop),
            size = 56.dp,
            iconSize = 28.dp,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            elevation = 0.dp,
            onClick = onStop,
        )
    }

    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(
            it.text(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CoverArt(coverUrl: String?) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CoverImage(
            coverUrl = coverUrl,
            modifier = Modifier.size(260.dp),
            shape = MaterialTheme.shapes.extraLarge,
            shadowElevation = 8.dp,
            tonalElevation = 2.dp,
            // The large tile keeps its full-bleed center-crop; letterboxing non-square art is
            // out of scope here (issue #97), so it opts out of CoverImage's Fit default.
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun StateChip(state: PlaybackState) {
    val (labelRes, dot) = when (state) {
        PlaybackState.PLAYING -> R.string.nowplaying_state_playing to MaterialTheme.colorScheme.primary
        PlaybackState.PAUSED -> R.string.nowplaying_state_paused to MaterialTheme.colorScheme.onSurfaceVariant
        PlaybackState.BUFFERING -> R.string.nowplaying_state_buffering to MaterialTheme.colorScheme.onSurfaceVariant
        PlaybackState.FINISHED -> R.string.nowplaying_state_finished to MaterialTheme.colorScheme.primary
        PlaybackState.STOPPED -> R.string.nowplaying_state_stopped to MaterialTheme.colorScheme.onSurfaceVariant
        PlaybackState.UNKNOWN -> R.string.nowplaying_state_unknown to MaterialTheme.colorScheme.onSurfaceVariant
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
                stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeLabel(seconds: Double, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = formatPlaybackTime(seconds),
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
}

@Composable
private fun CircleControl(
    icon: ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
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
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize))
        }
    }
}


