package com.github.damontecres.wholphin.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.services.WatchlistService
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.ExceptionHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject

/**
 * Supplementary [ViewModel] that backs the "Add to / Remove from Watchlist" context-menu toggle.
 *
 * The watchlist is a local per-user list of movies/series that behaves like Favorites in the UI:
 * a single toggle, available everywhere the context menu is shown.
 * This VM tracks which item ids are currently on the list so the menu can show the right label
 * (Add vs Remove) and update optimistically.
 */
@HiltViewModel
class WatchlistViewModel
    @Inject
    constructor(
        private val watchlistService: WatchlistService,
    ) : ViewModel() {
        private val _watchlistIds = MutableStateFlow<Set<UUID>>(emptySet())
        val watchlistIds: StateFlow<Set<UUID>> = _watchlistIds

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launchIO {
                _watchlistIds.value = watchlistService.itemIds().toSet()
            }
        }

        fun add(itemId: UUID) {
            // Optimistic: reflect the change immediately, then confirm against the server.
            _watchlistIds.value = _watchlistIds.value + itemId
            viewModelScope.launchIO(ExceptionHandler(autoToast = true)) {
                watchlistService.add(itemId)
                refresh()
            }
        }

        fun remove(itemId: UUID) {
            _watchlistIds.value = _watchlistIds.value - itemId
            viewModelScope.launchIO(ExceptionHandler(autoToast = true)) {
                watchlistService.remove(itemId)
                refresh()
            }
        }
    }
