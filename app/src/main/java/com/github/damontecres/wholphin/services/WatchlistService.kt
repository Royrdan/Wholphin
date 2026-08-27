package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's "watch later" list, backed by a single Jellyfin server playlist named [WATCHLIST_NAME].
 *
 * Unlike Favorites, this is a managed list: items can be added/removed from the context menu, and the
 * home row hides items once they have been watched.
 */
@Singleton
class WatchlistService
    @Inject
    constructor(
        private val api: ApiClient,
        private val playlistCreator: PlaylistCreator,
    ) {
        /** Returns the id of the watchlist playlist, or null if it doesn't exist yet */
        suspend fun getWatchlistId(): UUID? =
            try {
                playlistCreator
                    .getServerPlaylists(WATCHLIST_NAME, null)
                    .firstOrNull { it.name.equals(WATCHLIST_NAME, ignoreCase = true) }
                    ?.id
            } catch (ex: Exception) {
                Timber.e(ex, "Error finding watchlist playlist")
                null
            }

        /** Add an item, creating the watchlist playlist on first use */
        suspend fun add(itemId: UUID) {
            val existing = getWatchlistId()
            if (existing == null) {
                playlistCreator.createServerPlaylist(WATCHLIST_NAME, listOf(itemId))
            } else {
                playlistCreator.addToServerPlaylist(existing, itemId)
            }
        }

        suspend fun remove(itemId: UUID) {
            getWatchlistId()?.let { playlistCreator.removeFromServerPlaylist(it, itemId) }
        }

        /** Items currently on the watchlist (in playlist order) */
        suspend fun items(
            userId: UUID,
            fields: List<ItemFields> = HomeItemFields,
        ): List<BaseItemDto> {
            val id = getWatchlistId() ?: return emptyList()
            return GetItemsRequestHandler
                .execute(
                    api,
                    GetItemsRequest(
                        parentId = id,
                        userId = userId,
                        fields = fields,
                        enableTotalRecordCount = false,
                    ),
                ).content.items
                .orEmpty()
        }

        suspend fun isInWatchlist(
            userId: UUID,
            itemId: UUID,
        ): Boolean = items(userId, emptyList()).any { it.id == itemId }

        companion object {
            const val WATCHLIST_NAME = "My List"
        }
    }
