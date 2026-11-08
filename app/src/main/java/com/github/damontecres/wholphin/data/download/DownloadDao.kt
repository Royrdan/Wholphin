package com.github.damontecres.wholphin.data.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface DownloadDao {
    @Query("SELECT * FROM DownloadedItem ORDER BY seriesName, seasonNumber, episodeNumber, createdAt")
    fun observeAll(): Flow<List<DownloadedItem>>

    @Query("SELECT * FROM DownloadedItem ORDER BY seriesName, seasonNumber, episodeNumber, createdAt")
    suspend fun getAll(): List<DownloadedItem>

    @Query("SELECT * FROM DownloadedItem WHERE itemId = :itemId")
    suspend fun get(itemId: UUID): DownloadedItem?

    @Query("SELECT * FROM DownloadedItem WHERE itemId = :itemId")
    fun observe(itemId: UUID): Flow<DownloadedItem?>

    @Query("SELECT * FROM DownloadedItem WHERE itemId IN (:itemIds)")
    suspend fun getByIds(itemIds: List<UUID>): List<DownloadedItem>

    @Query("SELECT * FROM DownloadedItem WHERE seriesId = :seriesId")
    suspend fun getBySeries(seriesId: UUID): List<DownloadedItem>

    /** Oldest row still waiting for the worker. */
    @Query(
        "SELECT * FROM DownloadedItem WHERE state IN ('QUEUED','RESOLVING','DOWNLOADING') ORDER BY createdAt LIMIT 1",
    )
    suspend fun nextActive(): DownloadedItem?

    @Query("SELECT COUNT(*) FROM DownloadedItem WHERE state IN ('QUEUED','RESOLVING','DOWNLOADING')")
    suspend fun countActive(): Int

    /** Rows left mid-flight by a crash go back to the queue. */
    @Query("UPDATE DownloadedItem SET state = 'QUEUED' WHERE state IN ('RESOLVING','DOWNLOADING')")
    suspend fun requeueInFlight()

    @Query("UPDATE DownloadedItem SET state = 'PAUSED' WHERE state IN ('QUEUED','RESOLVING','DOWNLOADING')")
    suspend fun pauseAllActive()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<DownloadedItem>): List<Long>

    @Update
    suspend fun update(item: DownloadedItem)

    @Query("UPDATE DownloadedItem SET bytesDone = :bytesDone, bytesTotal = :bytesTotal WHERE itemId = :itemId")
    suspend fun updateProgress(
        itemId: UUID,
        bytesDone: Long,
        bytesTotal: Long,
    )

    @Query("UPDATE DownloadedItem SET state = :state, error = :error WHERE itemId = :itemId")
    suspend fun setState(
        itemId: UUID,
        state: DownloadState,
        error: String? = null,
    )

    @Query("UPDATE DownloadedItem SET lastPositionMs = :positionMs, playedToCompletion = :played WHERE itemId = :itemId")
    suspend fun updatePosition(
        itemId: UUID,
        positionMs: Long,
        played: Boolean,
    )

    @Query("DELETE FROM DownloadedItem WHERE itemId = :itemId")
    suspend fun delete(itemId: UUID)

    // ---- pending playstate ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaystate(playstate: PendingPlaystate)

    @Query("SELECT * FROM PendingPlaystate ORDER BY updatedAt")
    suspend fun pendingPlaystates(): List<PendingPlaystate>

    @Query("DELETE FROM PendingPlaystate WHERE itemId = :itemId AND updatedAt <= :updatedAt")
    suspend fun deletePlaystate(
        itemId: UUID,
        updatedAt: Long,
    )
}
