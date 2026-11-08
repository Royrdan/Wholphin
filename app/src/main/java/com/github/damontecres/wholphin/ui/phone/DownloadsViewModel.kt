package com.github.damontecres.wholphin.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.download.DownloadState
import com.github.damontecres.wholphin.data.download.DownloadedItem
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.download.DownloadRepository
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class DownloadGroup(
    val key: String,
    val title: String,
    val items: List<DownloadedItem>,
) {
    val totalBytes: Long get() = items.sumOf { if (it.bytesTotal > 0) it.bytesTotal else it.bytesDone }
    val doneCount: Int get() = items.count { it.state == DownloadState.DONE }
}

@HiltViewModel
class DownloadsViewModel
    @Inject
    constructor(
        private val repository: DownloadRepository,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        val groups: StateFlow<List<DownloadGroup>> =
            repository.downloads
                .map { rows ->
                    rows
                        .groupBy { it.groupKey }
                        .map { (key, items) ->
                            DownloadGroup(
                                key = key,
                                title = items.first().groupTitle,
                                items =
                                    items.sortedWith(
                                        compareBy({ it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }, { it.createdAt }),
                                    ),
                            )
                        }.sortedBy { it.title.lowercase() }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun play(item: DownloadedItem) {
            val start = if (item.playedToCompletion) 0L else item.lastPositionMs
            navigationManager.navigateTo(Destination.Playback(item.itemId, start))
        }

        fun pause(itemId: UUID) = io { repository.pause(itemId) }

        fun resume(itemId: UUID) = io { repository.resume(itemId) }

        fun retry(itemId: UUID) = io { repository.retry(itemId) }

        fun delete(itemId: UUID) = io { repository.delete(itemId) }

        fun deleteGroup(key: String) = io { repository.deleteGroup(key) }

        fun deleteAll() = io { repository.deleteAll() }

        fun pauseAll() = io { repository.pauseAll() }

        fun resumeAll() = io { repository.resumeAll() }

        private fun io(block: suspend () -> Unit) {
            viewModelScope.launch(Dispatchers.IO) { block() }
        }
    }
