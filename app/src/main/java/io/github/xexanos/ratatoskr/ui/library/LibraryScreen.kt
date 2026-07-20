/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.semantics.heading
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
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.domain.Progress
import io.github.xexanos.ratatoskr.ui.EmptyState
import io.github.xexanos.ratatoskr.ui.KnotLoader
import io.github.xexanos.ratatoskr.ui.UiError
import io.github.xexanos.ratatoskr.ui.UiTestTags
import io.github.xexanos.ratatoskr.ui.common.CoverImage
import io.github.xexanos.ratatoskr.ui.rememberDelayedVisible
import io.github.xexanos.ratatoskr.ui.text
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class LibraryUiState(
    val loading: Boolean = false,
    val items: List<LibraryItemSummary> = emptyList(),
    // The continue-listening shelf, most-recently-listened first as the server delivered it.
    // Empty means "no section" - the UI renders nothing for it. Held (not cleared) across
    // search reloads, so clearing the search restores the shelf without a refetch.
    val shelfItems: List<LibraryItemSummary> = emptyList(),
    // The shelf slot's inline tap-to-retry row: the last shelf fetch failed with nothing held.
    // Mutually exclusive with a non-empty shelfItems (stale data beats a failed refresh), and
    // never raised alongside the full-screen `error` - one message at a time. Distinct from an
    // empty shelf, which renders no section at all: a broken shelf must never look like "no
    // books in progress".
    val shelfError: Boolean = false,
    // Cursor for the page after the ones already in `items`; null once the last page is in.
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    // The last load-more attempt failed; the footer offers a tap-to-retry instead of a spinner.
    val loadMoreError: Boolean = false,
    // A pull-to-refresh is reloading over an existing list (drives the pull indicator).
    val refreshing: Boolean = false,
    // One-shot error for a refresh that failed while a list was already shown (a snackbar).
    val refreshError: UiError? = null,
    val error: UiError? = null,
)

