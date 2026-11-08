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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pushes positions / watched flags recorded during offline playback back to Jellyfin once any
 * network is available, so the TV picks up where the phone left off.
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
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val pending = dao.pendingPlaystates()
            if (pending.isEmpty()) return Result.success()
            Timber.i("PlaystateSync: %d pending", pending.size)

            if (api.baseUrl.isNullOrBlank() || api.accessToken.isNullOrBlank()) {
                val first = pending.first()
                val restored =
                    runCatching {
                        serverRepository.restoreSession(first.serverId?.toUUIDOrNull(), first.userId?.toUUIDOrNull())
                    }.getOrNull()
                if (restored == null || api.accessToken.isNullOrBlank()) {
                    Timber.w("PlaystateSync: no session; retrying later")
                    return Result.retry()
                }
            }

            var anyFailure = false
            for (p in pending) {
                try {
                    val ticks = p.positionMs.milliseconds.inWholeNanoseconds / 100
                    api.playStateApi.reportPlaybackStopped(
                        PlaybackStopInfo(
                            itemId = p.itemId,
                            positionTicks = if (p.played) null else ticks,
                            failed = false,
                        ),
                    )
                    if (p.played) {
                        api.playStateApi.markPlayedItem(p.itemId)
                    }
                    dao.deletePlaystate(p.itemId, p.updatedAt)
                    Timber.i("PlaystateSync: synced %s pos=%dms played=%s", p.itemId, p.positionMs, p.played)
                } catch (ex: Exception) {
                    anyFailure = true
                    Timber.w(ex, "PlaystateSync: failed for %s", p.itemId)
                }
            }
            return if (anyFailure) Result.retry() else Result.success()
        }

        companion object {
            const val WORK_NAME = "wholphin-playstate-sync"

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
