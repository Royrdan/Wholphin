package com.github.damontecres.wholphin.ui.phone

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.download.DownloadState
import com.github.damontecres.wholphin.data.download.DownloadedItem
import com.github.damontecres.wholphin.data.download.DownloadedSeries
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.StreamChoiceService
import com.github.damontecres.wholphin.services.download.DownloadQuality
import com.github.damontecres.wholphin.services.download.DownloadRepository
import com.github.damontecres.wholphin.services.download.DownloadResolveResult
import com.github.damontecres.wholphin.services.download.DownloadResolver
import com.github.damontecres.wholphin.util.TmdbRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What the download picker was opened for. */
sealed interface DownloadTarget {
    data class Movie(
        val item: BaseItem,
    ) : DownloadTarget

    data class Series(
        val seriesId: UUID,
        val seriesName: String?,
        val currentSeasonId: UUID? = null,
        val currentSeasonNumber: Int? = null,
    ) : DownloadTarget

    data class Episode(
        val item: BaseItem,
    ) : DownloadTarget
}

enum class DownloadScope(
    val label: String,
) {
    NEXT_UNWATCHED("Next unwatched"),
    THIS_SEASON("This season"),
    WHOLE_SHOW("Whole show"),
    PICK("Pick episodes"),
}

data class PickerEpisode(
    val item: BaseItem,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val resolverPath: String?,
    val played: Boolean,
    val runtime: Duration?,
    val existing: DownloadedItem?,
) {
    val id: UUID get() = item.id
    val downloadable: Boolean get() = resolverPath != null
    val label: String
        get() =
            if (seasonNumber != null && episodeNumber != null) {
                "S%02dE%02d  %s".format(seasonNumber, episodeNumber, item.name ?: "")
            } else {
                item.name ?: ""
            }
}

data class DownloadPickerState(
    val loading: Boolean = true,
    val error: String? = null,
    val isShow: Boolean = false,
    val title: String = "",
    val quality: DownloadQuality = DownloadQuality.DEFAULT,
    val scope: DownloadScope = DownloadScope.NEXT_UNWATCHED,
    val nextCount: Int = 5,
    val picked: Set<UUID> = emptySet(),
    val episodes: List<PickerEpisode> = emptyList(),
    val currentSeasonId: UUID? = null,
    val currentSeasonNumber: Int? = null,
    // Bytes of one representative file at the chosen tier; null while unknown.
    val sampleBytes: Long? = null,
    val sizePending: Boolean = false,
    val sizeUnavailable: Boolean = false,
    val enqueuedCount: Int? = null,
) {
    val selected: List<PickerEpisode>
        get() {
            if (!isShow) return episodes.filter { it.downloadable }
            val ordered =
                episodes
                    .filter { it.downloadable }
                    .sortedWith(compareBy({ it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }))
            return when (scope) {
                DownloadScope.NEXT_UNWATCHED ->
                    ordered
                        .filter { (it.seasonNumber ?: 0) > 0 && !it.played && it.existing?.state != DownloadState.DONE }
                        .take(nextCount)

                DownloadScope.THIS_SEASON ->
                    ordered.filter {
                        (currentSeasonId != null && it.item.data.seasonId == currentSeasonId) ||
                            (currentSeasonId == null && it.seasonNumber == currentSeasonNumber)
                    }

                DownloadScope.WHOLE_SHOW -> ordered
                DownloadScope.PICK -> ordered.filter { it.id in picked }
            }
        }

    val selectedCount: Int get() = selected.size
    val alreadyDownloaded: Int get() = selected.count { it.existing != null }
    val toDownload: Int get() = selected.count { it.existing == null }
    val totalRuntime: Duration get() = selected.fold(Duration.ZERO) { acc, e -> acc + (e.runtime ?: Duration.ZERO) }
    val runtimeKnownFor: Int get() = selected.count { it.runtime != null }
    val estimatedBytes: Long? get() = sampleBytes?.let { it * toDownload }
}

