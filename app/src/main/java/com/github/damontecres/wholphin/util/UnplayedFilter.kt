package com.github.damontecres.wholphin.util

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest

/**
 * Keeps "unwatched only" off the server for item types where it is ruinously expensive.
 *
 * Jellyfin stores a watched flag per *playable* item. A movie has one, so `isPlayed=false` on a
 * movie query is a single indexed lookup. A **series does not** - the server derives one by walking
 * every episode of every matching series, every time. Measured against this library (386 series,
 * Jellyfin on a 2-core container):
 *
 * | query                                            | with isPlayed=false | without |
 * |--------------------------------------------------|---------------------|---------|
 * | Series, newest in one genre                       | 6.5-8s              | 0.2-0.8s |
 * | Series, random, no genre                          | 19.3s               | 1.2s    |
 * | Series, random, five genres                       | 9.1s                | 2.0s    |
 * | Movie, random, five genres                        | 0.13s               | 0.13s   |
 *
 * Four of those in flight at once measured 38s each and starved unrelated requests (an unrelated
 * Favourites query went 0.26s -> 1.6s), which is what made whole pages crawl.
 *
 * The watched flag comes back on every item for free, so the cheap route is to ask the server for
 * more rows than are needed, drop the watched ones here, and cut back to the requested size.
 */
object UnplayedFilter {
    /** How many extra items to pull, since some of them get dropped here */
    private const val OVER_FETCH = 3

    /** Ceiling on that over-fetch, so a large row can't turn into a huge response */
    private const val MAX_FETCH = 100

    /**
     * Whether [request] asks the server to derive a watched flag it does not store.
     *
     * Movie-only queries are left alone - the server answers those from a stored value.
     */
    fun appliesTo(request: GetItemsRequest): Boolean =
        request.isPlayed == false &&
            request.includeItemTypes.orEmpty().any { it != BaseItemKind.MOVIE }

    /** [request] with the expensive filter dropped and room to spare for filtering here */
    fun rewrite(request: GetItemsRequest): GetItemsRequest =
        request.copy(
            isPlayed = null,
            limit = request.limit?.let { (it * OVER_FETCH).coerceAtMost(MAX_FETCH) },
        )

    /** Drop the watched items the server would have dropped, then cut back to [limit] */
    fun apply(
        items: List<BaseItemDto>,
        limit: Int?,
    ): List<BaseItemDto> =
        items
            .filter { it.userData?.played != true }
            .let { if (limit != null) it.take(limit) else it }
}
