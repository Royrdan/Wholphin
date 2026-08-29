package com.github.damontecres.wholphin.ui.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Client for jf-resolve's /api/segments endpoint (intro/credits detection).
 *
 * Jellyfin returns no media segments for debrid .strm content, so we source them
 * from jf-resolve instead — which detects intro/credits by chromaprint on the
 * resolved stream and exposes them keyed the same way as the resolver URL. We
 * simply rewrite the resolver URL (already on the media source path) to the
 * segments URL, poll until detection is "ready", and hand back real
 * [MediaSegmentDto]s so Wholphin's existing skip UI / auto-skip work unchanged.
 */

private const val SEG_TIMEOUT_MS = 15_000
private const val SEG_RETRIES = 8
private const val SEG_RETRY_DELAY_MS = 8_000L

/** Rewrite a jf-resolve resolver URL to its /api/segments equivalent, or null. */
fun String?.toSegmentsUrl(): String? =
    if (this != null && contains("/api/stream/resolve/")) {
        replace("/api/stream/resolve/", "/api/segments/")
    } else {
        null
    }

/**
 * Fetch jf-resolve intro/credits for [itemId], polling until detection reports
 * "ready" (bounded). Returns the segment list (possibly empty when nothing
 * recurring was found — a valid result), or null when this isn't a jf-resolve
 * source / it never became ready (caller falls back to Jellyfin).
 */
suspend fun fetchJfResolveSegments(resolverPath: String?, itemId: UUID): List<MediaSegmentDto>? {
    val url = resolverPath.toSegmentsUrl() ?: return null
    repeat(SEG_RETRIES) {
        val body = withContext(Dispatchers.IO) { httpGetBody(url) }
        if (body != null) {
            runCatching {
                val json = org.json.JSONObject(body)
                if (json.optString("status") == "ready") {
                    val segs = mutableListOf<MediaSegmentDto>()
                    json.optJSONObject("intro")?.let {
                        segs += seg(itemId, MediaSegmentType.INTRO, it.getDouble("start"), it.getDouble("end"))
                    }
                    json.optJSONObject("credits")?.let {
                        segs += seg(itemId, MediaSegmentType.OUTRO, it.getDouble("start"), it.getDouble("end"))
                    }
                    Timber.i("jf-resolve segments ready for %s: %d", itemId, segs.size)
                    return segs
                }
            }
        }
        delay(SEG_RETRY_DELAY_MS)
    }
    Timber.i("jf-resolve segments not ready for %s (gave up)", itemId)
    return null
}

/** Fire-and-forget: ask jf-resolve to (start) detecting segments for [resolverPath]. */
suspend fun triggerJfResolveSegments(resolverPath: String?) {
    val url = resolverPath.toSegmentsUrl() ?: return
    withContext(Dispatchers.IO) { httpGetBody(url) }
}

private fun seg(itemId: UUID, type: MediaSegmentType, startSec: Double, endSec: Double): MediaSegmentDto =
    MediaSegmentDto(
        id = UUID.randomUUID(),
        itemId = itemId,
        type = type,
        startTicks = (startSec * 1e7).toLong(),
        endTicks = (endSec * 1e7).toLong(),
    )

private fun httpGetBody(rawUrl: String): String? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = SEG_TIMEOUT_MS
            readTimeout = SEG_TIMEOUT_MS
        }
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            null
        }
    } catch (e: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}
