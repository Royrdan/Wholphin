package com.github.damontecres.wholphin.services.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.download.DownloadDao
import com.github.damontecres.wholphin.data.download.DownloadState
import com.github.damontecres.wholphin.data.download.PendingPlaystate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Two-way watched-state sync between the phone's downloads and Jellyfin. Runs whenever any
 * network is available and is scheduled on: a playstate row being written (pause/stop/finish of
 * offline playback, mark watched), app start / foreground, and the session being restored.
 *
 * 1. Up-sync: every [PendingPlaystate] row is pushed — `markPlayedItem` for completed items,
 *    `reportPlaybackStopped` with the position otherwise, `markUnplayedItem` for an explicit
 *    "not watched". A row is skipped (and dropped) when the server already has a NEWER change for
 *    that item (its `UserData.LastPlayedDate` is after the row's timestamp), so a stale position
 *    never overwrites what was watched on the TV. Rows are only deleted after the server call
 *    succeeded; anything else makes the worker retry with exponential backoff.
 * 2. Down-sync: one batched `getItems(ids=…)` for every downloaded item refreshes the local
 *    position / watched flag from the server (skipping items that still have a pending up-sync),
 *    so the Downloads library and the picker's "next unwatched" match the server.
 * 3. Missing local artwork is fetched while we are online anyway.
 *
 * Everything logs at INFO with the "PlaystateSync" prefix so it can be followed in logcat.
 */
@HiltWorker
class PlaystateSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val dao: DownloadDao,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val repository: DownloadRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val pending = dao.pendingPlaystates()
            val downloaded = dao.getAll()
            Timber.i("PlaystateSync: start attempt=%d pending=%d downloaded=%d", runAttemptCount, pending.size, downloaded.size)
            if (pending.isEmpty() && downloaded.isEmpty()) return Result.success()

            if (!ensureSession(pending, downloaded)) {
                Timber.w("PlaystateSync: no server session; retrying later")
                return Result.retry()
            }
            val userId = serverRepository.currentUser?.id

            // ---- 1. up-sync ----
            var failures = 0
            for (p in pending) {
                try {
                    pushOne(p, userId)
                    dao.deletePlaystate(p.itemId, p.updatedAt)
                } catch (ex: Exception) {
                    failures++
                    Timber.w(ex, "PlaystateSync: push failed for %s (%s)", p.itemId, ex.message)
                }
            }

            // ---- 2. down-sync ----
            val stillPending = dao.pendingPlaystates().map { it.itemId }.toSet()
            val ids = downloaded.map { it.itemId }
            if (ids.isNotEmpty()) {
                try {
                    pullUserData(ids, stillPending, userId)
                } catch (ex: Exception) {
                    failures++
                    Timber.w(ex, "PlaystateSync: down-sync failed (%s)", ex.message)
                }
            }

            // ---- 3. artwork ----
            runCatching { repository.ensureAllArtwork() }.onFailure { Timber.w(it, "PlaystateSync: artwork pass failed") }

            Timber.i("PlaystateSync: done failures=%d", failures)
            return if (failures > 0) Result.retry() else Result.success()
        }

        private suspend fun ensureSession(
            pending: List<PendingPlaystate>,
            downloaded: List<com.github.damontecres.wholphin.data.download.DownloadedItem>,
        ): Boolean {
            if (!api.baseUrl.isNullOrBlank() && !api.accessToken.isNullOrBlank() && serverRepository.currentUser != null) return true
            val serverId = pending.firstOrNull()?.serverId ?: downloaded.firstOrNull()?.serverId
            val userId = pending.firstOrNull()?.userId
            val restored =
                runCatching {
                    serverRepository.restoreSession(serverId?.toUUIDOrNull(), userId?.toUUIDOrNull())
                }.onFailure { Timber.w(it, "PlaystateSync: restoreSession failed") }.getOrNull()
            return restored != null && !api.accessToken.isNullOrBlank()
        }

        private suspend fun pushOne(
            p: PendingPlaystate,
            userId: UUID?,
        ) {
            // Stale check: if the server has a newer change for this item, keep the server's.
            val server = runCatching { api.userLibraryApi.getItem(p.itemId, userId).content.userData }.getOrNull()
            val serverChangedAt = server?.lastPlayedDate?.toEpochMs()
            if (server != null && serverChangedAt != null && serverChangedAt > p.updatedAt + CLOCK_SKEW_MS) {
                Timber.i(
                    "PlaystateSync: SKIP %s — server change at %d is newer than local %d (server played=%s pos=%dms)",
                    p.itemId,
                    serverChangedAt,
                    p.updatedAt,
                    server.played,
                    server.playbackPositionTicks / 10_000,
                )
                applyServerUserData(p.itemId, server)
                return
            }
            when {
                p.unplayed -> {
                    api.playStateApi.markUnplayedItem(p.itemId, userId)
                    Timber.i("PlaystateSync: marked UNPLAYED %s", p.itemId)
                }

                p.played -> {
                    val datePlayed = LocalDateTime.ofInstant(Instant.ofEpochMilli(p.updatedAt), ZoneOffset.UTC)
                    api.playStateApi.markPlayedItem(p.itemId, userId, datePlayed)
                    Timber.i("PlaystateSync: marked PLAYED %s (at %s)", p.itemId, datePlayed)
                }

                else -> {
                    val ticks = p.positionMs.milliseconds.inWholeNanoseconds / 100
                    api.playStateApi.reportPlaybackStopped(
                        PlaybackStopInfo(
                            itemId = p.itemId,
                            positionTicks = ticks,
                            failed = false,
                        ),
                    )
                    Timber.i("PlaystateSync: reported STOPPED %s pos=%dms", p.itemId, p.positionMs)
                }
            }
        }

        private suspend fun pullUserData(
            ids: List<UUID>,
            skip: Set<UUID>,
            userId: UUID?,
        ) {
            var updated = 0
            ids.chunked(100).forEach { chunk ->
                val items =
                    api.itemsApi
                        .getItems(
                            userId = userId,
                            ids = chunk,
                            fields = listOf(ItemFields.OVERVIEW),
                            enableImages = false,
                        ).content.items
                for (dto in items) {
                    if (dto.id in skip) continue
                    val ud = dto.userData ?: continue
                    if (applyServerUserData(dto.id, ud)) updated++
                }
            }
            Timber.i("PlaystateSync: down-sync refreshed %d of %d items (%d skipped: pending)", updated, ids.size, skip.size)
        }

        /** Copy the server's played flag / position onto the local row. Returns true if anything changed. */
        private suspend fun applyServerUserData(
            itemId: UUID,
            ud: UserItemDataDto,
        ): Boolean {
            val row = dao.get(itemId) ?: return false
            val played = ud.played
            val positionMs = if (played) 0L else ud.playbackPositionTicks / 10_000
            val serverAt = ud.lastPlayedDate?.toEpochMs()
            if (row.playedToCompletion == played && row.lastPositionMs == positionMs) return false
            // Only pull when the server is at least as new as what we have locally.
            val localAt = row.lastPlayedAt ?: 0L
            if (serverAt != null && serverAt + CLOCK_SKEW_MS < localAt && row.state == DownloadState.DONE) {
                Timber.i("PlaystateSync: keep local for %s (local %d newer than server %d)", itemId, localAt, serverAt)
                return false
            }
            dao.updatePosition(itemId, positionMs, played, serverAt ?: System.currentTimeMillis())
            Timber.i("PlaystateSync: pulled %s played=%s pos=%dms", itemId, played, positionMs)
            return true
        }

        private fun LocalDateTime.toEpochMs(): Long = toInstant(ZoneOffset.UTC).toEpochMilli()

        companion object {
            const val WORK_NAME = "wholphin-playstate-sync"
            private const val CLOCK_SKEW_MS = 30_000L

            fun schedule(workManager: WorkManager) {
                val request =
                    OneTimeWorkRequestBuilder<PlaystateSyncWorker>()
                        .setConstraints(
                            Constraints
                                .Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                        .build()
                workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
