package com.github.damontecres.wholphin.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.download.DownloadState
import com.github.damontecres.wholphin.data.download.DownloadedItem
import com.github.damontecres.wholphin.data.download.DownloadedSeries
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.download.DownloadRepository
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** One show tile in the Downloads library. */
data class DownloadedShow(
    val series: DownloadedSeries?,
    val seriesId: UUID,
    val name: String,
    val episodes: List<DownloadedItem>,
) {
    val doneCount: Int get() = episodes.size
    val unwatchedCount: Int get() = episodes.count { !it.playedToCompletion }
    val allWatched: Boolean get() = episodes.isNotEmpty() && unwatchedCount == 0
    val poster: String? get() = series?.displayPoster ?: episodes.firstOrNull()?.displayImage
    val totalBytes: Long get() = episodes.sumOf { it.bytesTotal }
}

data class DownloadsHomeState(
    val inProgress: List<DownloadedItem> = emptyList(),
    val shows: List<DownloadedShow> = emptyList(),
    val movies: List<DownloadedItem> = emptyList(),
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = inProgress.isEmpty() && shows.isEmpty() && movies.isEmpty()
    val anyActive: Boolean get() = inProgress.any { it.state.isActive }
    val anyPaused: Boolean get() = inProgress.any { it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED }
}

@HiltViewModel
class DownloadsViewModel
    @Inject
    constructor(
        private val repository: DownloadRepository,
        private val serverRepository: ServerRepository,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        val state: StateFlow<DownloadsHomeState> =
            combine(repository.downloads, repository.series) { rows, seriesRows ->
                val bySeries = seriesRows.associateBy { it.seriesId }
                val done = rows.filter { it.state == DownloadState.DONE }
                val shows =
                    done
                        .filter { it.seriesId != null }
                        .groupBy { it.seriesId!! }
                        .map { (seriesId, eps) ->
                            DownloadedShow(
                                series = bySeries[seriesId],
                                seriesId = seriesId,
                                name = bySeries[seriesId]?.name ?: eps.first().seriesName ?: "Show",
                                episodes =
                                    eps.sortedWith(
                                        compareBy({ it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }, { it.createdAt }),
                                    ),
                            )
                        }.sortedBy { it.name.lowercase() }
                DownloadsHomeState(
                    inProgress =
                        rows
                            .filter { it.state != DownloadState.DONE }
                            .sortedWith(compareBy({ it.state.isActive.not() }, { it.createdAt })),
                    shows = shows,
                    movies = done.filter { it.seriesId == null }.sortedBy { it.title.lowercase() },
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsHomeState())

        init {
            // Whenever the library is opened, try to pull the server's watched state (no-op offline).
            repository.scheduleSync("downloads page opened")
        }

        fun play(item: DownloadedItem) {
            val start = if (item.playedToCompletion) 0L else item.lastPositionMs
            navigationManager.navigateTo(Destination.Playback(item.itemId, start))
        }

        fun openShow(show: DownloadedShow) {
            navigationManager.navigateTo(Destination.DownloadedSeries(show.seriesId))
        }

        fun pause(itemId: UUID) = io { repository.pause(itemId) }

        fun resume(itemId: UUID) = io { repository.resume(itemId) }

        fun retry(itemId: UUID) = io { repository.retry(itemId) }

        fun delete(itemId: UUID) = io { repository.delete(itemId) }

        fun deleteShow(seriesId: UUID) = io { repository.deleteSeries(seriesId) }

        fun deleteAll() = io { repository.deleteAll() }

        fun pauseAll() = io { repository.pauseAll() }

        fun resumeAll() = io { repository.resumeAll() }

        fun setWatched(
            itemId: UUID,
            watched: Boolean,
        ) = io {
            val user = serverRepository.currentUser
            repository.setWatched(itemId, watched, user?.serverId?.toString(), user?.id?.toString())
        }

        private fun io(block: suspend () -> Unit) {
            viewModelScope.launch(Dispatchers.IO) { block() }
        }
    }
