package com.github.damontecres.wholphin.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.damontecres.wholphin.data.model.WatchlistItem
import java.util.UUID

@Dao
interface WatchlistDao {
    /** Item ids on the user's watchlist, most-recently-added first. */
    @Query("SELECT itemId FROM WatchlistItem WHERE userId = :userId ORDER BY addedAt DESC")
    suspend fun getItemIds(userId: Int): List<UUID>

    @Query("SELECT EXISTS(SELECT 1 FROM WatchlistItem WHERE userId = :userId AND itemId = :itemId)")
    suspend fun exists(
        userId: Int,
        itemId: UUID,
    ): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: WatchlistItem): Long

    @Query("DELETE FROM WatchlistItem WHERE userId = :userId AND itemId = :itemId")
    suspend fun delete(
        userId: Int,
        itemId: UUID,
    )
}
