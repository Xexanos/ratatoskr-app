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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.LibraryItemSummary
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.domain.Session
import io.github.xexanos.ratatoskr.ui.common.CoverImage
import io.github.xexanos.ratatoskr.ui.KnotLoader
import io.github.xexanos.ratatoskr.ui.UiError
import io.github.xexanos.ratatoskr.ui.UiTestTags
import io.github.xexanos.ratatoskr.ui.rememberDelayedVisible
import io.github.xexanos.ratatoskr.ui.text
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

private const val POLL_INTERVAL_MS = 5_000L

data class NowPlayingUiState(
    val loading: Boolean = true,
    val session: Session? = null,
    val error: UiError? = null,
    val stopped: Boolean = false,
)

class NowPlayingViewModel(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    // Monotonic counter of user control actions (pause/resume/seek). Each control bumps it; a
    // poll snapshots it and drops its own result if a newer control was issued meanwhile - so a
    // poll already in flight when the user pauses can't flip the control back to "playing". All
    // access is on the main dispatcher (viewModelScope), so a plain Int needs no synchronisation.
    private var commandEpoch = 0

    /**
     * Fetches the current session once. The screen drives this while it is RESUMED (see
     * [NowPlayingScreen]) instead of a self-running loop, so polling stops when the app is
     * backgrounded. It is a no-op once the session has been stopped, so a late in-flight poll
     * cannot revive a session the user just ended.
     */
    suspend fun refresh() {
        if (_uiState.value.stopped) return
        val epoch = commandEpoch
        val client = connectionManager.client() ?: return
        when (val result = client.currentSession()) {
            is ApiResult.Success -> applySession(result.data, epoch)
            is ApiResult.Failure -> {
                // A control action issued while this poll was in flight takes precedence.
                if (epoch != commandEpoch || _uiState.value.stopped) return
                when (result.error) {
                    is RatatoskrError.NoActiveSession -> {
                        // The session ended (server relinquished it, or it was stopped elsewhere).
                        // The server owns token rotation only WHILE a session is active (SPEC
                        // section 5), so release that flag now - otherwise the client keeps
                        // suppressing its own
                        // refresh while it sits here polling 404s, and its access token would
                        // eventually lapse with no way to renew (mirrors applySession()/stop()).
                        connectionManager.setSessionActive(false)
                        // Drop the card AND clear any error, so a banner from a just-prior failure
                        // (e.g. a 502 while the speaker was dropping out) doesn't stick on the
                        // now-empty screen. Mirrors the stale-error clear in applySession().
                        _uiState.value = _uiState.value.copy(loading = false, session = null, error = null)
                    }
                    else ->
                        _uiState.value = _uiState.value.copy(loading = false, error = UiError.Domain(result.error))
                }
            }
        }
    }

    private fun applySession(session: Session, epoch: Int) {
        // A poll or control response that completes after stop() must not revive the ended
        // session: the `stopped` guard in refresh() only covers the start of the call, not the
        // apply after the network await, and stop() has already navigated away.
        if (_uiState.value.stopped) return
        // Drop a result the user's latest control action has already superseded (e.g. a poll
        // that was in flight when the user paused), so it can't revert the just-set state.
        if (epoch != commandEpoch) return
        // The server owns token rotation while a session is active (SPEC section 5).
        val active = session.state in ACTIVE_STATES
        connectionManager.setSessionActive(active)
        // Clear a stale error on a successful response so a transient poll failure doesn't
        // leave a sticky banner once the stream is healthy again. `stopped` is preserved by the
        // guard above; a fresh control action also clears the error up front.
        _uiState.value = _uiState.value.copy(loading = false, session = session, error = null)
    }

    fun pause() = control { it.pause() }
    fun resume() = control { it.resume() }
    fun seek(positionSeconds: Double) = control { it.seek(positionSeconds) }

    fun stop() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            val client = connectionManager.client() ?: return@launch
            when (val result = client.stopSession()) {
                is ApiResult.Success -> {
                    connectionManager.setSessionActive(false)
                    _uiState.value = _uiState.value.copy(stopped = true)
                }
                is ApiResult.Failure ->
                    _uiState.value = _uiState.value.copy(error = UiError.Domain(result.error))
            }
        }
    }

    private fun control(action: suspend (io.github.xexanos.ratatoskr.network.api.RatatoskrClient) -> ApiResult<Session>) {
        val epoch = ++commandEpoch
        viewModelScope.launch {
            // Clear a stale error when the user starts a new action, so a later successful poll
            // is not what makes the old message disappear.
            _uiState.value = _uiState.value.copy(error = null)
            val client = connectionManager.client() ?: return@launch
            when (val result = action(client)) {
                is ApiResult.Success -> applySession(result.data, epoch)
                // Only surface this action's error if a newer control hasn't superseded it.
                is ApiResult.Failure ->
                    if (epoch == commandEpoch) {
                        _uiState.value = _uiState.value.copy(error = UiError.Domain(result.error))
                    }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.setSessionActive(false)
    }

    private companion object {
        val ACTIVE_STATES = setOf(PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.BUFFERING)
    }
}

@Composable
fun NowPlayingScreen(
    viewModel: NowPlayingViewModel,
    onStopped: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        // Poll only while the screen is RESUMED; a backgrounded app stops hitting the server
        // (and stops holding the session active).
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                viewModel.refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    LaunchedEffect(state.stopped) {
        if (state.stopped) onStopped()
    }

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

    var dragging by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(session.positionSeconds.toFloat()) }
    // Keyed on the server position only (not `dragging`): follow the server while the user
    // isn't dragging, but do NOT re-run the instant a drag is released - otherwise it would
    // snap the thumb back to the last polled value before the seek round-trips. After release
    // it resumes following on the next poll (by which time the seek has normally landed).
    LaunchedEffect(session.positionSeconds) {
        if (!dragging) sliderPosition = session.positionSeconds.toFloat()
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
        text = formatTime(seconds),
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
    error: UiError? = null,
    loading: Boolean = false,
) {
    RatatoskrTheme {
        Surface {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(
                    text = stringResource(R.string.nowplaying_header),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (rememberDelayedVisible(loading)) KnotLoader()
                    }
                    session == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            error?.text() ?: stringResource(R.string.nowplaying_nothing_playing),
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

@Preview(name = "Now playing - playing", widthDp = 360, heightDp = 800)
@Composable
internal fun NowPlayingPlayingPreview() =
    NowPlayingPreviewScaffold(previewSession(PlaybackState.PLAYING))

@Preview(name = "Now playing - paused", widthDp = 360, heightDp = 800)
@Composable
internal fun NowPlayingPausedPreview() =
    NowPlayingPreviewScaffold(previewSession(PlaybackState.PAUSED))

@Preview(name = "Now playing - nothing playing", widthDp = 360, heightDp = 800)
@Composable
internal fun NowPlayingEmptyPreview() =
    NowPlayingPreviewScaffold(session = null)
