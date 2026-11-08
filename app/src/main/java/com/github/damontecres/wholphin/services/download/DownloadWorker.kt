package com.github.damontecres.wholphin.services.download

import android.content.Context
import android.os.StatFs
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.damontecres.wholphin.data.download.DownloadDao
import com.github.damontecres.wholphin.data.download.DownloadState
import com.github.damontecres.wholphin.data.download.DownloadedItem
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Drains the download queue one item at a time: resolve the jf-resolve link to the CDN file,
 * ranged GET from the current byte offset into app-private storage, verify the length, mark DONE.
 *
 * Only runs on an unmetered network (constraint set by [DownloadRepository.scheduleWorker]); when
 * Wi-Fi drops WorkManager stops the worker and restarts it later, and the file's own length is
 * the resume offset.
 */
@HiltWorker
class DownloadWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted params: WorkerParameters,
        private val dao: DownloadDao,
        private val repository: DownloadRepository,
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) : CoroutineWorker(context, params) {
        private val client: OkHttpClient =
            okHttpClient
                .newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        private val notifications by lazy {
            DownloadNotifications(context, WorkManager.getInstance(context).createCancelPendingIntent(id))
        }

        private var currentTitle: String = "Downloads"

        override suspend fun getForegroundInfo(): ForegroundInfo =
            notifications.foregroundInfo(notifications.build(currentTitle, "Starting…", null, 0))

        override suspend fun doWork(): Result {
            Timber.i("DownloadWorker start (attempt %d)", runAttemptCount)
            runCatching { setForeground(getForegroundInfo()) }
                .onFailure { Timber.w(it, "setForeground failed; continuing without foreground") }

            dao.requeueInFlight()
            var finished = 0
            var failed = 0
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val next = dao.nextActive() ?: break
                    when (processItem(next)) {
                        ItemOutcome.DONE -> finished++
                        ItemOutcome.FAILED -> failed++
                        ItemOutcome.SKIPPED -> Unit
                    }
                }
            } catch (ex: CancellationException) {
                // Stopped by WorkManager: constraint lost (keep rows queued so it resumes) or the
                // user tapped the notification action / cancelled (pause everything).
                withContext(NonCancellable) {
                    val reason = runCatching { stopReason }.getOrDefault(WorkInfo.STOP_REASON_UNKNOWN)
                    if (reason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                        Timber.i("Cancelled by app: pausing active downloads")
                        dao.pauseAllActive()
                    } else {
                        Timber.i("Stopped (reason=%d): leaving queue in place", reason)
                        dao.requeueInFlight()
                    }
                }
                throw ex
            }
            notifications.showFinished(finished, failed)
            Timber.i("DownloadWorker done: finished=%d failed=%d", finished, failed)
            return Result.success()
        }

        private enum class ItemOutcome { DONE, FAILED, SKIPPED }

        private suspend fun processItem(initial: DownloadedItem): ItemOutcome {
            var row = initial
            currentTitle = displayTitle(row)
            Timber.i("Processing %s (%s)", row.itemId, currentTitle)
            notify(row, null, "Preparing…")

            // ---- resolve (with caching back-off) ----
            var cdnUrl: String? = null
            val cachingBackoffs = longArrayOf(30_000L, 60_000L, 120_000L)
            var cachingWaits = 0
            var transientFailures = 0
            while (cdnUrl == null) {
                if (!stillWanted(row.itemId)) return ItemOutcome.SKIPPED
                dao.setState(row.itemId, DownloadState.RESOLVING, null)
                when (val r = DownloadResolver.resolve(row.resolverUrl)) {
                    is DownloadResolveResult.Success -> cdnUrl = r.url
                    is DownloadResolveResult.RetryLater -> {
                        if (cachingWaits >= cachingBackoffs.size) {
                            return fail(row, r.reason)
                        }
                        val wait = cachingBackoffs[cachingWaits++]
                        Timber.i("Source still caching for %s, waiting %ds: %s", row.itemId, wait / 1000, r.reason)
                        notify(row, null, "Waiting for source (${wait / 1000}s)…")
                        delay(wait)
                    }

                    is DownloadResolveResult.Transient -> {
                        if (++transientFailures > 3) return fail(row, r.reason)
                        delay(10_000L * transientFailures)
                    }

                    is DownloadResolveResult.Failed -> return fail(row, "${r.reason} (HTTP ${r.code})")
                }
            }

            // ---- file target ----
            val file = File(row.filePath ?: targetPath(row, cdnUrl))
            file.parentFile?.mkdirs()
            if (row.filePath == null) {
                row = row.copy(filePath = file.absolutePath)
                dao.update(row)
            }

            // ---- download with resume + re-resolve ----
            var attempts = 0
            var lastError: String? = null
            while (attempts < MAX_ATTEMPTS) {
                attempts++
                if (!stillWanted(row.itemId)) return ItemOutcome.SKIPPED
                val offset = if (file.exists()) file.length() else 0L
                if (!hasFreeSpace(file, row.bytesTotal, offset)) {
                    return fail(row, "Not enough free space on the phone")
                }
                dao.setState(row.itemId, DownloadState.DOWNLOADING, null)
                try {
                    val outcome = downloadRange(row, cdnUrl!!, file, offset)
                    when (outcome) {
                        RangeOutcome.COMPLETE -> {
                            val total = file.length()
                            dao.update(
                                dao.get(row.itemId)?.copy(
                                    state = DownloadState.DONE,
                                    bytesDone = total,
                                    bytesTotal = total,
                                    error = null,
                                    finishedAt = System.currentTimeMillis(),
                                    filePath = file.absolutePath,
                                ) ?: return ItemOutcome.SKIPPED,
                            )
                            Timber.i("DONE %s -> %s (%d bytes)", row.itemId, file.absolutePath, total)
                            return ItemOutcome.DONE
                        }

                        RangeOutcome.ABANDONED -> return ItemOutcome.SKIPPED
                        RangeOutcome.LINK_EXPIRED, RangeOutcome.SHORT_READ -> {
                            lastError = if (outcome == RangeOutcome.LINK_EXPIRED) "link expired" else "connection dropped"
                        }
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: NoSpaceException) {
                    return fail(row, ex.message ?: "Not enough free space")
                } catch (ex: IOException) {
                    lastError = ex.message ?: ex.javaClass.simpleName
                    Timber.w(ex, "download IO error for %s (attempt %d)", row.itemId, attempts)
                } catch (ex: Exception) {
                    Timber.e(ex, "download error for %s", row.itemId)
                    return fail(row, ex.message ?: ex.javaClass.simpleName)
                }
                // Re-resolve before the next go; the TorBox link may have expired.
                delay(3_000L * attempts)
                notify(row, null, "Retrying ($attempts/$MAX_ATTEMPTS)…")
                var freshUrl: String? = null
                repeat(3) { i ->
                    if (freshUrl == null) {
                        when (val r = DownloadResolver.resolve(row.resolverUrl)) {
                            is DownloadResolveResult.Success -> freshUrl = r.url
                            is DownloadResolveResult.RetryLater -> delay(30_000L)
                            is DownloadResolveResult.Transient -> delay(5_000L * (i + 1))
                            is DownloadResolveResult.Failed -> return fail(row, "${r.reason} (HTTP ${r.code})")
                        }
                    }
                }
                cdnUrl = freshUrl ?: cdnUrl
            }
            return fail(row, "Gave up after $MAX_ATTEMPTS attempts (${lastError ?: "unknown error"})")
        }

        private enum class RangeOutcome { COMPLETE, LINK_EXPIRED, SHORT_READ, ABANDONED }

        /**
         * One ranged GET appended to [file] from [offset]. Returns COMPLETE when the file reaches the
         * server's total length.
         */
        private suspend fun downloadRange(
            row: DownloadedItem,
            url: String,
            file: File,
            offset: Long,
        ): RangeOutcome =
            withContext(Dispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
                        .build()
                client.newCall(request).execute().use { resp ->
                    when (resp.code) {
                        403, 410, 401 -> return@withContext RangeOutcome.LINK_EXPIRED
                        416 -> {
                            // Range past the end: trust a known total, otherwise start over.
                            val total = resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                            if (total != null && file.length() == total) return@withContext RangeOutcome.COMPLETE
                            file.delete()
                            return@withContext RangeOutcome.SHORT_READ
                        }
                    }
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from CDN")
                    val body = resp.body

                    val resumed = resp.code == 206 && offset > 0L
                    val total: Long =
                        if (resumed) {
                            resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                                ?: (offset + body.contentLength()).takeIf { body.contentLength() > 0 } ?: -1L
                        } else {
                            body.contentLength()
                        }
                    val startAt = if (resumed) offset else 0L
                    if (!resumed && file.exists()) file.delete()
                    if (total > 0L && !hasFreeSpace(file, total, startAt)) {
                        throw NoSpaceException()
                    }

                    var written = startAt
                    var sinceDb = 0L
                    var lastNotify = 0L
                    dao.updateProgress(row.itemId, written, total.coerceAtLeast(0L))
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(startAt)
                        raf.setLength(startAt)
                        val buffer = ByteArray(256 * 1024)
                        body.byteStream().use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                raf.write(buffer, 0, n)
                                written += n
                                sinceDb += n
                                if (sinceDb >= DB_UPDATE_BYTES) {
                                    sinceDb = 0L
                                    dao.updateProgress(row.itemId, written, total.coerceAtLeast(0L))
                                    if (!stillWanted(row.itemId)) {
                                        return@withContext RangeOutcome.ABANDONED
                                    }
                                    val now = System.currentTimeMillis()
                                    if (now - lastNotify > 1_000L) {
                                        lastNotify = now
                                        notify(row, progressPercent(written, total), progressText(written, total))
                                    }
                                }
                            }
                        }
                    }
                    dao.updateProgress(row.itemId, written, total.coerceAtLeast(0L))
                    when {
                        total > 0L && written < total -> RangeOutcome.SHORT_READ
                        total > 0L && written > total -> {
                            Timber.w("Wrote more than expected (%d > %d); restarting", written, total)
                            file.delete()
                            RangeOutcome.SHORT_READ
                        }

                        else -> RangeOutcome.COMPLETE
                    }
                }
            }

        private class NoSpaceException : IOException("Not enough free space on the phone")

        /** False when the row was deleted, paused, or otherwise taken out of the queue meanwhile. */
        private suspend fun stillWanted(itemId: java.util.UUID): Boolean {
            val current = dao.get(itemId) ?: run {
                Timber.i("Row %s gone; abandoning", itemId)
                return false
            }
            return current.state.isActive
        }

        private suspend fun fail(
            row: DownloadedItem,
            reason: String,
        ): ItemOutcome {
            Timber.w("FAILED %s: %s", row.itemId, reason)
            dao.setState(row.itemId, DownloadState.FAILED, reason.take(300))
            return ItemOutcome.FAILED
        }

        private fun hasFreeSpace(
            file: File,
            bytesTotal: Long,
            alreadyHave: Long,
        ): Boolean {
            val dir = file.parentFile ?: repository.downloadsDir()
            val free = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            val need =
                if (bytesTotal > 0L) (bytesTotal - alreadyHave).coerceAtLeast(0L) + HEADROOM_BYTES else UNKNOWN_SIZE_NEED
            return free >= need
        }

        private fun targetPath(
            row: DownloadedItem,
            cdnUrl: String,
        ): String {
            val folder = File(repository.downloadsDir(), safe(row.seriesName ?: "Movies"))
            val base =
                if (row.isEpisode) {
                    "${row.episodeTag} - ${row.title}"
                } else {
                    row.title
                }
            val ext = DownloadResolver.extensionFor(cdnUrl)
            return File(folder, safe(base).take(120) + "." + ext).absolutePath
        }

        private fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9 ._()'&,-]"), "_").trim().ifBlank { "item" }

        private fun displayTitle(row: DownloadedItem): String =
            if (row.isEpisode) "${row.seriesName ?: ""} ${row.episodeTag} ${row.title}".trim() else row.title

        private suspend fun notify(
            row: DownloadedItem,
            progress: Int?,
            text: String,
        ) {
            val remaining = runCatching { dao.countActive() }.getOrDefault(1)
            notifications.update(notifications.build(displayTitle(row), text, progress, remaining))
        }

        private fun progressPercent(
            done: Long,
            total: Long,
        ): Int? = if (total > 0L) ((done * 100) / total).toInt().coerceIn(0, 100) else null

        private fun progressText(
            done: Long,
            total: Long,
        ): String =
            if (total > 0L) {
                "${formatBytes(done)} of ${formatBytes(total)}"
            } else {
                formatBytes(done)
            }

        companion object {
            const val WORK_NAME = "wholphin-downloads"
            const val TAG = "wholphin-downloads"
            private const val MAX_ATTEMPTS = 5
            private const val DB_UPDATE_BYTES = 1L shl 20 // ~1 MB
            private const val HEADROOM_BYTES = 1L shl 30 // 1 GB
            private const val UNKNOWN_SIZE_NEED = 4L shl 30 // 4 GB
        }
    }

fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "?"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "%.1f GB".format(bytes / gb)
        bytes >= mb -> "%.0f MB".format(bytes / mb)
        bytes >= kb -> "%.0f KB".format(bytes / kb)
        else -> "$bytes B"
    }
}
