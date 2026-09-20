package com.github.damontecres.wholphin.ui.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads jf-resolve's live view of an in-flight resolve, so the loading page can say what is
 * actually happening instead of counting seconds and guessing.
 *
 * Kept deliberately small and forgiving. It runs on the play path, where an unexpected response,
 * an older server, or a switched-off feature must degrade to the timer-driven text rather than
 * hold up (or break) playback — so every failure mode here returns null.
 *
 * Wording is NOT built here. The server ships a display-ready line and this file shows it
 * verbatim: changing a phrase server-side is a container rebuild, changing it here is a signed
 * APK and an adb install.
 */

private const val PROGRESS_CONNECT_TIMEOUT_MS = 2_000
private const val PROGRESS_READ_TIMEOUT_MS = 3_000

/** A phase snapshot from `/api/stream/progress/...`. */
data class ResolveProgress(
    val state: String,
    val phase: String,
    val message: String,
    /** How long this resolve has been running, per the server. */
    val elapsedMs: Long,
    /** Age of the current phase — a number that keeps climbing is a phase that is dragging. */
    val updatedMsAgo: Long,
) {
    val isFailed: Boolean get() = state == "failed"

    /** The server knows this endpoint but has nothing to show (idle, or switched off). */
    val isQuiet: Boolean get() = state == "idle" || state == "disabled"

    val isDisabled: Boolean get() = state == "disabled"

    val hasMessage: Boolean get() = message.isNotBlank() && !isQuiet
}

/**
 * The progress endpoint twin of a jf-resolve resolver URL, or null if [resolverPath] isn't one.
 *
 * A plain string swap, on purpose: the two routes take identical path segments and query params,
 * so rebuilding the parameters here would just add a way for app and server to disagree about
 * which resolve is being watched.
 */
fun progressUrlFor(resolverPath: String?): String? =
    if (resolverPath != null && resolverPath.contains("/api/stream/resolve/")) {
        resolverPath.replace("/api/stream/resolve/", "/api/stream/progress/")
    } else {
        null
    }

/** One poll. Null means "learned nothing" — caller should keep its own fallback text. */
suspend fun fetchResolveProgress(progressUrl: String): ResolveProgress? =
    withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn =
                (URL(progressUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = PROGRESS_CONNECT_TIMEOUT_MS
                    readTimeout = PROGRESS_READ_TIMEOUT_MS
                }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = conn.inputStream?.bufferedReader()?.use { it.readText() } ?: return@withContext null
            val json = JSONObject(body)
            ResolveProgress(
                state = json.optString("state", "idle"),
                phase = json.optString("phase", ""),
                message = json.optString("message", ""),
                elapsedMs = json.optLong("elapsed_ms", 0L),
                updatedMsAgo = json.optLong("updated_ms_ago", 0L),
            )
        } catch (ex: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
