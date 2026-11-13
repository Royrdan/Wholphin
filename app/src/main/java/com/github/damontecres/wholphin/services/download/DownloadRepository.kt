package com.github.damontecres.wholphin.services.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.github.damontecres.wholphin.data.download.DownloadDao
import com.github.damontecres.wholphin.data.download.DownloadState
import com.github.damontecres.wholphin.data.download.DownloadedItem
import com.github.damontecres.wholphin.data.download.DownloadedSeries
import com.github.damontecres.wholphin.data.download.PendingPlaystate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for everything offline-download related: the Room rows, the files on disk,
 * local artwork, and scheduling the background workers. UI and playback talk to this, never to
 * the DAO.
 */
@Singleton
class DownloadRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dao: DownloadDao,
        private val workManager: WorkManager,
        private val artwork: DownloadArtwork,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val downloads: Flow<List<DownloadedItem>> = dao.observeAll()

        val series: Flow<List<DownloadedSeries>> = dao.observeSeries()

        fun observe(itemId: UUID): Flow<DownloadedItem?> = dao.observe(itemId)

        fun observeSeries(seriesId: UUID): Flow<DownloadedSeries?> = dao.observeSeriesRow(seriesId)

        fun observeEpisodes(seriesId: UUID): Flow<List<DownloadedItem>> = dao.observeBySeries(seriesId)

        suspend fun get(itemId: UUID): DownloadedItem? = dao.get(itemId)

        suspend fun getSeries(seriesId: UUID): DownloadedSeries? = dao.getSeries(seriesId)

        suspend fun getByIds(itemIds: List<UUID>): List<DownloadedItem> = if (itemIds.isEmpty()) emptyList() else dao.getByIds(itemIds)

        suspend fun hasDownloads(): Boolean = dao.countDone() > 0

        /** Root folder for downloaded media (app-private external storage, removed on uninstall). */
        fun downloadsDir(): File {
            val base = context.getExternalFilesDir("downloads") ?: File(context.filesDir, "downloads")
            if (!base.exists()) base.mkdirs()
            return base
        }

        fun isOnline(): Boolean = NetworkStatus.isOnline(context)

        private fun DownloadedItem.fileOk(): Boolean = filePath?.let { File(it).let { f -> f.exists() && f.length() > 0L } } == true

        /** Finished download whose file is still on disk, or null. */
        suspend fun getPlayable(itemId: UUID): DownloadedItem? =
            withContext(Dispatchers.IO) {
                dao.get(itemId)?.takeIf { row -> row.state == DownloadState.DONE && row.fileOk() }
            }

        /**
         * Finished, playable episodes of one show in (season, episode) order. Used for offline
         * next-up: gaps in the downloaded run are simply skipped.
         */
        suspend fun playableEpisodesOf(seriesId: UUID): List<DownloadedItem> =
            withContext(Dispatchers.IO) {
                dao
                    .getBySeries(seriesId)
                    .filter { it.state == DownloadState.DONE && it.fileOk() }
                    .sortedWith(compareBy({ it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }, { it.createdAt }))
            }

        /** Add rows (existing item ids are skipped), remember the show, and start the worker. */
        suspend fun enqueue(
            items: List<DownloadedItem>,
            series: DownloadedSeries? = null,
        ): Int {
            if (items.isEmpty()) return 0
            if (series != null) {
                val inserted = dao.insertSeries(series)
                if (inserted == -1L) {
                    // Refresh text / remote URLs but keep saved art paths.
                    dao.getSeries(series.seriesId)?.let { existing ->
                        dao.updateSeries(
                            existing.copy(
                                name = series.name,
                                year = series.year ?: existing.year,
                                overview = series.overview ?: existing.overview,
                                posterUrl = series.posterUrl ?: existing.posterUrl,
                                backdropUrl = series.backdropUrl ?: existing.backdropUrl,
                            ),
                        )
                    }
                }
            }
            val inserted = dao.insertAll(items).count { it != -1L }
            Timber.i("enqueue: %d new of %d requested", inserted, items.size)
            scheduleWorker()
            return inserted
        }

        fun scheduleWorker() {
            val request =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresStorageNotLow(true)
                            .build(),
                    ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag(DownloadWorker.TAG)
                    .build()
            workManager.enqueueUniqueWork(DownloadWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Run the two-way watched-state sync as soon as any network is available. Safe to call
         * often (app start, foreground, session restored, after offline playback): it is a unique
         * work request, so repeated calls just replace the pending one.
         */
        fun scheduleSync(reason: String) {
            Timber.i("PlaystateSync: scheduling (%s)", reason)
            PlaystateSyncWorker.schedule(workManager)
        }

        /** Make sure artwork for a finished row and its show is saved locally (needs network). */
        suspend fun ensureArtwork(row: DownloadedItem) {
            runCatching { artwork.ensureItemImage(row, downloadsDir()) }
                .onFailure { Timber.w(it, "item artwork failed for %s", row.itemId) }
            row.seriesId?.let { seriesId ->
                dao.getSeries(seriesId)?.let { s ->
                    runCatching { artwork.ensureSeriesArt(s, downloadsDir()) }
                        .onFailure { Timber.w(it, "series artwork failed for %s", seriesId) }
                }
            }
        }

        /** Fill in any artwork still missing (e.g. enqueue happened before the show row existed). */
        suspend fun ensureAllArtwork() {
            if (!isOnline()) return
            dao.getDone().filter { it.localImagePath == null && it.imageUrl != null }.forEach { row ->
                runCatching { artwork.ensureItemImage(row, downloadsDir()) }
            }
            dao.getAllSeries().filter { it.posterPath == null || it.backdropPath == null }.forEach { s ->
                runCatching { artwork.ensureSeriesArt(s, downloadsDir()) }
            }
        }

        suspend fun pause(itemId: UUID) {
            dao.setState(itemId, DownloadState.PAUSED)
        }

        suspend fun pauseAll() {
            dao.pauseAllActive()
        }

        suspend fun resume(itemId: UUID) {
            dao.setState(itemId, DownloadState.QUEUED, null)
            scheduleWorker()
        }

        suspend fun resumeAll() {
            dao.getAll().filter { it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED }.forEach {
                dao.setState(it.itemId, DownloadState.QUEUED, null)
            }
            scheduleWorker()
        }

        suspend fun retry(itemId: UUID) = resume(itemId)

        /** Cancel is delete: the partial file and the row both go. */
        suspend fun cancel(itemId: UUID) = delete(itemId)

        /**
         * Delete one download: file, artwork, row. The show row goes too once it has no items left
         * (so deleting the last episode removes the show from the grid). Pending watched-state
         * updates are kept so they still reach the server.
         */
        suspend fun delete(itemId: UUID) =
            withContext(Dispatchers.IO) {
                val row = dao.get(itemId)
                row?.let { deleteFileFor(it) }
                artwork.deleteItemArt(itemId, downloadsDir())
                dao.delete(itemId)
                row?.seriesId?.let { seriesId ->
                    if (dao.getBySeries(seriesId).isEmpty()) removeSeriesRow(seriesId)
                }
            }

        suspend fun deleteSeries(seriesId: UUID) =
            withContext(Dispatchers.IO) {
                dao.getBySeries(seriesId).forEach { delete(it.itemId) }
                removeSeriesRow(seriesId)
            }

        suspend fun deleteGroup(groupKey: String) =
            withContext(Dispatchers.IO) {
                dao.getAll().filter { it.groupKey == groupKey }.forEach { delete(it.itemId) }
            }

        suspend fun deleteAll() =
            withContext(Dispatchers.IO) {
                dao.getAll().forEach { delete(it.itemId) }
                dao.getAllSeries().forEach { removeSeriesRow(it.seriesId) }
                workManager.cancelUniqueWork(DownloadWorker.WORK_NAME)
            }

        private suspend fun removeSeriesRow(seriesId: UUID) {
            artwork.deleteSeriesArt(seriesId, downloadsDir())
            dao.deleteSeries(seriesId)
        }

        private fun deleteFileFor(row: DownloadedItem) {
            row.filePath?.let { path ->
                val f = File(path)
                if (f.exists() && !f.delete()) Timber.w("Could not delete %s", path)
                // Drop the series folder once empty
                f.parentFile?.takeIf { it != downloadsDir() && it.list()?.isEmpty() == true }?.delete()
            }
        }

        // ---- playback position / watched state while offline ----

        suspend fun savePosition(
            itemId: UUID,
            positionMs: Long,
            played: Boolean,
        ) {
            dao.updatePosition(itemId, positionMs.coerceAtLeast(0L), played)
        }

        /**
         * Record a position / watched update to push to Jellyfin later, and schedule the sync
         * worker. Fire-and-forget safe: runs on the repository scope so a dying ViewModel cannot
         * cancel it. Called on every pause, stop, item change and completion of offline playback.
         */
        fun queuePlaystateAsync(
            itemId: UUID,
            positionMs: Long,
            played: Boolean,
            serverId: String?,
            userId: String?,
        ) {
            scope.launch {
                runCatching {
                    queuePlaystate(itemId, positionMs, played, unplayed = false, serverId, userId)
                }.onFailure { Timber.e(it, "queuePlaystate failed for %s", itemId) }
            }
        }

        private suspend fun queuePlaystate(
            itemId: UUID,
            positionMs: Long,
            played: Boolean,
            unplayed: Boolean,
            serverId: String?,
            userId: String?,
        ) {
            val pos = positionMs.coerceAtLeast(0L)
            dao.updatePosition(itemId, pos, played)
            dao.upsertPlaystate(
                PendingPlaystate(
                    itemId = itemId,
                    positionMs = pos,
                    played = played,
                    unplayed = unplayed,
                    serverId = serverId,
                    userId = userId,
                ),
            )
            Timber.i("PlaystateSync: queued %s pos=%dms played=%s unplayed=%s", itemId, pos, played, unplayed)
            scheduleSync("playstate queued")
        }

        /** Mark watched / not watched from the Downloads library (local row + pending server update). */
        suspend fun setWatched(
            itemId: UUID,
            watched: Boolean,
            serverId: String?,
            userId: String?,
        ) {
            queuePlaystate(itemId, 0L, played = watched, unplayed = !watched, serverId, userId)
        }
    }
