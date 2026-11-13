package com.github.damontecres.wholphin.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.download.DownloadState
import com.github.damontecres.wholphin.data.download.DownloadedItem
import com.github.damontecres.wholphin.data.download.DownloadedSeries
import com.github.damontecres.wholphin.data.download.toBaseItemDto
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.download.DownloadRepository
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** One downloaded episode with its ready-made [BaseItem] for the normal cards/header. */
data class DownloadedEpisode(
    val row: DownloadedItem,
    val item: BaseItem,
) {
    val id: UUID get() = row.itemId
}

data class DownloadedSeason(
    val seasonNumber: Int?,
    val title: String,
    val episodes: List<DownloadedEpisode>,
)

data class DownloadedSeriesState(
    val series: DownloadedSeries? = null,
    val name: String = "",
    val seasons: List<DownloadedSeason> = emptyList(),
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = loaded && seasons.isEmpty()
}

/** Where the user is on the page (season tab + episode in the row), kept across recompositions. */
data class DownloadedSeriesPosition(
    val seasonIndex: Int = 0,
    val episodeIndex: Int = 0,
)

/**
 * Feeds [DownloadedSeriesPage] from the downloads database only: finished episodes of one show,
 * grouped by season, with watched state from the local row (kept in sync both ways by
 * PlaystateSyncWorker). Nothing here talks to the server.
 */
@HiltViewModel(assistedFactory = DownloadedSeriesViewModel.Factory::class)
class DownloadedSeriesViewModel
    @AssistedInject
    constructor(
        private val repository: DownloadRepository,
        private val serverRepository: ServerRepository,
        val navigationManager: NavigationManager,
        @Assisted val seriesId: UUID,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(seriesId: UUID): DownloadedSeriesViewModel
        }

        val position = MutableStateFlow(DownloadedSeriesPosition())

        val state: StateFlow<DownloadedSeriesState> =
            combine(repository.observeSeries(seriesId), repository.observeEpisodes(seriesId)) { series, rows ->
                val done = rows.filter { it.state == DownloadState.DONE }
                val seasons =
                    done
                        .groupBy { it.seasonNumber }
                        .toSortedMap(compareBy { it ?: Int.MAX_VALUE })
                        .map { (seasonNumber, eps) ->
                            DownloadedSeason(
                                seasonNumber = seasonNumber,
                                title =
                                    when {
                                        seasonNumber == null -> "Episodes"
                                        seasonNumber == 0 -> "Specials"
                                        else -> "Season $seasonNumber"
                                    },
                                episodes =
                                    eps
                                        .sortedWith(compareBy({ it.episodeNumber ?: Int.MAX_VALUE }, { it.createdAt }))
                                        .map { row ->
                                            DownloadedEpisode(
                                                row = row,
                                                item = BaseItem(row.toBaseItemDto(), useSeriesForPrimary = false, imageUrlOverride = row.displayImage),
                                            )
                                        },
                            )
                        }
                DownloadedSeriesState(
                    series = series,
                    name = series?.name ?: done.firstOrNull()?.seriesName ?: "",
                    seasons = seasons,
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadedSeriesState())

        fun play(
            episode: DownloadedEpisode,
            fromStart: Boolean = false,
        ) {
            val row = episode.row
            val start = if (fromStart || row.playedToCompletion) 0L else row.lastPositionMs
            navigationManager.navigateTo(Destination.Playback(row.itemId, start))
        }

        fun setWatched(
            episode: DownloadedEpisode,
            watched: Boolean,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                val user = serverRepository.currentUser
                repository.setWatched(episode.id, watched, user?.serverId?.toString(), user?.id?.toString())
            }
        }

        fun deleteEpisode(episode: DownloadedEpisode) {
            viewModelScope.launch(Dispatchers.IO) { repository.delete(episode.id) }
        }

        fun deleteShow() {
            viewModelScope.launch(Dispatchers.IO) {
                repository.deleteSeries(seriesId)
            }
        }

        fun goBack() = navigationManager.goBack()
    }