// A single reload of the library. `userTriggered` distinguishes a pull-to-refresh / retry (which
// reloads over an existing list without the full-screen spinner) from a query change.
private data class Reload(val query: String?, val userTriggered: Boolean)

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

    // Explicit reload requests (pull-to-refresh, retry-after-error). Buffered so one is kept if
    // it arrives mid-load and coalesced with any others.
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Whether the shelf has ever loaded successfully. Distinguishes the first no-query load
    // (fetch the shelf) from a search being cleared (redisplay the held shelf, no refetch).
    private var shelfLoaded = false

    // Whether the screen has composed at least once for this ViewModel. The first composition
    // belongs to the initial load (which already carries the shelf fetch); every later one is
    // a re-entry (back from Now Playing, etc.) and refetches the shelf silently.
    private var screenEntered = false

    // The in-flight shelf-only refresh, so a newer one replaces a still-running older one
    // instead of racing it for the state write.
    private var shelfRefresh: Job? = null

    init {
        // One reload pipeline for the whole screen. Two sources feed it, both funnelled through
        // the same collectLatest so a newer reload cancels any in-flight load or page fetch:
        //   - query changes: the initial (null) load runs immediately, keystrokes are debounced,
        //     identical queries ignored.
        //   - explicit refreshes: reload the CURRENT query, bypassing debounce and NOT swallowed
        //     by distinctUntilChanged (a refresh of the same query must actually re-fetch).
        // Follow-up pages are collected INSIDE the block, so they belong to the current reload and
        // die with it - a stale cursor can never append to fresh results.
        viewModelScope.launch {
            val queryChanges = query
                .debounce { if (it == null) 0L else SEARCH_DEBOUNCE_MS }
                .distinctUntilChanged()
                .map { Reload(it, userTriggered = false) }
            val refreshes = refreshRequests.map { Reload(query.value, userTriggered = true) }
            merge(queryChanges, refreshes).collectLatest { reload ->
                load(reload.query, reload.userTriggered)
                loadMoreRequests.collect { loadNextPage(reload.query) }
            }
        }
    }

    fun search(query: String) {
        this.query.value = query.ifBlank { null }
    }

    fun loadMore() {
        loadMoreRequests.tryEmit(Unit)
    }

    /** Pull-to-refresh, or retry after a load error: reloads the current query in place. */
    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    fun clearRefreshError() {
        _uiState.value = _uiState.value.copy(refreshError = null)
    }

    /** Called every time the Library screen enters composition (see [screenEntered]). */
    fun onScreenEntered() {
        if (!screenEntered) {
            screenEntered = true
            return
        }
        refreshShelf()
    }

    /**
     * Refetches ONLY the shelf: the error row's tap-to-retry, and the silent refetch on
     * re-entering the Library. The browse list, its cursor, and the scroll position stay
     * untouched, and no loading state is raised. Skipped while a query is active (the shelf
     * is never fetched during a search) or while a full reload is running (that reload
     * carries its own shelf fetch).
     */
    fun refreshShelf() {
        if (query.value != null || _uiState.value.loading || _uiState.value.refreshing) return
        shelfRefresh?.cancel()
        shelfRefresh = viewModelScope.launch {
            when (val result = connectionManager.client()?.listInProgressItems()) {
                is ApiResult.Success -> {
                    shelfLoaded = true
                    _uiState.value = _uiState.value.copy(shelfItems = result.data, shelfError = false)
                }
                // Failed, or the server became unconfigured: stale beats error - held rows
                // stay; the error row appears only when there is nothing to hold on to.
                else -> _uiState.value = _uiState.value.copy(shelfError = _uiState.value.shelfItems.isEmpty())
            }
        }
    }

    private suspend fun load(query: String?, userTriggered: Boolean = false) {
        // A user-triggered reload OVER an existing list shows the pull indicator and keeps the
        // list; everything else (initial load, query change, retry with nothing shown) uses the
        // full-screen spinner.
        val showAsRefresh = userTriggered && _uiState.value.items.isNotEmpty()
        _uiState.value =
            if (showAsRefresh) _uiState.value.copy(refreshing = true, refreshError = null)
            else _uiState.value.copy(loading = true, error = null)
        val client = connectionManager.client()
        if (client == null) {
            // Same rule as the failure branch below: a refresh over an existing list keeps the
            // list and reports via the snackbar rather than wiping to the full-screen error.
            _uiState.value =
                if (showAsRefresh) _uiState.value.copy(refreshing = false, refreshError = UiError.NoServer)
                else LibraryUiState(error = UiError.NoServer, shelfItems = _uiState.value.shelfItems)
            return
        }
        // The shelf is never fetched while a query is active: search results get the full
        // screen, and the held shelf reappears on clear WITHOUT a refetch - which is also why
        // a query-clear reload (query back to null, shelf already loaded) skips it. With no
        // query it rides along on the first load and on user-triggered reloads, in parallel
        // with the page; the single loading state waits for both, so the shelf never pops in
        // after the list.
        val fetchShelf = query == null && (userTriggered || !shelfLoaded)
        coroutineScope {
            val shelfDeferred = if (fetchShelf) async { client.listInProgressItems() } else null
            val pageResult = client.listLibraryItems(query = query)
            val held = _uiState.value.shelfItems
            val (shelfItems, shelfError) = when (val shelfResult = shelfDeferred?.await()) {
                is ApiResult.Success -> {
                    shelfLoaded = true
                    shelfResult.data to false
                }
                // Failed: keep whatever is held - stale beats a blanked shelf. The error row
                // is raised only when nothing is held, so it can never cover live data.
                is ApiResult.Failure -> held to held.isEmpty()
                // Not fetched (search, or query cleared with the shelf already loaded): carry
                // the slot through unchanged.
                null -> held to _uiState.value.shelfError
            }
            _uiState.value = when (pageResult) {
                is ApiResult.Success ->
                    LibraryUiState(
                        items = pageResult.data.items,
                        nextCursor = pageResult.data.nextCursor,
                        shelfItems = shelfItems,
                        shelfError = shelfError,
                    )
                is ApiResult.Failure ->
                    if (showAsRefresh) {
                        // A refresh over an existing list failed: keep the list, report via a
                        // snackbar - but a shelf result that DID land in parallel still applies.
                        _uiState.value.copy(
                            refreshing = false,
                            refreshError = UiError.Domain(pageResult.error),
                            shelfItems = shelfItems,
                            shelfError = shelfError,
                        )
                    } else {
                        // The full-screen error owns the whole screen (its retry refetches the
                        // shelf too), so the shelf slot's own error row is never raised with it
                        // - one message, even when both requests failed.
                        LibraryUiState(error = UiError.Domain(pageResult.error), shelfItems = shelfItems)
                    }
            }
        }
    }

    private suspend fun loadNextPage(query: String?) {
        val cursor = _uiState.value.nextCursor ?: return
        val client = connectionManager.client() ?: return
        _uiState.value = _uiState.value.copy(loadingMore = true, loadMoreError = false)
        _uiState.value = when (val result = client.listLibraryItems(query = query, cursor = cursor)) {
            is ApiResult.Success -> {
                // Drop ids already loaded before appending: a duplicate id (cursor drift from a
                // concurrent ABS-side mutation, or a server pagination bug) would otherwise crash
                // the LazyColumn, whose item key must be unique. If the page brings nothing new,
                // stop paginating - a server that keeps returning a non-null cursor with no
                // forward progress would make the near-end trigger re-fire without end.
                // The list grows unbounded by design for now (no windowing); see issue #65.
                val seen = _uiState.value.items.mapTo(HashSet(_uiState.value.items.size)) { it.id }
                val fresh = result.data.items.filter { seen.add(it.id) }
                _uiState.value.copy(
                    items = _uiState.value.items + fresh,
                    nextCursor = if (fresh.isEmpty()) null else result.data.nextCursor,
                    loadingMore = false,
                )
            }
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
    val snackbarHostState = remember { SnackbarHostState() }

    // On process-death restore the text field's saved query comes back, but the ViewModel's
    // search flow restarts at null (full library). Re-issue the restored query so the results
    // match what the search box shows. Then report the (re-)entry: re-entering the Library
    // (e.g. back from Now Playing) silently refetches the shelf so the just-played book leads
    // it - the search() call must come first so an active query keeps the shelf fetch gated.
    LaunchedEffect(Unit) {
        if (query.isNotBlank()) viewModel.search(query)
        viewModel.onScreenEntered()
    }

    // A refresh that failed while the list stayed on screen: a one-shot snackbar that keeps the
    // list AND offers retry as its action (errors always offer retry first).
    val refreshError = state.refreshError
    val refreshErrorText = refreshError?.text()
    val retryLabel = stringResource(R.string.library_retry)
    LaunchedEffect(refreshError) {
        if (refreshErrorText != null) {
            val result = snackbarHostState.showSnackbar(
                message = refreshErrorText,
                actionLabel = retryLabel,
            )
            viewModel.clearRefreshError()
            if (result == SnackbarResult.ActionPerformed) viewModel.refresh()
        }
    }

    // No nested Scaffold: MainActivity already hosts the single top-level Scaffold under
    // enableEdgeToEdge(), so a second one would compute system-bar insets a second time. Host the
    // snackbar in a Box overlay instead - the content is already inset by the outer Scaffold, so a
    // bottom-aligned SnackbarHost clears the navigation bar.
    Box(Modifier.fillMaxSize()) {
        LibraryContent(
            state = state,
            query = query,
            onQueryChange = {
                query = it
                viewModel.search(it)
            },
            onLoadMore = viewModel::loadMore,
            onRefresh = viewModel::refresh,
            onRetryShelf = viewModel::refreshShelf,
            onOpenItem = onOpenItem,
            onOpenNowPlaying = onOpenNowPlaying,
            onOpenSettings = onOpenSettings,
        )
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

// How many rows before the end of the list the next page is requested.
private const val LOAD_MORE_THRESHOLD = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(
    state: LibraryUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetryShelf: () -> Unit = {},
    onOpenItem: (String) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Horizontal padding sits on the top bar and search field, NOT the whole column: the list
    // below needs the full width so the shelf's tonal band can run edge to edge.
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
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
            // Full pill - the design's search-field shape (ux-design: Shape tokens).
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(UiTestTags.LIBRARY_SEARCH),
        )
        when {
            // The delay covers both first load and search: quick responses never flash the
            // loader, only genuinely slow ones escalate to the knot.
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (rememberDelayedVisible(state.loading)) KnotLoader()
            }

            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        state.error.text(),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRefresh) {
                        Text(stringResource(R.string.library_retry))
                    }
                }
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
                // The shelf hides the moment the search FIELD is non-blank (not the debounced
                // query): results get the full screen instantly, and the held shelf comes
                // straight back when the field is cleared. A failed shelf keeps the slot
                // (band, both headers) with an inline tap-to-retry row where its books would
                // be - "failed" must never look like "no books in progress" (which renders no
                // section at all).
                val shelfVisible = query.isBlank() && state.shelfItems.isNotEmpty()
                val shelfErrorVisible = query.isBlank() && state.shelfError && state.shelfItems.isEmpty()
                val shelfSlotVisible = shelfVisible || shelfErrorVisible
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Row spacing is each row's bottom padding (not spacedBy): the shelf's
                        // band must also fill the gaps between its rows, and item-owned padding
                        // keeps the band continuous where an arrangement gap would slice it.
                        contentPadding = PaddingValues(top = if (shelfSlotVisible) 12.dp else 16.dp, bottom = 24.dp),
                    ) {
                        if (shelfSlotVisible) {
                            // Keys are namespaced per section ("shelf:"/"browse:"): shelf books
                            // also keep their place in the browse list below (no deduplication),
                            // so a bare item id could occur twice in one LazyColumn.
                            item(key = "shelf-header") {
                                LibrarySectionHeader(
                                    stringResource(R.string.library_shelf_header),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                )
                            }
                            items(state.shelfItems, key = { "shelf:${it.id}" }) { item ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .padding(horizontal = 16.dp)
                                        .padding(bottom = 8.dp),
                                ) {
                                    LibraryRow(item, onClick = { onOpenItem(item.id) })
                                }
                            }
                            if (shelfErrorVisible) {
                                item(key = "shelf-error") {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceContainer)
                                            .padding(horizontal = 16.dp)
                                            .padding(bottom = 8.dp),
                                    ) {
                                        ShelfErrorRow(onRetry = onRetryShelf)
                                    }
                                }
                            }
                            // The hairline that closes the tonal band (screen 03, decision 6).
                            item(key = "shelf-edge") {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            item(key = "browse-header") {
                                LibrarySectionHeader(stringResource(R.string.library_all_books_header))
                            }
                        }
                        items(state.items, key = { "browse:${it.id}" }) { item ->
                            Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                                LibraryRow(item, onClick = { onOpenItem(item.id) })
                            }
                        }
                        if (state.nextCursor != null) {
                            item(key = "load-more") {
                                if (state.loadMoreError) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clickable(onClick = onLoadMore)
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
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

// Marked as a heading so the shelf/browse boundary is navigable non-visually, mirroring what
// the tonal band does for sighted users.
@Composable
private fun LibrarySectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .semantics { heading() }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
    )
}

