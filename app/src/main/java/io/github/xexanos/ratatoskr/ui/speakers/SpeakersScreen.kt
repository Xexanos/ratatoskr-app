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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.Speaker
import io.github.xexanos.ratatoskr.ui.EmptyState
import io.github.xexanos.ratatoskr.ui.UiTestTags
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
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

    SpeakersContent(state = state, onSelectSpeaker = viewModel::start)
}

@Composable
private fun SpeakersContent(
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    if (state.starting) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.speakers_starting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    state.error,
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
        shape = MaterialTheme.shapes.extraLarge,
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
                shape = CircleShape,
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

// --- Previews (render in Android Studio without a running server) --------------------------

private val previewSpeakers = listOf(
    Speaker("lr", "Living Room", isGroup = false, members = emptyList()),
    Speaker("home", "Whole home", isGroup = true, members = listOf("Living Room", "Kitchen", "Study")),
)

@Preview(name = "Speakers - loaded", widthDp = 360, heightDp = 800)
@Composable
internal fun SpeakersLoadedPreview() = RatatoskrTheme {
    Surface { SpeakersContent(SpeakersUiState(loading = false, speakers = previewSpeakers)) {} }
}

@Preview(name = "Speakers - empty", widthDp = 360, heightDp = 800)
@Composable
internal fun SpeakersEmptyPreview() = RatatoskrTheme {
    Surface { SpeakersContent(SpeakersUiState(loading = false)) {} }
}

@Preview(name = "Speakers - loading", widthDp = 360, heightDp = 800)
@Composable
internal fun SpeakersLoadingPreview() = RatatoskrTheme {
    Surface { SpeakersContent(SpeakersUiState(loading = true)) {} }
}
