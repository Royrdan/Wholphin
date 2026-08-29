package com.github.damontecres.wholphin.ui.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared jf-resolve / debrid .strm resolution used by the INTERNAL ExoPlayer playback path.
 *
 * The external-player path ([PlayExternalPage]) has its OWN private copy of this logic and is
 * deliberately left untouched. This file exists so the built-in player can obtain the same
 * already-resolved direct CDN link without sharing (and risking) the external path's code.
 *
 * A jf-resolve source's media path is a resolver endpoint (".../api/stream/resolve/...") that
 * replies with a 302 whose Location header is the real, range-capable debrid CDN URL. Handing
 * ExoPlayer the resolver URL directly makes it choke on the cross-protocol redirect (and loop),
 * and handing it the Jellyfin proxy URL makes the server chase the redirect instead. So we do a
 * single no-redirect GET here and feed ExoPlayer the final direct URL.
 */

private const val STRM_RESOLVE_CONNECT_TIMEOUT_MS = 15_000
private const val STRM_RESOLVE_READ_TIMEOUT_MS = 45_000

/** True if [this] looks like a jf-resolve resolver endpoint. */
fun String?.isJfResolvePath(): Boolean = this?.contains("/api/stream/resolve/") == true

sealed interface StrmResolveResult {
    data class Success(val url: String) : StrmResolveResult

    data class Error(val code: Int, val reason: String) : StrmResolveResult
}

/**
 * Resolve a jf-resolve resolver URL to its final direct CDN link via a single no-redirect GET,
 * reading the 302 Location header. On a non-3xx, pulls the FastAPI {"detail": ...} body for a
 * human reason. Runs on IO.
 */
suspend fun resolveDebridDirectUrl(rawUrl: String): StrmResolveResult =
    withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn =
                (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = STRM_RESOLVE_CONNECT_TIMEOUT_MS
                    readTimeout = STRM_RESOLVE_READ_TIMEOUT_MS
                }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                if (location.isNullOrBlank()) {
                    StrmResolveResult.Error(code, "resolver returned no location")
                } else {
                    StrmResolveResult.Success(location)
                }
            } else {
                val body =
                    runCatching {
                        (conn.errorStream ?: conn.inputStream)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?.take(500)
                    }.getOrNull()
                StrmResolveResult.Error(code, extractResolveDetail(body) ?: "HTTP $code")
            }
        } catch (ex: Exception) {
            StrmResolveResult.Error(-1, ex.message ?: ex.javaClass.simpleName ?: "network error")
        } finally {
            conn?.disconnect()
        }
    }

/** Pull the {"detail": "..."} message out of a FastAPI JSON error body, if present. */
private fun extractResolveDetail(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return runCatching {
        org.json.JSONObject(body).optString("detail").takeIf { it.isNotBlank() }
    }.getOrNull()
}
