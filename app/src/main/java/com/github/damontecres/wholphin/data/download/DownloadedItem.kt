package com.github.damontecres.wholphin.data.download

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/** Lifecycle of one offline download. Stored by name. */
enum class DownloadState {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    PAUSED,
    DONE,
    FAILED,
    ;

    val isActive: Boolean get() = this == QUEUED || this == RESOLVING || this == DOWNLOADING
}

/**
 * One movie or episode downloaded (or being downloaded) to the phone for offline playback.
 *
 * [resolverUrl] is the jf-resolve resolve link with the `quality` query already rewritten to
 * [qualityTier]; the worker follows its 302 to the real CDN file. Playback position and watched
 * flag live here while offline and are pushed back to Jellyfin through [PendingPlaystate].
 */
@Entity
data class DownloadedItem(
    @PrimaryKey
    val itemId: UUID,
    val serverId: String? = null,
    val seriesId: UUID? = null,
    val seriesName: String? = null,
    val seasonId: UUID? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String,
    val imageUrl: String? = null,
    val resolverUrl: String,
    val qualityTier: String,
    val filePath: String? = null,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val state: DownloadState = DownloadState.QUEUED,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val lastPositionMs: Long = 0L,
    val playedToCompletion: Boolean = false,
    val runTimeTicks: Long? = null,
) {
    val isEpisode: Boolean get() = seriesId != null

    /** "S01E02" style tag for episodes, empty for movies. */
    val episodeTag: String
        get() =
            if (isEpisode) {
                "S%02dE%02d".format(seasonNumber ?: 0, episodeNumber ?: 0)
            } else {
                ""
            }

    val groupKey: String get() = seriesId?.toString() ?: "movies"

    val groupTitle: String get() = seriesName ?: "Movies"

    val progressFraction: Float
        get() = if (bytesTotal > 0L) (bytesDone.toDouble() / bytesTotal.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * Watched / position updates made while offline, flushed to Jellyfin by PlaystateSyncWorker
 * once a network is available.
 */
@Entity
data class PendingPlaystate(
    @PrimaryKey
    val itemId: UUID,
    val positionMs: Long,
    val played: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
    val serverId: String? = null,
    val userId: String? = null,
)
