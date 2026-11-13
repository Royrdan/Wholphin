package com.github.damontecres.wholphin.data.download

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
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
 * flag live here while offline and are pushed back to Jellyfin through [PendingPlaystate]; the
 * sync worker also pulls the server's user data back into [lastPositionMs] / [playedToCompletion]
 * so the Downloads library shows what was watched on the TV.
 *
 * [imageUrl] is the server URL of the episode/movie primary image; [localImagePath] is the copy
 * saved to app storage once the download finishes so the card has artwork with no network.
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
    // v38
    val localImagePath: String? = null,
    /** When the watched state / position was last changed (local play or server down-sync), epoch ms. */
    val lastPlayedAt: Long? = null,
    val overview: String? = null,
    val premiereYear: Int? = null,
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

    /** 0..100 watched percentage from the local position, or 0 when unknown / finished. */
    val playedPercent: Double
        get() {
            val ticks = runTimeTicks ?: return 0.0
            if (ticks <= 0L || playedToCompletion || lastPositionMs <= 0L) return 0.0
            return (lastPositionMs * 10_000.0 / ticks * 100.0).coerceIn(0.0, 100.0)
        }

    /** Image to show for this item: the local copy when it exists, else the server URL. */
    val displayImage: String?
        get() = localImagePath?.let { "file://$it" } ?: imageUrl

    val downloadedDone: Boolean get() = state == DownloadState.DONE
}

/**
 * Minimal Jellyfin item built from a download row so the normal cards / player can show and play a
 * download when the server cannot be reached. User data mirrors the local watched state.
 */
fun DownloadedItem.toBaseItemDto(): BaseItemDto =
    BaseItemDto(
        id = itemId,
        type = if (isEpisode) BaseItemKind.EPISODE else BaseItemKind.MOVIE,
        name = title,
        seriesId = seriesId,
        seriesName = seriesName,
        seasonId = seasonId,
        parentIndexNumber = seasonNumber,
        indexNumber = episodeNumber,
        runTimeTicks = runTimeTicks,
        overview = overview,
        productionYear = premiereYear,
        userData =
            UserItemDataDto(
                rating = null,
                playedPercentage = playedPercent.takeIf { it > 0.0 },
                unplayedItemCount = null,
                playbackPositionTicks = if (playedToCompletion) 0L else lastPositionMs * 10_000L,
                playCount = if (playedToCompletion) 1 else 0,
                isFavorite = false,
                likes = null,
                lastPlayedDate = lastPlayedAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC) },
                played = playedToCompletion,
                key = itemId.toString(),
                itemId = itemId,
            ),
    )

/**
 * A show with at least one download. Carries the artwork + text needed to draw the Downloads
 * library with no server: poster/backdrop are saved to app storage by DownloadArtwork.
 */
@Entity
data class DownloadedSeries(
    @PrimaryKey
    val seriesId: UUID,
    val serverId: String? = null,
    val name: String,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val displayPoster: String? get() = posterPath?.let { "file://$it" } ?: posterUrl

    val displayBackdrop: String? get() = backdropPath?.let { "file://$it" } ?: backdropUrl
}

/**
 * Watched / position updates made while offline, flushed to Jellyfin by PlaystateSyncWorker
 * once a network is available.
 *
 * [unplayed] is an explicit "mark as not watched" request (clears the played flag on the server);
 * otherwise [played] + [positionMs] describe a stop/pause/completion.
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
    @ColumnInfo(defaultValue = "0")
    val unplayed: Boolean = false,
)
