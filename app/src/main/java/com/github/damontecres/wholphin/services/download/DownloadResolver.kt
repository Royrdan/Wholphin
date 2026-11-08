package com.github.damontecres.wholphin.services.download

import com.github.damontecres.wholphin.ui.playback.StrmResolveResult
import com.github.damontecres.wholphin.ui.playback.isJfResolvePath
import com.github.damontecres.wholphin.ui.playback.resolveDebridDirectUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Quality tiers offered by the download picker. [param] is what jf-resolve's `quality=` expects. */
enum class DownloadQuality(
    val param: String,
    val label: String,
) {
    AUTO("auto", "Auto"),
    UHD("4k", "4K"),
    P1080("1080p", "1080p"),
    P720("720p", "720p"),
    P480("480p", "480p"),
    ;

    companion object {
        val DEFAULT = P1080

        fun fromParam(param: String?): DownloadQuality = entries.firstOrNull { it.param == param } ?: DEFAULT
    }
}

sealed interface DownloadResolveResult {
    data class Success(
        val url: String,
    ) : DownloadResolveResult

    /** jf-resolve is still caching the copy on TorBox: try again later. */
    data class RetryLater(
        val reason: String,
    ) : DownloadResolveResult

    /** Network error or similar: worth another attempt but not a caching wait. */
    data class Transient(
        val reason: String,
    ) : DownloadResolveResult

    data class Failed(
        val code: Int,
        val reason: String,
    ) : DownloadResolveResult
}

/**
 * Builds the jf-resolve link for a download and follows it to the CDN URL. Mirrors what
 * PlaybackViewModel.resolverPathFor + resolveDebridDirectUrl do for playback.
 */
object DownloadResolver {
    private val headClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** The jf-resolve media source of [dto] (highest resolution if several), or null if it is not a .strm. */
    fun resolverPathFor(
        dto: BaseItemDto,
        chooseSource: (List<MediaSourceInfo>?) -> MediaSourceInfo?,
    ): String? {
        val source = chooseSource(dto.mediaSources) ?: dto.mediaSources?.firstOrNull() ?: return null
        val path = source.path
        return if (source.protocol == MediaProtocol.HTTP && path.isJfResolvePath()) path else null
    }

    /**
     * Rewrite (or add) the `quality` query parameter. [DownloadQuality.AUTO] leaves the link as the
     * .strm has it, so jf-resolve's own series preference applies.
     */
    fun withQuality(
        resolverPath: String,
        quality: DownloadQuality,
    ): String {
        if (quality == DownloadQuality.AUTO) return resolverPath
        val url = resolverPath.toHttpUrlOrNull() ?: return resolverPath
        return url
            .newBuilder()
            .removeAllQueryParameters("quality")
            .addQueryParameter("quality", quality.param)
            .build()
            .toString()
    }

    /** One no-redirect GET on the resolver link, classified for the worker's retry policy. */
    suspend fun resolve(resolverUrl: String): DownloadResolveResult =
        when (val r = resolveDebridDirectUrl(resolverUrl)) {
            is StrmResolveResult.Success -> DownloadResolveResult.Success(r.url)
            is StrmResolveResult.Error -> {
                val reason = r.reason
                when {
                    r.code == 404 && reason.contains("cach", ignoreCase = true) ->
                        DownloadResolveResult.RetryLater(reason)

                    r.code < 0 || r.code in 500..599 || r.code == 429 ->
                        DownloadResolveResult.Transient(reason)

                    else -> DownloadResolveResult.Failed(r.code, reason)
                }
            }
        }

    /**
     * Size of the file behind a CDN URL, via HEAD, falling back to a 1-byte ranged GET whose
     * Content-Range carries the total. Null when the server will not say.
     */
    suspend fun contentLength(url: String): Long? =
        withContext(Dispatchers.IO) {
            runCatching {
                headClient.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                    } else {
                        null
                    }
                }
            }.getOrNull() ?: runCatching {
                headClient
                    .newCall(
                        Request
                            .Builder()
                            .url(url)
                            .header("Range", "bytes=0-0")
                            .build(),
                    ).execute()
                    .use { resp ->
                        val range = resp.header("Content-Range")
                        range
                            ?.substringAfterLast('/')
                            ?.trim()
                            ?.toLongOrNull()
                            ?.takeIf { it > 0 }
                            ?: resp.header("Content-Length")?.toLongOrNull()?.takeIf { resp.code == 200 && it > 1 }
                    }
            }.onFailure { Timber.w(it, "contentLength probe failed") }.getOrNull()
        }

    /** File extension from the CDN URL path when it looks like a video container, else "mkv". */
    fun extensionFor(url: String): String {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?')
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) ext else "mkv"
    }
}
