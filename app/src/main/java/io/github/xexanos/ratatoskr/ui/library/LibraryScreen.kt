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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.github.xexanos.ratatoskr.network.domain.LibraryItemSummary
import io.github.xexanos.ratatoskr.network.domain.Progress
import io.github.xexanos.ratatoskr.ui.EmptyState
import io.github.xexanos.ratatoskr.ui.UiTestTags
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import io.github.xexanos.ratatoskr.ui.toMessage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
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
    // Cursor for the page after the ones already in `items`; null once the last page is in.
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    // The last load-more attempt failed; the footer offers a tap-to-retry instead of a spinner.
    val loadMoreError: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val query = MutableStateFlow<String?>(null)

    // Buffered so a request arriving while a page load is in flight is kept (and coalesced with
    // any later ones) instead of dropped - the scroll position is still near the end when the
    // page lands, and the UI's trigger won't fire again until the threshold is crossed anew.
    private val loadMoreRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        // One search pipeline for the whole screen: the initial (null) load runs immediately,
        // keystrokes are debounced, identical queries are ignored, and collectLatest cancels an
        // in-flight request when a newer query arrives so results can't land out of order.
        // Follow-up pages are collected INSIDE the same block, so they always belong to the
        // current query and die with it - a stale cursor can never append to fresh results.
        viewModelScope.launch {
            query
                .debounce { if (it == null) 0L else SEARCH_DEBOUNCE_MS }
                .distinctUntilChanged()
                .collectLatest { q ->
                    load(q)
                    loadMoreRequests.collect { loadNextPage(q) }
                }
        }
    }

    fun search(query: String) {
        this.query.value = query.ifBlank { null }
    }

    fun loadMore() {
        loadMoreRequests.tryEmit(Unit)
    }

    private suspend fun load(query: String?) {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        val client = connectionManager.client()
        if (client == null) {
            _uiState.value = LibraryUiState(error = "No server configured.")
            return
        }
        _uiState.value = when (val result = client.listLibraryItems(query = query)) {
            is ApiResult.Success ->
                LibraryUiState(items = result.data.items, nextCursor = result.data.nextCursor)
            is ApiResult.Failure -> LibraryUiState(error = result.error.toMessage())
        }
    }

    private suspend fun loadNextPage(query: String?) {
        val cursor = _uiState.value.nextCursor ?: return
        val client = connectionManager.client() ?: return
        _uiState.value = _uiState.value.copy(loadingMore = true, loadMoreError = false)
        _uiState.value = when (val result = client.listLibraryItems(query = query, cursor = cursor)) {
            is ApiResult.Success -> _uiState.value.copy(
                items = _uiState.value.items + result.data.items,
                nextCursor = result.data.nextCursor,
                loadingMore = false,
            )
            // Keep the loaded items and the cursor: the footer flips to a tap-to-retry row
            // instead of replacing a working list with the full-screen error state. Scrolling
            // back into the trigger zone retries too.
            is ApiResult.Failure -> _uiState.value.copy(loadingMore = false, loadMoreError = true)
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

    // On process-death restore the text field's saved query comes back, but the ViewModel's
    // search flow restarts at null (full library). Re-issue the restored query so the results
    // match what the search box shows.
    LaunchedEffect(Unit) {
        if (query.isNotBlank()) viewModel.search(query)
    }

    LibraryContent(
        state = state,
        query = query,
        onQueryChange = {
            query = it
            viewModel.search(it)
        },
        onLoadMore = viewModel::loadMore,
        onOpenItem = onOpenItem,
        onOpenNowPlaying = onOpenNowPlaying,
        onOpenSettings = onOpenSettings,
    )
}

// How many rows before the end of the list the next page is requested.
private const val LOAD_MORE_THRESHOLD = 8

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onLoadMore: () -> Unit = {},
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
                stringResource(R.string.library_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenNowPlaying) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.library_now_playing),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.library_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().testTag(UiTestTags.LIBRARY_SEARCH),
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

            else -> {
                val listState = rememberLazyListState()
                // Ask for the next page a few rows before the end, so it is usually there by
                // the time the user reaches it. derivedStateOf keeps this from recomposing on
                // every scroll frame: it only changes when the threshold is crossed.
                val nearEnd by remember(listState) {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        last >= info.totalItemsCount - 1 - LOAD_MORE_THRESHOLD
                    }
                }
                LaunchedEffect(nearEnd, state.nextCursor) {
                    if (nearEnd && state.nextCursor != null) onLoadMore()
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        LibraryRow(item, onClick = { onOpenItem(item.id) })
                    }
                    if (state.nextCursor != null) {
                        item(key = "load-more") {
                            if (state.loadMoreError) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable(onClick = onLoadMore)
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        stringResource(R.string.library_load_more_failed),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            } else {
                                val loadingDesc = stringResource(R.string.library_loading_more)
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp)
                                        .semantics { contentDescription = loadingDesc },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(query: String) {
    EmptyState(
        icon = if (query.isBlank()) Icons.AutoMirrored.Filled.MenuBook else Icons.Default.SearchOff,
        title = stringResource(
            if (query.isBlank()) R.string.library_empty_title else R.string.library_no_results_title,
        ),
        body = if (query.isBlank()) {
            stringResource(R.string.library_empty_body)
        } else {
            stringResource(R.string.library_no_results_body, query)
        },
    )
}

@Composable
private fun LibraryRow(item: LibraryItemSummary, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().testTag(UiTestTags.LIBRARY_ROW).clickable(onClick = onClick),
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
            stringResource(R.string.library_finished),
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
                stringResource(R.string.library_percent, (fraction * 100).roundToInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoverThumb(title: String) {
    val initial = title.trim().firstOrNull()?.uppercase()
    Surface(
        modifier = Modifier.size(56.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (initial != null) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

// --- Previews (render in Android Studio without a running server) --------------------------

private val previewItems = listOf(
    LibraryItemSummary("1", "The Hobbit", "J. R. R. Tolkien", 39_600.0, null, Progress(12_600.0, false)),
    LibraryItemSummary("2", "Project Hail Mary", "Andy Weir", 57_600.0, null, Progress(57_600.0, true)),
    LibraryItemSummary("3", "Dune", "Frank Herbert", 75_600.0, null, null),
)

@Preview(name = "Library - loaded", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryLoadedPreview() = RatatoskrTheme {
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

@Preview(name = "Library - empty", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryEmptyPreview() = RatatoskrTheme {
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

@Preview(name = "Library - loading more", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryLoadingMorePreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems, nextCursor = "c2", loadingMore = true),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Library - load more failed", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryLoadMoreFailedPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems, nextCursor = "c2", loadMoreError = true),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Library - loading", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryLoadingPreview() = RatatoskrTheme {
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
