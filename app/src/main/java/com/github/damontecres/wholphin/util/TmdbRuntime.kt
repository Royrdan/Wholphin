package com.github.damontecres.wholphin.util

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.BaseItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Approximate runtime fallback for items whose Jellyfin RunTimeTicks is null (debrid .strm items
 * are never probed until played). Looks the item up on TMDB by its provider ids and caches the
 * answer in memory for the life of the process. Key comes from BuildConfig.TMDB_API_KEY, which is
 * injected from the build env (never committed).
 */
object TmdbRuntime {
    private const val TAG = "WholphinTMDB"
    private const val BASE = "https://api.themoviedb.org/3"

    // Value 0 = looked up, nothing found (don't retry this process)
    private val cache = ConcurrentHashMap<String, Int>()

    fun needsLookup(item: BaseItem?): Boolean =
        item != null &&
            BuildConfig.TMDB_API_KEY.isNotBlank() &&
            (item.data.runTimeTicks ?: 0L) <= 0L &&
            item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.SERIES)

    fun cached(item: BaseItem): Duration? = cache[item.id.toString()]?.takeIf { it > 0 }?.minutes

    suspend fun lookup(item: BaseItem): Duration? {
        val key = item.id.toString()
        cache[key]?.let { return it.takeIf { m -> m > 0 }?.minutes }
        val minutes =
            withContext(Dispatchers.IO) {
                try {
                    fetchMinutes(item)
                } catch (e: Exception) {
                    Log.w(TAG, "lookup failed for ${item.id}: ${e.message}")
                    null
                }
            }
        // Only cache definite answers; a network failure may be retried on next visit
        if (minutes != null) cache[key] = minutes
        return minutes?.takeIf { it > 0 }?.minutes
    }

    private fun fetchMinutes(item: BaseItem): Int {
        val ids = item.data.providerIds.orEmpty()
        fun id(name: String) = ids.entries.firstOrNull { it.key.equals(name, true) }?.value?.takeIf { it.isNotBlank() }
        return when (item.type) {
            BaseItemKind.MOVIE -> {
                val tmdb =
                    id("Tmdb")
                        ?: id("Imdb")?.let { find(it, "imdb_id").optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id") }?.toString()
                tmdb?.let { get("/movie/$it").optInt("runtime", 0) } ?: 0
            }

            BaseItemKind.EPISODE -> {
                val ep =
                    id("Tvdb")?.let { find(it, "tvdb_id").optJSONArray("tv_episode_results")?.optJSONObject(0) }
                        ?: id("Imdb")?.let { find(it, "imdb_id").optJSONArray("tv_episode_results")?.optJSONObject(0) }
                ep?.optInt("runtime", 0) ?: 0
            }

            BaseItemKind.SERIES -> {
                val tmdb =
                    id("Tmdb")
                        ?: id("Tvdb")?.let { find(it, "tvdb_id").optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id") }?.toString()
                if (tmdb == null) {
                    0
                } else {
                    val show = get("/tv/$tmdb")
                    val ert = show.optJSONArray("episode_run_time")
                    val fromList = if (ert != null && ert.length() > 0) ert.optInt(0, 0) else 0
                    fromList.takeIf { it > 0 }
                        ?: show.optJSONObject("last_episode_to_air")?.optInt("runtime", 0)
                        ?: 0
                }
            }

            else -> 0
        }
    }

    private fun find(
        externalId: String,
        source: String,
    ) = get("/find/$externalId", "external_source=$source")

    private fun get(
        path: String,
        extra: String? = null,
    ): JSONObject {
        val url = URL("$BASE$path?api_key=${BuildConfig.TMDB_API_KEY}" + (extra?.let { "&$it" } ?: ""))
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode} $path")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}

/** Approximate TMDB runtime for [item] when Jellyfin has none; null while loading or unknown. */
@Composable
fun rememberApproxRuntime(item: BaseItem?): Duration? {
    if (!TmdbRuntime.needsLookup(item)) return null
    var result by remember(item!!.id) { mutableStateOf(TmdbRuntime.cached(item)) }
    LaunchedEffect(item.id) {
        if (result == null) result = TmdbRuntime.lookup(item)
    }
    return result
}