// The shelf slot's failure state (screen 03, .shelf-err): one tappable row in the place the
// shelf's books would occupy, styled as an error so it cannot be read as content. The whole
// row retries, and it refetches ONLY the shelf - the browse list below keeps working.
@Composable
private fun ShelfErrorRow(onRetry: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onRetry),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.library_shelf_error_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    stringResource(R.string.library_shelf_error_retry),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItemSummary, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().testTag(UiTestTags.LIBRARY_ROW).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(
                title = item.title,
                coverUrl = item.coverUrl,
                modifier = Modifier.size(56.dp),
            )
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

// --- Previews (render in Android Studio without a running server) --------------------------

private val previewItems = listOf(
    LibraryItemSummary("1", "The Hobbit", "J. R. R. Tolkien", 39_600.0, null, Progress(12_600.0, false)),
    LibraryItemSummary("2", "Project Hail Mary", "Andy Weir", 57_600.0, null, Progress(57_600.0, true)),
    LibraryItemSummary("3", "Dune", "Frank Herbert", 75_600.0, null, null),
)

// Most-recently-listened first, as the server would deliver it. "The Hobbit" also sits in
// previewItems: the shelf is a view onto the library, not a partition, so the same book
// appearing in both sections is the state to preview.
private val previewShelfItems = listOf(
    LibraryItemSummary("4", "A Wizard of Earthsea", "Ursula K. Le Guin", 25_200.0, null, Progress(9_100.0, false)),
    LibraryItemSummary("1", "The Hobbit", "J. R. R. Tolkien", 39_600.0, null, Progress(12_600.0, false)),
)

