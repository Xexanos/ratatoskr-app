/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.speakers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.data.SpeakerManager
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.Speaker
import io.github.xexanos.ratatoskr.ui.EmptyState
import io.github.xexanos.ratatoskr.ui.KnotLoader
import io.github.xexanos.ratatoskr.ui.UiTestTags
import io.github.xexanos.ratatoskr.ui.UiError
import io.github.xexanos.ratatoskr.ui.rememberDelayedVisible
import io.github.xexanos.ratatoskr.ui.text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpeakersUiState(
    val loading: Boolean = true,
    val speakers: List<Speaker> = emptyList(),
    val starting: Boolean = false,
    val started: Boolean = false,
    val error: UiError? = null,
)

class SpeakersViewModel(
    private val connectionManager: ConnectionManager,
    private val speakerManager: SpeakerManager,
    private val itemId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpeakersUiState())
    val uiState: StateFlow<SpeakersUiState> = _uiState.asStateFlow()

    init { loadSpeakers() }

    /**
     * Forces a fresh fetch through the shared [SpeakerManager] rather than reading whatever's
     * cached - the user is actively picking a speaker here, so a stale name or membership
     * would be visible and wrong. This also keeps the mini player's cache current as a
     * side effect (SpeakerManager has no periodic background refresh of its own).
     */
    private fun loadSpeakers() {
        viewModelScope.launch {
            _uiState.value = when (val result = speakerManager.refresh()) {
                null -> SpeakersUiState(loading = false, error = UiError.NoServer)
                is ApiResult.Success -> SpeakersUiState(loading = false, speakers = result.data)
                is ApiResult.Failure -> SpeakersUiState(loading = false, error = UiError.Domain(result.error))
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
                    _uiState.value.copy(starting = false, error = UiError.Domain(result.error))
            }
        }
    }
}

// The stateful host (ADR 0001): owns the ViewModel wiring and the started navigation effect.
// The navigation graph renders this; previews and goldens render [SpeakersScreen].
@Composable
fun SpeakersScreenHost(
    viewModel: SpeakersViewModel,
    onStarted: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.started) {
        if (state.started) onStarted()
    }

    SpeakersScreen(state = state, onSelectSpeaker = viewModel::start)
}

// The screen itself: a pure function of [state], previewable without a ViewModel or server.
@Composable
fun SpeakersScreen(
    state: SpeakersUiState,
    onSelectSpeaker: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.speakers_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.speakers_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            state.loading || state.starting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (rememberDelayedVisible(state.loading || state.starting)) {
                    KnotLoader(
                        label = if (state.starting) stringResource(R.string.speakers_starting) else null,
                    )
                }
            }

            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    state.error.text(),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }

            state.speakers.isEmpty() -> EmptyState(
                icon = Icons.Default.SpeakerGroup,
                title = stringResource(R.string.speakers_empty_title),
                body = stringResource(R.string.speakers_empty_hint),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.speakers, key = { it.id }) { speaker ->
                    SpeakerRow(speaker, onClick = { onSelectSpeaker(speaker.id) })
                }
            }
        }
    }
}

@Composable
private fun SpeakerRow(speaker: Speaker, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().testTag(UiTestTags.SPEAKER_ROW).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                // 16 dp rounded square, not a circle: content tile, same shape family as the
                // library rows' covers - circles are reserved for controls (ux-design: Shape tokens).
                shape = MaterialTheme.shapes.large,
                color = if (speaker.isGroup) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (speaker.isGroup) Icons.Default.SpeakerGroup else Icons.Default.Speaker,
                        contentDescription = null,
                        tint = if (speaker.isGroup) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(speaker.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (speaker.isGroup) {
                        val members = speaker.members
                        if (members.isEmpty()) {
                            stringResource(R.string.speakers_group)
                        } else {
                            stringResource(R.string.speakers_group_members, members.joinToString())
                        }
                    } else {
                        stringResource(R.string.speakers_kind_speaker)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

