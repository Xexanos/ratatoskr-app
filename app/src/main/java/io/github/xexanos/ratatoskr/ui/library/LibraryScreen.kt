/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.library

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.LibraryItemSummary
import io.github.xexanos.ratatoskr.network.domain.Progress
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import io.github.xexanos.ratatoskr.ui.toMessage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class LibraryUiState(
    val loading: Boolean = false,
    val items: List<LibraryItemSummary> = emptyList(),
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val query = MutableStateFlow<String?>(null)

    init {
        // One search pipeline for the whole screen: the initial (null) load runs immediately,
        // keystrokes are debounced, identical queries are ignored, and collectLatest cancels an
        // in-flight request when a newer query arrives so results can't land out of order.
        viewModelScope.launch {
            query
                .debounce { if (it == null) 0L else SEARCH_DEBOUNCE_MS }
                .distinctUntilChanged()
                .collectLatest { load(it) }
        }
    }

    fun search(query: String) {
        this.query.value = query.ifBlank { null }
    }

    private suspend fun load(query: String?) {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        val client = connectionManager.client()
        if (client == null) {
            _uiState.value = LibraryUiState(error = "No server configured.")
            return
        }
        _uiState.value = when (val result = client.listLibraryItems(query = query)) {
            is ApiResult.Success -> LibraryUiState(items = result.data.items)
            is ApiResult.Failure -> LibraryUiState(error = result.error.toMessage())
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenItem: (String) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    LibraryContent(
        state = state,
        query = query,
        onQueryChange = {
            query = it
            viewModel.search(it)
        },
        onOpenItem = onOpenItem,
        onOpenNowPlaying = onOpenNowPlaying,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Library",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenNowPlaying) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Now playing",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search your audiobooks") },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }

            state.items.isEmpty() -> EmptyLibrary(query)

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    LibraryRow(item, onClick = { onOpenItem(item.id) })
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(query: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                if (query.isBlank()) "📚" else "🔍",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                if (query.isBlank()) "Your library is empty" else "No matches",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                if (query.isBlank()) {
                    "Audiobooks from your Audiobookshelf server will show up here."
                } else {
                    "No audiobooks match “$query”. Try a shorter search."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItemSummary, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverThumb(item.title)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ProgressLine(item.progress, item.durationSeconds)
            }
        }
    }
}

@Composable
private fun ProgressLine(progress: Progress?, durationSeconds: Double) {
    if (progress == null || durationSeconds <= 0) return
    Spacer(Modifier.height(8.dp))
    if (progress.isFinished) {
        Text(
            "Finished",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Medium,
        )
    } else {
        val fraction = (progress.positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.weight(1f).height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${(fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoverThumb(title: String) {
    Surface(
        modifier = Modifier.size(56.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title.trim().firstOrNull()?.uppercase() ?: "♪",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

// --- Previews (render in Android Studio without a running server) --------------------------

private val previewItems = listOf(
    LibraryItemSummary("1", "The Hobbit", "J. R. R. Tolkien", 39_600.0, null, Progress(12_600.0, false)),
    LibraryItemSummary("2", "Project Hail Mary", "Andy Weir", 57_600.0, null, Progress(57_600.0, true)),
    LibraryItemSummary("3", "Dune", "Frank Herbert", 75_600.0, null, null),
)

@Preview(name = "Library — loaded", widthDp = 360, heightDp = 800)
@Composable
private fun LibraryLoadedPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Library — empty", widthDp = 360, heightDp = 800)
@Composable
private fun LibraryEmptyPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = emptyList()),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Library — loading", widthDp = 360, heightDp = 800)
@Composable
private fun LibraryLoadingPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(loading = true),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}