@Preview(name = "Library - shelf loaded", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryShelfLoadedPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems, shelfItems = previewShelfItems),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

// A non-blank search field hides the shelf and both headers even though the held shelf items
// are still in state - the visibility rule lives here in previews (spec #80's testing
// decision), to be picked up by the screenshot goldens (#76).
@Preview(name = "Library - searching, shelf hidden", widthDp = 360, heightDp = 800)
@Composable
internal fun LibrarySearchingShelfHiddenPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems.take(1), shelfItems = previewShelfItems),
            query = "hobbit",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

// The shelf failed with nothing held while the list works: the slot keeps the tonal band and
// both section headers, with the tap-to-retry error row where the shelf's books would be.
// Contrast with LibraryLoadedPreview below - a successfully EMPTY shelf renders no section at
// all, so "failed" and "empty" can never look the same.
@Preview(name = "Library - shelf failed", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryShelfErrorPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems, shelfError = true),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

// An empty shelf renders no section at all: this must look exactly like the screen before the
// shelf existed - no headers, no empty band.
@Preview(name = "Library - loaded, empty shelf", widthDp = 360, heightDp = 800)
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

@Preview(name = "Library - error", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryErrorPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(error = UiError.Domain(RatatoskrError.Upstream(code = null, message = "Audiobookshelf is unreachable."))),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}
