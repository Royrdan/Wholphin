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
import com.github.damontecres.wholphin.data.download.PendingPlaystate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * and scheduling the background worker. UI and playback talk to this, never to the DAO.
 */
@Singleton
class DownloadRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dao: DownloadDao,
        private val workManager: WorkManager,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val downloads: Flow<List<DownloadedItem>> = dao.observeAll()

        fun observe(itemId: UUID): Flow<DownloadedItem?> = dao.observe(itemId)

        suspend fun get(itemId: UUID): DownloadedItem? = dao.get(itemId)

        suspend fun getByIds(itemIds: List<UUID>): List<DownloadedItem> = if (itemIds.isEmpty()) emptyList() else dao.getByIds(itemIds)

        /** Root folder for downloaded media (app-private external storage, removed on uninstall). */
        fun downloadsDir(): File {
            val base = context.getExternalFilesDir("downloads") ?: File(context.filesDir, "downloads")
            if (!base.exists()) base.mkdirs()
            return base
        }

        /** Finished download whose file is still on disk, or null. */
        suspend fun getPlayable(itemId: UUID): DownloadedItem? =
            withContext(Dispatchers.IO) {
                dao.get(itemId)?.takeIf { row ->
                    row.state == DownloadState.DONE &&
                        row.filePath?.let { File(it).let { f -> f.exists() && f.length() > 0L } } == true
                }
            }

        /** Add rows (existing item ids are skipped) and make sure the worker is running. */
        suspend fun enqueue(items: List<DownloadedItem>): Int {
            if (items.isEmpty()) return 0
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

        suspend fun delete(itemId: UUID) =
            withContext(Dispatchers.IO) {
                dao.get(itemId)?.let { deleteFileFor(it) }
                dao.delete(itemId)
            }

        suspend fun deleteSeries(seriesId: UUID) =
            withContext(Dispatchers.IO) {
                dao.getBySeries(seriesId).forEach { delete(it.itemId) }
            }

        suspend fun deleteGroup(groupKey: String) =
            withContext(Dispatchers.IO) {
                dao.getAll().filter { it.groupKey == groupKey }.forEach { delete(it.itemId) }
            }

        suspend fun deleteAll() =
            withContext(Dispatchers.IO) {
                dao.getAll().forEach { delete(it.itemId) }
                workManager.cancelUniqueWork(DownloadWorker.WORK_NAME)
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
         * cancel it.
         */
        fun queuePlaystateAsync(
            itemId: UUID,
            positionMs: Long,
            played: Boolean,
            serverId: String?,
            userId: String?,
        ) {
            scope.launch(NonCancellable) {
                runCatching {
                    dao.updatePosition(itemId, positionMs.coerceAtLeast(0L), played)
                    dao.upsertPlaystate(
                        PendingPlaystate(
                            itemId = itemId,
                            positionMs = positionMs.coerceAtLeast(0L),
                            played = played,
                            serverId = serverId,
                            userId = userId,
                        ),
                    )
                    PlaystateSyncWorker.schedule(workManager)
                }.onFailure { Timber.e(it, "queuePlaystate failed for %s", itemId) }
            }
        }
    }
