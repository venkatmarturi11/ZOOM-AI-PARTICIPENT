package com.zoomrecord.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ViewModel for the recordings list screen.
 * Groups recordings by day and provides delete/rename operations.
 */
class RecordingsListViewModel(private val repo: RecordingsRepository) : ViewModel() {

    private var allItems: List<RecordingItem> = emptyList()

    private val _state = MutableStateFlow<RecordingsUiState>(RecordingsUiState.Loading)
    val state: StateFlow<RecordingsUiState> = _state

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedTab = MutableStateFlow(0) // 0: All, 1: Cloud, 2: Local
    val selectedTab: StateFlow<Int> = _selectedTab

    /**
     * Loads all recordings from disk and MediaStore, applying current filters.
     */
    fun load() = viewModelScope.launch(Dispatchers.IO) {
        _state.value = RecordingsUiState.Loading
        allItems = repo.queryRecordings()
        applyFilter()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilter()
    }

    fun setSelectedTab(tabIndex: Int) {
        _selectedTab.value = tabIndex
        applyFilter()
    }

    private fun applyFilter() {
        val query = _searchQuery.value.trim().lowercase()
        val tab = _selectedTab.value

        var filtered = allItems

        // Filter by tab: 0 = All, 1 = Cloud, 2 = Local
        if (tab == 1) {
            // Cloud recordings (MediaStore/synced)
            filtered = filtered.filter { it.uri.scheme != "file" }
        } else if (tab == 2) {
            // Local files
            filtered = filtered.filter { it.uri.scheme == "file" }
        }

        // Filter by search query
        if (query.isNotBlank()) {
            filtered = filtered.filter { it.displayName.lowercase().contains(query) }
        }

        _state.value = if (filtered.isEmpty()) {
            RecordingsUiState.Empty
        } else {
            RecordingsUiState.Content(groupByDay(filtered))
        }
    }

    /**
     * Deletes a recording and reloads the list.
     */
    fun delete(item: RecordingItem) = viewModelScope.launch(Dispatchers.IO) {
        repo.delete(item.uri)
        load()
    }

    /**
     * Renames a recording and reloads the list.
     */
    fun rename(item: RecordingItem, newName: String) = viewModelScope.launch(Dispatchers.IO) {
        repo.rename(item.uri, newName)
        load()
    }

    /**
     * Groups recordings by day with friendly labels (Today, Yesterday, or date).
     */
    private fun groupByDay(items: List<RecordingItem>): List<RecordingsSection> {
        val today = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern("d MMM yyyy")

        return items
            .groupBy { epochSecToLocalDate(it.dateAddedEpochSec) }
            .toSortedMap(compareByDescending { it })
            .map { (date, list) ->
                val label = when (date) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> date.format(formatter)
                }
                RecordingsSection(label, list)
            }
    }

    private fun epochSecToLocalDate(epochSec: Long): LocalDate =
        try {
            Instant.ofEpochSecond(epochSec)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        } catch (_: Exception) {
            LocalDate.now()
        }

    /**
     * Factory for creating this ViewModel with a [RecordingsRepository] dependency.
     */
    class Factory(private val repo: RecordingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RecordingsListViewModel(repo) as T
        }
    }
}

// ── UI State ─────────────────────────────────────────────────────────

sealed interface RecordingsUiState {
    data object Loading : RecordingsUiState
    data object Empty : RecordingsUiState
    data class Content(val sections: List<RecordingsSection>) : RecordingsUiState
}

data class RecordingsSection(val label: String, val items: List<RecordingItem>)
