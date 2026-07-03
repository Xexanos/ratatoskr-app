/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.LibraryItemSummary
import io.github.xexanos.ratatoskr.ui.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class LibraryUiState(
    val loading: Boolean = false,
    val items: List<LibraryItemSummary> = emptyList(),
    val error: String? = null,
)

class LibraryViewModel(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init { load(null) }

    fun search(query: String) = load(query.ifBlank { null })

    private fun load(query: String?) {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val client = connectionManager.client()
            if (client == null) {
                _uiState.value = LibraryUiState(error = "No server configured.")
                return@launch
            }
            _uiState.value = when (val result = client.listLibraryItems(query = query)) {
                is ApiResult.Success -> LibraryUiState(items = result.data.items)
                is ApiResult.Failure -> LibraryUiState(error = result.error.toMessage())
            }
        }
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenNowPlaying) { Text("Now playing") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                viewModel.search(it)
            },
            label = { Text("Search library") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.loading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            state.error != null -> Text(
                state.error!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.id }) { item ->
                    LibraryRow(item, onClick = { onOpenItem(item.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItemSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        item.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val progress = item.progress
        if (progress != null && item.durationSeconds > 0) {
            val percent = (progress.positionSeconds / item.durationSeconds * 100).roundToInt()
            Text(
                if (progress.isFinished) "Finished" else "$percent% listened",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
