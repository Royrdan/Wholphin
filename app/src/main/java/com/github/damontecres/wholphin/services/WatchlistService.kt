package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.WatchlistDao
import com.github.damontecres.wholphin.data.model.WatchlistItem
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's "watch later" list ("My List").
 *
 * Stored locally, per-user, as a set of item ids. A movie stays a movie and a series stays a series
 * - unlike a Jellyfin playlist, which explodes a series into every episode. The home row hides items
 * once they have been watched (see the Watchlist row in HomeSettingsService).
 */
@Singleton
class WatchlistService
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val watchlistDao: WatchlistDao,
    ) {
        /** Ids currently on the current user's watchlist, most-recently-added first. */
        suspend fun itemIds(): List<UUID> {
            val userRowId = serverRepository.currentUser?.rowId ?: return emptyList()
            return watchlistDao.getItemIds(userRowId)
        }

        suspend fun add(itemId: UUID) {
            val userRowId = serverRepository.currentUser?.rowId ?: return
            watchlistDao.insert(
                WatchlistItem(
                    userId = userRowId,
                    itemId = itemId,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }

        suspend fun remove(itemId: UUID) {
            val userRowId = serverRepository.currentUser?.rowId ?: return
            watchlistDao.delete(userRowId, itemId)
        }

        /** The watchlisted items resolved from the server (movies/series), newest first. */
        suspend fun items(
            userId: UUID,
            fields: List<ItemFields> = HomeItemFields,
        ): List<BaseItemDto> {
            val ids = itemIds()
            if (ids.isEmpty()) return emptyList()
            val items =
                GetItemsRequestHandler
                    .execute(
                        api,
                        GetItemsRequest(
                            ids = ids,
                            userId = userId,
                            fields = fields,
                            enableTotalRecordCount = false,
                        ),
                    ).content.items
                    .orEmpty()
            // GetItems does not honour the requested id order; restore watchlist order.
            val order = ids.withIndex().associate { (index, id) -> id to index }
            return items.sortedBy { order[it.id] ?: Int.MAX_VALUE }
        }
    }