@HiltViewModel
class DownloadPickerViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val streamChoiceService: StreamChoiceService,
        private val downloadRepository: DownloadRepository,
        private val imageUrlService: ImageUrlService,
    ) : ViewModel() {
        private val _state = MutableStateFlow(DownloadPickerState())
        val state: StateFlow<DownloadPickerState> = _state

        private var loadJob: Job? = null
        private var sizeJob: Job? = null
        private var runtimeJob: Job? = null
        private val sizeCache = ConcurrentHashMap<String, Long>()
        private var loadedTarget: DownloadTarget? = null

        fun load(target: DownloadTarget) {
            if (loadedTarget == target && !_state.value.loading && _state.value.error == null) return
            loadedTarget = target
            loadJob?.cancel()
            sizeJob?.cancel()
            runtimeJob?.cancel()
            _state.value = DownloadPickerState()
            loadJob =
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val quality = runCatching { DownloadPrefs.quality(context).first() }.getOrDefault(DownloadQuality.DEFAULT)
                        when (target) {
                            is DownloadTarget.Movie -> loadMovie(target.item, quality)
                            is DownloadTarget.Series -> loadSeries(target, quality)
                            is DownloadTarget.Episode -> loadEpisodeTarget(target.item, quality)
                        }
                        refreshEstimates()
                    } catch (ex: Exception) {
                        Timber.e(ex, "Download picker load failed")
                        _state.update { it.copy(loading = false, error = ex.message ?: "Could not load episodes") }
                    }
                }
        }

        private suspend fun loadMovie(
            item: BaseItem,
            quality: DownloadQuality,
        ) {
            // Re-fetch to be sure media sources are present
            val dto =
                if (item.data.mediaSources.isNullOrEmpty()) {
                    runCatching { api.userLibraryApi.getItem(item.id).content }.getOrDefault(item.data)
                } else {
                    item.data
                }
            val ep = toPickerEpisode(dto, existingFor(listOf(dto.id)))
            _state.update {
                it.copy(
                    loading = false,
                    isShow = false,
                    title = dto.name ?: "",
                    quality = quality,
                    episodes = listOf(ep),
                )
            }
        }

        private suspend fun loadSeries(
            target: DownloadTarget.Series,
            quality: DownloadQuality,
        ) {
            val episodes = fetchSeriesEpisodes(target.seriesId)
            val isSeasonContext = target.currentSeasonId != null || target.currentSeasonNumber != null
            _state.update {
                it.copy(
                    loading = false,
                    isShow = true,
                    title = target.seriesName ?: "",
                    quality = quality,
                    scope = if (isSeasonContext) DownloadScope.THIS_SEASON else DownloadScope.NEXT_UNWATCHED,
                    episodes = episodes,
                    currentSeasonId = target.currentSeasonId,
                    currentSeasonNumber = target.currentSeasonNumber,
                )
            }
        }

        private suspend fun loadEpisodeTarget(
            item: BaseItem,
            quality: DownloadQuality,
        ) {
            val seriesId = item.data.seriesId
            if (seriesId == null) {
                loadMovie(item, quality)
                return
            }
            val episodes = fetchSeriesEpisodes(seriesId)
            // The tapped episode may itself be a "-2" duplicate; select whichever copy survived dedupe.
            val match =
                episodes.firstOrNull { it.id == item.id }
                    ?: episodes.firstOrNull {
                        it.seasonNumber == item.data.parentIndexNumber && it.episodeNumber == item.data.indexNumber
                    }
            _state.update {
                it.copy(
                    loading = false,
                    isShow = true,
                    title = item.data.seriesName ?: "",
                    quality = quality,
                    scope = DownloadScope.PICK,
                    picked = setOfNotNull(match?.id),
                    episodes = episodes,
                    currentSeasonId = item.data.seasonId,
                    currentSeasonNumber = item.data.parentIndexNumber,
                )
            }
        }

        private suspend fun fetchSeriesEpisodes(seriesId: UUID): List<PickerEpisode> {
            val userId = serverRepository.currentUser?.id
            val result =
                api.tvShowsApi
                    .getEpisodes(
                        seriesId = seriesId,
                        userId = userId,
                        fields =
                            listOf(
                                ItemFields.MEDIA_SOURCES,
                                ItemFields.PATH,
                                ItemFields.PROVIDER_IDS,
                                ItemFields.PARENT_ID,
                            ),
                    ).content.items
            val deduped = dedupe(result)
            val existing = existingFor(deduped.map { it.id })
            return deduped.map { toPickerEpisode(it, existing) }
        }

        /**
         * Jellyfin holds a duplicate ".strm" version of each episode (names ending "-2"). Keep one
         * row per season+episode number, preferring the copy without the suffix.
         */
        private fun dedupe(items: List<BaseItemDto>): List<BaseItemDto> {
            fun isDuplicateCopy(dto: BaseItemDto): Boolean {
                val path = dto.path?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
                val srcName = dto.mediaSources?.firstOrNull()?.name ?: ""
                val name = dto.name ?: ""
                return path.endsWith("-2") || srcName.endsWith("-2") || name.endsWith("-2")
            }
            val byKey = LinkedHashMap<String, BaseItemDto>()
            items.forEach { dto ->
                val key =
                    if (dto.parentIndexNumber != null && dto.indexNumber != null) {
                        "${dto.parentIndexNumber}:${dto.indexNumber}"
                    } else {
                        dto.id.toString()
                    }
                val current = byKey[key]
                if (current == null || (isDuplicateCopy(current) && !isDuplicateCopy(dto))) {
                    byKey[key] = dto
                }
            }
            return byKey.values.toList()
        }

        private suspend fun existingFor(ids: List<UUID>): Map<UUID, DownloadedItem> =
            downloadRepository.getByIds(ids).associateBy { it.itemId }

        private fun toPickerEpisode(
            dto: BaseItemDto,
            existing: Map<UUID, DownloadedItem>,
        ): PickerEpisode {
            val item = BaseItem(dto)
            val resolverPath = DownloadResolver.resolverPathFor(dto) { streamChoiceService.chooseSource(it) }
            val runtime =
                dto.runTimeTicks?.takeIf { it > 0 }?.let { (it / 10_000).milliseconds }
                    ?: TmdbRuntime.cached(item)
            return PickerEpisode(
                item = item,
                seasonNumber = dto.parentIndexNumber,
                episodeNumber = dto.indexNumber,
                resolverPath = resolverPath,
                played = dto.userData?.played == true,
                runtime = runtime,
                existing = existing[dto.id],
            )
        }

        // ---- user choices ----

        fun setQuality(quality: DownloadQuality) {
            _state.update { it.copy(quality = quality) }
            viewModelScope.launch { runCatching { DownloadPrefs.setQuality(context, quality) } }
            refreshEstimates()
        }

        fun setScope(scope: DownloadScope) {
            _state.update { it.copy(scope = scope) }
            refreshEstimates()
        }

        fun setNextCount(count: Int) {
            _state.update { it.copy(nextCount = count.coerceIn(1, 50)) }
            refreshEstimates()
        }

        fun togglePicked(id: UUID) {
            _state.update {
                val picked = it.picked.toMutableSet()
                if (!picked.add(id)) picked.remove(id)
                it.copy(picked = picked)
            }
            refreshEstimates()
        }

        fun pickSeason(seasonNumber: Int?) {
            _state.update { st ->
                val ids = st.episodes.filter { it.seasonNumber == seasonNumber && it.downloadable }.map { it.id }
                val allPicked = ids.all { it in st.picked }
                val picked = st.picked.toMutableSet()
                if (allPicked) picked.removeAll(ids.toSet()) else picked.addAll(ids)
                st.copy(picked = picked)
            }
            refreshEstimates()
        }

        /** Fill in missing runtimes (TMDB fallback) and the size sample, without blocking the sheet. */
        private fun refreshEstimates() {
            val st = _state.value
            if (st.loading) return
            // Runtimes
            runtimeJob?.cancel()
            val missing = st.selected.filter { it.runtime == null && TmdbRuntime.needsLookup(it.item) }.take(40)
            if (missing.isNotEmpty()) {
                runtimeJob =
                    viewModelScope.launch(Dispatchers.IO) {
                        for (ep in missing) {
                            val d = runCatching { TmdbRuntime.lookup(ep.item) }.getOrNull() ?: continue
                            _state.update { s ->
                                s.copy(episodes = s.episodes.map { if (it.id == ep.id && it.runtime == null) it.copy(runtime = d) else it })
                            }
                        }
                    }
            }
            // Size sample: resolve the first item needing download at this tier and HEAD the CDN file.
            sizeJob?.cancel()
            val sample = st.selected.firstOrNull { it.existing == null } ?: st.selected.firstOrNull()
            if (sample?.resolverPath == null) {
                _state.update { it.copy(sampleBytes = null, sizePending = false, sizeUnavailable = sample != null) }
                return
            }
            val url = DownloadResolver.withQuality(sample.resolverPath, st.quality)
            sizeCache[url]?.let { bytes ->
                _state.update { it.copy(sampleBytes = bytes, sizePending = false, sizeUnavailable = false) }
                return
            }
            _state.update { it.copy(sampleBytes = null, sizePending = true, sizeUnavailable = false) }
            sizeJob =
                viewModelScope.launch(Dispatchers.IO) {
                    delay(400) // debounce chip taps
                    val bytes =
                        when (val r = DownloadResolver.resolve(url)) {
                            is DownloadResolveResult.Success -> DownloadResolver.contentLength(r.url)
                            else -> {
                                Timber.i("size sample: resolve gave %s", r)
                                null
                            }
                        }
                    if (bytes != null) sizeCache[url] = bytes
                    _state.update { it.copy(sampleBytes = bytes, sizePending = false, sizeUnavailable = bytes == null) }
                }
        }

        /** Show row for the Downloads library (name, year, overview, poster + backdrop URLs). */
        private suspend fun buildSeriesRow(rows: List<DownloadedItem>): DownloadedSeries? {
            val seriesId = rows.firstOrNull { it.seriesId != null }?.seriesId ?: return null
            val fallbackName = rows.firstNotNullOfOrNull { it.seriesName } ?: ""
            val dto = runCatching { api.userLibraryApi.getItem(seriesId).content }.getOrNull()
            val item = dto?.let { BaseItem(it) }
            return DownloadedSeries(
                seriesId = seriesId,
                serverId = serverRepository.currentServer?.id?.toString(),
                name = dto?.name ?: fallbackName,
                year = dto?.productionYear,
                overview = dto?.overview,
                posterUrl =
                    item?.let { runCatching { imageUrlService.getItemImageUrl(it, ImageType.PRIMARY, fillWidth = 600) }.getOrNull() }
                        ?: imageUrlService.getItemImageUrl(seriesId, ImageType.PRIMARY, maxWidth = 600),
                backdropUrl =
                    item?.let { runCatching { imageUrlService.getItemImageUrl(it, ImageType.BACKDROP, fillWidth = 1280) }.getOrNull() }
                        ?: imageUrlService.getItemImageUrl(seriesId, ImageType.BACKDROP, maxWidth = 1280),
            )
        }

        /** Queue everything currently selected. Returns the number of new rows. */
        fun enqueue(onDone: (Int) -> Unit) {
            val st = _state.value
            viewModelScope.launch(Dispatchers.IO) {
                val serverId = serverRepository.currentServer?.id?.toString()
                val now = System.currentTimeMillis()
                val rows =
                    st.selected
                        .filter { it.existing == null && it.resolverPath != null }
                        .mapIndexed { index, ep ->
                            val dto = ep.item.data
                            DownloadedItem(
                                itemId = dto.id,
                                serverId = serverId,
                                seriesId = dto.seriesId,
                                seriesName = dto.seriesName,
                                seasonId = dto.seasonId,
                                seasonNumber = dto.parentIndexNumber,
                                episodeNumber = dto.indexNumber,
                                title = dto.name ?: "Untitled",
                                imageUrl =
                                    runCatching {
                                        // Episode thumbnail (not the series poster): the show art lives on DownloadedSeries.
                                        imageUrlService.getItemImageUrl(
                                            ep.item,
                                            ImageType.PRIMARY,
                                            fillWidth = 480,
                                            useSeriesForPrimary = false,
                                        )
                                    }.getOrNull(),
                                overview = dto.overview,
                                premiereYear = dto.productionYear ?: dto.premiereDate?.year,
                                resolverUrl = DownloadResolver.withQuality(ep.resolverPath!!, st.quality),
                                qualityTier = st.quality.param,
                                createdAt = now + index, // keep queue order
                                runTimeTicks = dto.runTimeTicks ?: ep.runtime?.let { it.inWholeMilliseconds * 10_000 },
                            )
                        }
                val seriesRow = runCatching { buildSeriesRow(rows) }.onFailure { Timber.w(it, "series row failed") }.getOrNull()
                val count = runCatching { downloadRepository.enqueue(rows, seriesRow) }.getOrElse { Timber.e(it, "enqueue failed"); 0 }
                // Poster/backdrop for the Downloads library: fetch now while we are online.
                if (count > 0) runCatching { downloadRepository.ensureAllArtwork() }
                _state.update { it.copy(enqueuedCount = count) }
                // Toasts must be shown on the main thread; the enqueue above ran on IO.
                withContext(Dispatchers.Main) { onDone(count) }
            }
        }
    }
