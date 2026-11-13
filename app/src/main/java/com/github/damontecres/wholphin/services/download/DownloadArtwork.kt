package com.github.damontecres.wholphin.services.download

import com.github.damontecres.wholphin.data.download.DownloadDao
import com.github.damontecres.wholphin.data.download.DownloadedItem
import com.github.damontecres.wholphin.data.download.DownloadedSeries
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves artwork for downloads to app storage (`<downloads>/.art/<id>-<kind>.jpg`) so the Downloads
 * library can draw posters, backdrops and episode thumbnails with no network. Plain OkHttp fetch of
 * the server image URL that is already stored on the row; failures are logged and retried next time.
 */
@Singleton
class DownloadArtwork
    @Inject
    constructor(
        private val dao: DownloadDao,
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) {
        private val client =
            okHttpClient
                .newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

        fun artDir(downloadsDir: File): File = File(downloadsDir, ".art").also { if (!it.exists()) it.mkdirs() }

        /** Episode / movie primary image for [row]; updates the row's localImagePath on success. */
        suspend fun ensureItemImage(
            row: DownloadedItem,
            downloadsDir: File,
        ): String? {
            row.localImagePath?.let { if (File(it).length() > 0L) return it }
            val url = row.imageUrl ?: return null
            val target = File(artDir(downloadsDir), "${row.itemId}-primary.jpg")
            val saved = fetch(url, target) ?: return null
            dao.setLocalImage(row.itemId, saved)
            return saved
        }

        /** Poster + backdrop for the show; updates the series row paths. */
        suspend fun ensureSeriesArt(
            series: DownloadedSeries,
            downloadsDir: File,
        ): DownloadedSeries {
            var updated = series
            if (series.posterPath?.let { File(it).length() > 0L } != true && series.posterUrl != null) {
                fetch(series.posterUrl, File(artDir(downloadsDir), "${series.seriesId}-poster.jpg"))?.let {
                    updated = updated.copy(posterPath = it)
                }
            }
            if (series.backdropPath?.let { File(it).length() > 0L } != true && series.backdropUrl != null) {
                fetch(series.backdropUrl, File(artDir(downloadsDir), "${series.seriesId}-backdrop.jpg"))?.let {
                    updated = updated.copy(backdropPath = it)
                }
            }
            if (updated != series) dao.updateSeries(updated)
            return updated
        }

        fun deleteItemArt(
            itemId: UUID,
            downloadsDir: File,
        ) {
            File(artDir(downloadsDir), "$itemId-primary.jpg").delete()
        }

        fun deleteSeriesArt(
            seriesId: UUID,
            downloadsDir: File,
        ) {
            File(artDir(downloadsDir), "$seriesId-poster.jpg").delete()
            File(artDir(downloadsDir), "$seriesId-backdrop.jpg").delete()
        }

        private suspend fun fetch(
            url: String,
            target: File,
        ): String? =
            withContext(Dispatchers.IO) {
                try {
                    val tmp = File(target.absolutePath + ".part")
                    client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Timber.w("Artwork HTTP %d for %s", resp.code, url)
                            return@withContext null
                        }
                        tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
                    }
                    if (tmp.length() <= 0L) {
                        tmp.delete()
                        return@withContext null
                    }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        return@withContext null
                    }
                    Timber.i("Artwork saved %s (%d bytes)", target.name, target.length())
                    target.absolutePath
                } catch (ex: Exception) {
                    Timber.w(ex, "Artwork fetch failed for %s", url)
                    null
                }
            }
    }
