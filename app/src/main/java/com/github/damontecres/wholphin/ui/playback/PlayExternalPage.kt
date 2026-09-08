package com.github.damontecres.wholphin.ui.playback

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ItemPlaybackDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.PlaylistItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PlaylistCreationResult
import com.github.damontecres.wholphin.services.PlaylistCreator
import com.github.damontecres.wholphin.services.StreamChoiceService
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.MainActivity
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.findActivity
import com.github.damontecres.wholphin.ui.indexOfFirstOrNull
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.getExternalPlayers
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

// --- resolve-in-Wholphin additions ---
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.MediaProtocol

/**
 * External-player playback with Wholphin-driven queue support.
 *
 * Stock Wholphin only ever hands the external player the FIRST item of a queue and never chains.
 * This version keeps the full queue in the view model and drives it item-by-item:
 *  - launches the external player (e.g. Just Player) for the current item,
 *  - on return, reports playback start/stop to Jellyfin per item (resume + watched checkpointing),
 *  - if the item finished (end_by == "playback_completion"), auto-advances to the next item,
 *  - if the user backed out (end_by == "user") or the player is unknown, stops the queue.
 *
 * Just Player note: enable its "post-playback action = exit/next" so it returns control on
 * completion, otherwise auto-play-next cannot fire (no result is delivered while it stays open).
 */
@HiltViewModel
class PlayExternalViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val itemPlaybackDao: ItemPlaybackDao,
        private val playlistCreator: PlaylistCreator,
        private val streamChoiceService: StreamChoiceService,
        private val navigationManager: NavigationManager,
        private val userPreferencesService: UserPreferencesService,
    ) : ViewModel() {
        val state = MutableStateFlow(PlayExternalState())

        // True while an external player has been launched and we're awaiting its result.
        // MainActivity reads this to avoid popping the playback page when we return.
        val launched = savedStateHandle.getMutableStateFlow("launched", false)

        /** Ordered queue of item ids to play through the external player. Held in memory (VM-scoped). */
        private var queue: List<UUID> = emptyList()

        // --- next-episode pre-warm ---
        // A short while after an item starts playing (PREWARM_DELAY_MS), we resolve the NEXT queue
        // item's debrid link in the background and stash it here. When play-next fires, playItemAt
        // reuses it instead of resolving live, so the jump is instant. Best-effort: if it's missing
        // or stale, playItemAt just resolves normally. The delay doubles as a guard so quick
        // browse-in/out of an episode never triggers a resolve (no debrid hammering).
        private data class Prewarmed(
            val itemId: UUID,
            val url: String,
            val atMs: Long,
        )

        private var prewarmed: Prewarmed? = null
        private var prewarmJob: Job? = null

        // Wall-clock time (ms) of the last external-player launch; onResult uses it to detect a bogus
        // instant "completion" from the relaunch race (external player relaunched before it tore down).
        private var lastLaunchMs: Long = 0L
        // Consecutive fast-fail ("relaunch race") retries for the current item; reset when the item
        // changes or a genuine result arrives. Lets a transient race self-heal before we give up.
        private var retryItemId: UUID? = null
        private var retryCount: Int = 0

        // Local resume store. Jellyfin can't persist a resume position for items with null
        // RunTimeTicks (common for debrid/.strm content), so we keep our own keyed by item id.
        private val resumePrefs by lazy {
            context.getSharedPreferences("jp_external_resume", Context.MODE_PRIVATE)
        }

        private fun localResumeMs(id: UUID): Long? = resumePrefs.getLong(id.toString(), -1L).takeIf { it > 0 }

        private fun saveLocalResume(
            id: UUID,
            ms: Long,
        ) = resumePrefs.edit().putLong(id.toString(), ms).apply()

        private fun clearLocalResume(id: UUID) = resumePrefs.edit().remove(id.toString()).apply()

        fun init(destination: Destination) {
            Timber.v("init called: %s", destination)
            Log.i(TAG, "init dest=$destination launched=${launched.value}")
            state.update { it.copy(loading = LoadingState.Loading) }
            viewModelScope.launchDefault {
                try {
                    val initialPositionMs: Long
                    val itemId =
                        when (val d = destination) {
                            is Destination.Playback -> {
                                initialPositionMs = d.positionMs
                                d.itemId
                            }

                            is Destination.PlaybackList -> {
                                initialPositionMs = 0
                                d.itemId
                            }

                            else -> {
                                throw IllegalArgumentException("Destination not supported: $destination")
                            }
                        }

                    val queriedItem = prepStep("get item") { api.userLibraryApi.getItem(itemId).content }
                    val shuffle =
                        when (destination) {
                            is Destination.Playback -> destination.shuffle
                            is Destination.PlaybackList -> destination.shuffle
                            else -> false
                        }
                    val singleItem = listOf(PlaylistItem.Media(BaseItem(queriedItem)))
                    // Build the real playback queue. For an EPISODE, build the "continue from here"
                    // series queue so external playback can auto-advance; for a series/season/collection
                    // (non-playable parent) expand the whole list; a lone movie/video stays single.
                    val playlistItems: List<PlaylistItem> =
                        if (queriedItem.type == BaseItemKind.EPISODE || !queriedItem.type.playable) {
                            val playlistResult =
                                prepStep("build queue") {
                                    if (destination is Destination.PlaybackList) {
                                        playlistCreator.createFrom(
                                            item = queriedItem,
                                            startIndex = destination.startIndex ?: 0,
                                            sortAndDirection = destination.sortAndDirection,
                                            shuffled = shuffle,
                                            recursive = destination.recursive,
                                            filter = destination.filter,
                                        )
                                    } else {
                                        playlistCreator.createFrom(item = queriedItem, shuffled = shuffle)
                                    }
                                }
                            when (val r = playlistResult) {
                                is PlaylistCreationResult.Error ->
                                    if (queriedItem.type.playable) {
                                        singleItem
                                    } else {
                                        state.update { it.copy(loading = LoadingState.Error(r.message, r.ex)) }
                                        return@launchDefault
                                    }

                                is PlaylistCreationResult.Success -> {
                                    val items = r.playlist.items
                                    if (items.isNotEmpty()) {
                                        items
                                    } else if (queriedItem.type.playable) {
                                        singleItem
                                    } else {
                                        showToast(context, "Playlist is empty", Toast.LENGTH_SHORT)
                                        navigationManager.goBack()
                                        return@launchDefault
                                    }
                                }
                            }
                        } else {
                            singleItem
                        }

                    queue = playlistItems.map { it.id }
                    Log.i(TAG, "init type=${queriedItem.type} dest=${destination::class.simpleName} queueSize=${queue.size} startPos=$initialPositionMs")
                    Timber.v("External queue size=%d", queue.size)
                    savedStateHandle[KEY_INDEX] = 0
                    // Fall back to the item's own server-side resume when the destination gives 0.
                    playItemAt(0, overridePositionMs = initialPositionMs.takeIf { it > 0 })
                } catch (ex: Exception) {
                    Timber.e(ex, "Error for destination %s", destination)
                    state.update { it.copy(loading = LoadingState.Error(ex)) }
                }
            }
        }

        /**
         * Runs one pre-resolve Jellyfin call with a hard timeout and a single retry.
         *
         * Jellyfin can stall for minutes on these calls when it ffprobes a cold debrid CDN link
         * for a .strm item ("Error in Probe Provider" server-side); without a cap the spinner
         * never ends and only an app restart recovers. The aborted first attempt usually leaves
         * the CDN link warmed, so the retry tends to be quick. A second timeout throws a plain
         * RuntimeException (NOT the TimeoutCancellationException) because the auto-advance path
         * swallows CancellationException — rethrowing it would wedge the spinner again.
         */
        private suspend fun <T> prepStep(
            what: String,
            block: suspend () -> T,
        ): T =
            try {
                withTimeout(PREP_TIMEOUT_MS) { block() }
            } catch (ex: TimeoutCancellationException) {
                Log.w(TAG, "prep '$what' timed out after ${PREP_TIMEOUT_MS / 1000}s - retrying once")
                state.update { it.copy(statusMessage = "Server slow preparing this item — retrying…") }
                try {
                    withTimeout(PREP_TIMEOUT_MS) { block() }
                } catch (ex2: TimeoutCancellationException) {
                    Log.w(TAG, "prep '$what' timed out twice - surfacing error")
                    throw RuntimeException(
                        "Jellyfin took too long preparing this item " +
                            "(media probe of the source link stalled). " +
                            "Go back and press play again — the retry is usually instant.",
                    )
                }
            }

        /**
         * Resolves and launches the queue item at [index]. If [overridePositionMs] is null the
         * item's own resume position is used (so auto-advanced items resume where left off).
         */
        private suspend fun playItemAt(
            index: Int,
            overridePositionMs: Long?,
        ) {
            if (index !in queue.indices) {
                Timber.v("No item at index %d, finishing queue", index)
                cancelPrewarm()
                navigationManager.goBack()
                state.update { PlayExternalState() }
                launched.update { false }
                return
            }
            val itemId = queue[index]
            // Visible only while the initial spinner is up: distinguishes the Jellyfin prep
            // phase from the later "Finding source…" resolve phase.
            state.update { it.copy(statusMessage = "Loading item info…") }
            val prefs = userPreferencesService.getCurrent()
            val item = BaseItem(prepStep("get item") { api.userLibraryApi.getItem(itemId).content })
            // Prefer an explicit override, then our local resume store, then the server's resume.
            val positionMs = overridePositionMs ?: localResumeMs(itemId) ?: item.resumeMs

            val playbackConfig =
                serverRepository.currentUser?.let { user ->
                    itemPlaybackDao.getItem(user, itemId)?.let {
                        Timber.v("Fetched itemPlayback from DB: %s", it)
                        if (it.sourceId != null) it else null
                    }
                }
            val mediaSource = streamChoiceService.chooseSource(item.data, playbackConfig)
            val plc = streamChoiceService.getPlaybackLanguageChoice(item.data)
            if (mediaSource == null) {
                Timber.w("Media source is null for %s", itemId)
                cancelPrewarm()
                navigationManager.goBack()
                state.update { PlayExternalState() }
                launched.update { false }
                return
            }
            savedStateHandle[KEY_ID] = itemId
            savedStateHandle[KEY_MEDIA_ID] = mediaSource.id
            savedStateHandle[KEY_INDEX] = index
            // mediaSource.runTimeTicks is often null for debrid/.strm sources; fall back to the item.
            val runtimeTicks = mediaSource.runTimeTicks ?: item.data.runTimeTicks
            savedStateHandle[KEY_RUNTIME_TICKS] = runtimeTicks

            val subtitleIndex =
                streamChoiceService
                    .chooseSubtitleStream(
                        source = mediaSource,
                        audioStream = null,
                        seriesId = item.data.seriesId,
                        itemPlayback = playbackConfig,
                        plc = plc,
                        prefs = prefs,
                    )?.index
            val externalSubtitles =
                mediaSource.mediaStreams
                    ?.filter { it.isExternal }
                    ?.sortedWith(compareBy<MediaStream> { it.index == subtitleIndex }.thenBy { it.isDefault })
                    .orEmpty()
            val subtitleUrls =
                externalSubtitles.map {
                    val format = it.path?.let { p -> File(p).extension } ?: "srt"
                    api.subtitleApi
                        .getSubtitleUrl(
                            routeItemId = itemId,
                            routeMediaSourceId = mediaSource.id!!,
                            routeIndex = it.index,
                            routeFormat = format,
                        ).toUri()
                }

            // jf-resolve / debrid .strm sources carry a resolver URL as the media source path
            // (Protocol=Http, IsRemote=true, path=".../api/stream/resolve/..."). Rather than hand the
            // external player the Jellyfin proxy URL — which makes Jellyfin trigger the resolve and
            // hides any failure behind a black screen — we resolve it HERE: a single no-redirect GET
            // returns a 302 whose Location is the real debrid CDN link. That lets us show "finding
            // source" and surface any error in our own UI before ever launching the player.
            val resolverPath = mediaSource.path
            val uri =
                if (mediaSource.protocol == MediaProtocol.HTTP &&
                    resolverPath?.contains("/api/stream/resolve/") == true
                ) {
                    val warm = consumePrewarm(itemId)
                    if (warm != null) {
                        // Pre-warmed by the previous item: skip the resolve (and the modal) entirely.
                        Log.i(TAG, "prewarm HIT item=$itemId -> ${warm.take(90)}")
                        warm.toUri()
                    } else {
                        state.update { it.copy(loading = LoadingState.Loading, statusMessage = "Finding source…") }
                        Log.i(TAG, "resolving via jfresolve item=$itemId path=$resolverPath")
                        val ticker = startStatusTicker()
                        val r =
                            try {
                                resolveDirectUrl(resolverPath)
                            } finally {
                                ticker.cancel()
                            }
                        when (r) {
                            is ResolveResult.Success -> {
                                Log.i(TAG, "resolved item=$itemId -> ${r.url.take(90)}")
                                r.url.toUri()
                            }

                            is ResolveResult.Error -> {
                                Log.i(TAG, "resolve FAILED item=$itemId code=${r.code} reason=${r.reason}")
                                val msg = buildResolveErrorMessage(resolverPath, r)
                                state.update {
                                    it.copy(
                                        loading = LoadingState.Error(msg, null),
                                        statusMessage = null,
                                    )
                                }
                                launched.update { false }
                                return
                            }
                        }
                    }
                } else {
                    api.videosApi
                        .getVideoStreamUrl(
                            itemId = item.id,
                            mediaSourceId = mediaSource.id,
                            static = true,
                        ).toUri()
                }
            val playerId = prefs.appPreferences.playbackPreferences.externalPlayer
            // Make sure player is available, user could have uninstalled it
            val foundPlayer =
                getExternalPlayers(context).firstOrNull { it.identifier == playerId } != null
            val component =
                if (playerId.isNotNullOrBlank() && foundPlayer) {
                    ComponentName.unflattenFromString(playerId)
                } else {
                    null
                }
            Timber.v("playerId=%s, component=%s, index=%d", playerId, component, index)
            val title = "${item.title} ${item.subtitleLong}"
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setComponent(component)
                    setDataAndTypeAndNormalize(uri, "video/*")
                    putExtra("title", title)
                    putExtra("position", positionMs.toInt())

                    // MX/mpv
                    putExtra("return_result", true)
                    putExtra("secure_uri", true)
                    putExtra("subs", subtitleUrls.toTypedArray())
                    putExtra(
                        "subs.name",
                        externalSubtitles
                            .map { it.displayTitle ?: it.index.toString() }
                            .toTypedArray(),
                    )
                    if (subtitleIndex != null) {
                        externalSubtitles
                            .indexOfFirstOrNull { it.index == subtitleIndex }
                            ?.let {
                                putExtra("subs.enable", arrayOf(subtitleUrls[it]))
                            }
                    }

                    // VLC
                    if (subtitleUrls.isNotEmpty()) {
                        putExtra("subtitles_location", subtitleUrls.first().toString())
                    }
                    runtimeTicks?.ticks?.inWholeMilliseconds?.let {
                        putExtra("extra_duration", it)
                    }

                    // Vimu - https://vimu.tv/player-api/
                    putExtra("startfrom", positionMs.toInt())
                    putExtra("forceresume", false)
                    putExtra("forcename", title)
                    externalSubtitles
                        .indexOfFirstOrNull { it.index == subtitleIndex && it.codec == "srt" }
                        ?.let {
                            putExtra("forcedsrt", subtitleUrls[it])
                        }
                }
            Log.i(TAG, "launch index=$index item=$itemId pos=$positionMs runtimeTicks=$runtimeTicks component=$component")
            api.playStateApi.reportPlaybackStart(
                PlaybackStartInfo(
                    canSeek = false,
                    itemId = itemId,
                    isPaused = false,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    isMuted = false,
                ),
            )
            state.update {
                it.copy(
                    loading = LoadingState.Success,
                    intent = intent,
                    launchToken = it.launchToken + 1,
                    statusMessage = null,
                )
            }

            // Record launch time so onResult can spot a bogus instant "completion" (relaunch race).
            lastLaunchMs = System.currentTimeMillis()

            // Kick off the background pre-warm of the *next* item (if any).
            schedulePrewarm(index + 1)
        }

        /**
         * Resolve a jf-resolve/debrid resolver URL to its final direct CDN link. The resolver replies
         * with a 302 whose Location header is the real (range-capable, stable) debrid URL, so we issue
         * a single GET with redirects DISABLED and read Location. Runs on IO; a generous read timeout
         * covers jf-resolve's candidate walk + ffprobe validation. On any non-3xx we pull the
         * FastAPI {"detail": ...} body so the UI can show a real reason instead of a black screen.
         */
        private suspend fun resolveDirectUrl(rawUrl: String): ResolveResult =
            withContext(Dispatchers.IO) {
                var conn: HttpURLConnection? = null
                try {
                    conn =
                        (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = false
                            requestMethod = "GET"
                            connectTimeout = RESOLVE_CONNECT_TIMEOUT_MS
                            readTimeout = RESOLVE_READ_TIMEOUT_MS
                        }
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location")
                        if (location.isNullOrBlank()) {
                            ResolveResult.Error(code, "resolver returned no location")
                        } else {
                            ResolveResult.Success(location)
                        }
                    } else {
                        val body =
                            runCatching {
                                (conn.errorStream ?: conn.inputStream)
                                    ?.bufferedReader()
                                    ?.use { it.readText() }
                                    ?.take(500)
                            }.getOrNull()
                        ResolveResult.Error(code, extractDetail(body) ?: "HTTP $code")
                    }
                } catch (ex: Exception) {
                    ResolveResult.Error(-1, ex.message ?: ex.javaClass.simpleName ?: "network error")
                } finally {
                    conn?.disconnect()
                }
            }

        /** Pull the {"detail": "..."} message out of a FastAPI JSON error body, if present. */
        private fun extractDetail(body: String?): String? {
            if (body.isNullOrBlank()) return null
            return runCatching {
                org.json.JSONObject(body).optString("detail").takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        /**
         * Schedule a background pre-warm of the queue item at [nextIndex]. After PREWARM_DELAY_MS
         * (which also guards against browse-in/out spam) we resolve that item's debrid link and cache
         * it. Cancels any previously scheduled pre-warm so at most one is ever in flight.
         */
        private fun schedulePrewarm(nextIndex: Int) {
            prewarmJob?.cancel()
            if (nextIndex !in queue.indices) return
            val nextId = queue[nextIndex]
            prewarmJob =
                viewModelScope.launchDefault {
                    delay(PREWARM_DELAY_MS)
                    val path = runCatching { resolverPathFor(nextId) }.getOrNull()
                    if (path == null) {
                        Log.i(TAG, "prewarm skip (not a resolver item) next=$nextId")
                        return@launchDefault
                    }
                    Log.i(TAG, "prewarm START next=$nextId")
                    when (val r = runCatching { resolveDirectUrl(path) }.getOrNull()) {
                        is ResolveResult.Success -> {
                            prewarmed = Prewarmed(nextId, r.url, System.currentTimeMillis())
                            Log.i(TAG, "prewarm READY next=$nextId -> ${r.url.take(90)}")
                        }

                        is ResolveResult.Error ->
                            Log.i(TAG, "prewarm miss next=$nextId code=${r.code} reason=${r.reason}")

                        null ->
                            Log.i(TAG, "prewarm aborted next=$nextId")
                    }
                }
        }

        /**
         * Resolve the resolver-path (jf-resolve URL) for [itemId] without launching anything, mirroring
         * the source selection playItemAt does. Returns null if the item isn't a jf-resolve/debrid
         * source (nothing worth pre-warming — local media resolves instantly).
         */
        private suspend fun resolverPathFor(itemId: UUID): String? {
            val item = BaseItem(api.userLibraryApi.getItem(itemId).content)
            val playbackConfig =
                serverRepository.currentUser?.let { user ->
                    itemPlaybackDao.getItem(user, itemId)?.let { if (it.sourceId != null) it else null }
                }
            val mediaSource = streamChoiceService.chooseSource(item.data, playbackConfig) ?: return null
            val path = mediaSource.path
            return if (mediaSource.protocol == MediaProtocol.HTTP &&
                path?.contains("/api/stream/resolve/") == true
            ) {
                path
            } else {
                null
            }
        }

        /**
         * Return a fresh pre-warmed URL for [itemId] and consume it (one-shot), or null if there's no
         * match or it's older than PREWARM_TTL_MS (kept safely under the debrid link's ~60-min life).
         */
        private fun consumePrewarm(itemId: UUID): String? {
            val p = prewarmed ?: return null
            prewarmed = null
            return if (p.itemId == itemId && System.currentTimeMillis() - p.atMs < PREWARM_TTL_MS) {
                p.url
            } else {
                null
            }
        }

        /** Cancel any pending pre-warm and drop a cached link (used when the queue stops). */
        private fun cancelPrewarm() {
            prewarmJob?.cancel()
            prewarmJob = null
            prewarmed = null
        }

        /**
         * While a resolve is in flight, escalate the loading message over time so the user can see it's
         * actively working rather than a frozen spinner. Caller cancels the returned Job when done.
         */
        private fun startStatusTicker(): Job =
            viewModelScope.launchDefault {
                delay(8_000)
                state.update { it.copy(statusMessage = "Still searching — checking providers…") }
                delay(17_000) // ~25s in total
                state.update { it.copy(statusMessage = "Source provider slow to respond…") }
            }

        /**
         * Compose a human-readable resolve-failure message: classify the failure (timeout vs
         * unreachable vs HTTP error) and, best-effort, ask jf-resolve which providers are down so the
         * user sees the real cause (e.g. "TorBox down") instead of a bare "timeout".
         */
        private suspend fun buildResolveErrorMessage(
            resolverPath: String,
            err: ResolveResult.Error,
        ): String {
            val head =
                when {
                    err.code == -1 && err.reason.contains("timeout", ignoreCase = true) ->
                        "No source found — jf-resolve didn't respond within ${RESOLVE_READ_TIMEOUT_MS / 1000}s."
                    err.code == -1 ->
                        "Couldn't reach jf-resolve (${err.reason})."
                    err.code in 400..599 ->
                        "No playable source (jf-resolve ${err.code}: ${err.reason})."
                    else ->
                        "Couldn't find a playable source (${err.reason})."
                }
            val providers = fetchProviderHealth(resolverPath)
            return if (providers != null) "$head\n$providers" else head
        }

        /**
         * Ask jf-resolve's /providers endpoint which debrid/indexer providers are healthy. Returns a
         * short one-line summary, or null if the endpoint isn't reachable (e.g. older jf-resolve).
         */
        private suspend fun fetchProviderHealth(resolverPath: String): String? =
            withContext(Dispatchers.IO) {
                val base = resolverPath.substringBefore("/api/stream/", "")
                if (base.isBlank()) return@withContext null
                var conn: HttpURLConnection? = null
                try {
                    conn =
                        (URL("$base/api/stream/providers").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 4_000
                            readTimeout = 8_000
                        }
                    if (conn.responseCode != 200) return@withContext null
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    val provider = json.optString("provider", "debrid")
                    val debridName =
                        if (provider.equals("rd", ignoreCase = true)) "Real-Debrid" else "TorBox"
                    fun fmt(
                        name: String,
                        o: org.json.JSONObject?,
                    ): String {
                        val st = o?.optString("status") ?: "unknown"
                        if (st == "ok") return "$name ✓"
                        val extra = o?.optString("message").orEmpty()
                        return "$name ✗ $extra".trim()
                    }
                    "Providers: ${fmt(debridName, json.optJSONObject("debrid"))} · ${fmt("Zilean", json.optJSONObject("zilean"))}"
                } catch (ex: Exception) {
                    null
                } finally {
                    conn?.disconnect()
                }
            }

        fun onResult(result: ActivityResult) {
            Log.i(TAG, "onResult ENTER code=${result.resultCode} savedItemId=${savedStateHandle.get<UUID?>(KEY_ID)}")
            viewModelScope.launchDefault {
                val itemId = savedStateHandle.get<UUID?>(KEY_ID)
                try {
                    val mediaSourceId = savedStateHandle.get<String?>(KEY_MEDIA_ID)
                    val currentIndex = savedStateHandle.get<Int?>(KEY_INDEX) ?: 0
                    val runtimeTicks = savedStateHandle.get<Long?>(KEY_RUNTIME_TICKS)
                    if (itemId == null) {
                        Timber.w("itemId is null")
                        return@launchDefault
                    }
                    val data = result.data
                    val endBy = data?.getStringExtra("end_by")
                    val jpDurationMs = data?.getIntExtra("duration", -1) ?: -1
                    Timber.v(
                        "Result: result=%s, action=%s, end_by=%s, itemId=%s, index=%d",
                        result.resultCode,
                        data?.action,
                        endBy,
                        itemId,
                        currentIndex,
                    )

                    // Did the item play to the end? (Just Player/MX send end_by; Vimu uses result code 1)
                    val completed =
                        endBy == "playback_completion" ||
                            (data?.action == "net.gtvbox.videoplayer.result" && result.resultCode == 1)
                    Log.i(TAG, "onResult code=${result.resultCode} action=${data?.action} end_by=$endBy jpDurationMs=$jpDurationMs idx=$currentIndex completed=$completed rtTicks=$runtimeTicks")

                    if (result.resultCode == Activity.RESULT_OK || result.resultCode == Activity.RESULT_CANCELED ||
                        (data?.action == "net.gtvbox.videoplayer.result" && result.resultCode == 1)
                    ) {
                        val position: Long?
                        when (data?.action) {
                            // VLC: https://wiki.videolan.org/Android_Player_Intents/
                            "org.videolan.vlc.player.result" -> {
                                position =
                                    data
                                        .getLongExtra("extra_position", Long.MIN_VALUE)
                                        .takeIf { it > 0 }
                            }

                            // mpv-android: https://mpv-android.github.io/mpv-android/intent.html
                            "is.xyz.mpv.MPVActivity.result",
                            // MX player (also used by Just Player): https://mx.j2inter.com/api
                            "com.mxtech.intent.result.VIEW",
                            // VIMU: https://vimu.tv/player-api/
                            "net.gtvbox.videoplayer.result",
                            -> {
                                position =
                                    data
                                        .getIntExtra("position", Int.MIN_VALUE)
                                        .toLong()
                                        .takeIf { it >= 0 }
                            }

                            else -> {
                                // Unsupported app
                                val posInt =
                                    data
                                        ?.getIntExtra("position", Int.MIN_VALUE)
                                        ?.takeIf { it >= 0 }
                                        ?.toLong()
                                position =
                                    posInt ?: data
                                        ?.getLongExtra("position", -1L)
                                        ?.takeIf { it >= 0 }
                            }
                        }
                        Timber.v("Result position: %s (completed=%s)", position?.milliseconds, completed)

                        // Guard against the relaunch race: when the external player is relaunched within
                        // ~1s of closing, it can open the next item and instantly return a bogus
                        // "playback_completion" (sub-second elapsed). Trusting it would falsely mark the
                        // episode watched and auto-skip it. Treat an implausibly fast completion as a
                        // failed launch: retry the SAME item once after a settle delay, and only surface
                        // an error if it fails again. Nothing has been marked/reported at this point.
                        val elapsedMs = System.currentTimeMillis() - lastLaunchMs
                        if (completed && elapsedMs in 0 until MIN_REAL_PLAY_MS) {
                            if (itemId != retryItemId) {
                                retryItemId = itemId
                                retryCount = 0
                            }
                            retryCount++
                            Log.i(TAG, "SUSPECT fast completion item=$itemId elapsedMs=$elapsedMs attempt=$retryCount/$MAX_RELAUNCH_RETRIES")
                            if (retryCount <= MAX_RELAUNCH_RETRIES) {
                                // Transient relaunch race — wait a growing beat, then retry the SAME item.
                                delay(RELAUNCH_SETTLE_MS * retryCount)
                                playItemAt(currentIndex, overridePositionMs = null)
                            } else {
                                // Still won't start after several tries — surface it instead of looping
                                // or silently skipping.
                                retryItemId = null
                                retryCount = 0
                                cancelPrewarm()
                                state.update {
                                    it.copy(
                                        loading = LoadingState.Error("Couldn't play this episode — try again.", null),
                                    )
                                }
                                launched.update { false }
                            }
                            return@launchDefault
                        }
                        // Genuine result — reset the retry counter.
                        retryItemId = null
                        retryCount = 0

                        // Only report the stop to Jellyfin when we actually know the item's runtime.
                        // For null-runtime items (debrid/.strm) Jellyfin treats ANY reported stop
                        // position as ">=90% => played" and marks it watched (and zeroes the resume) —
                        // that's the false "watched after a few minutes" bug. We handle those entirely
                        // locally instead, and only mark watched on a genuine end-of-file completion.
                        if (runtimeTicks != null) {
                            val reportTicks =
                                if (completed) runtimeTicks else position?.milliseconds?.inWholeTicks?.takeIf { it >= 0 }
                            api.playStateApi.reportPlaybackStopped(
                                PlaybackStopInfo(
                                    itemId = itemId,
                                    mediaSourceId = mediaSourceId,
                                    positionTicks = reportTicks,
                                    failed = false,
                                ),
                            )
                            Log.i(TAG, "reportStopped(server) item=$itemId reportTicks=$reportTicks")
                        } else {
                            Log.i(TAG, "skip server stop-report (null runtime) item=$itemId completed=$completed")
                        }

                        if (completed) {
                            clearLocalResume(itemId)
                            val r = runCatching { api.playStateApi.markPlayedItem(itemId) }
                            Log.i(TAG, "completion markPlayed ok=${r.isSuccess} err=${r.exceptionOrNull()?.message} item=$itemId")
                        } else if (position != null && jpDurationMs > 0 &&
                            position >= jpDurationMs.toLong() * WATCHED_PERCENT / 100
                        ) {
                            // Watched past the ~90% mark then backed out before the very end. Null-runtime
                            // (debrid/.strm) items can't use Jellyfin's server-side 90%-watched rule, so we
                            // apply it here using the duration Just Player reports on exit.
                            clearLocalResume(itemId)
                            val r = runCatching { api.playStateApi.markPlayedItem(itemId) }
                            Log.i(TAG, "near-end markPlayed ok=${r.isSuccess} pos=$position dur=$jpDurationMs item=$itemId")
                        } else if (position != null && position > RESUME_MIN_MS) {
                            saveLocalResume(itemId, position)
                            Log.i(TAG, "saveLocalResume item=$itemId pos=$position")
                        }
                    } else {
                        Timber.w("Activity result: %s, action=%s", result.resultCode, data?.action)
                        showToast(context, "Unknown result from external player")
                    }

                    // Auto-advance to the next queue item only when the item finished cleanly.
                    val nextIndex = currentIndex + 1
                    Log.i(TAG, "advance? completed=$completed next=$nextIndex inRange=${nextIndex in queue.indices} queueSize=${queue.size}")
                    if (completed && nextIndex in queue.indices) {
                        Timber.i("Auto-advancing external playback to index %d", nextIndex)
                        // Settle: let the external player fully close before relaunching, or it can
                        // return a bogus instant completion for the next item (the relaunch race).
                        delay(RELAUNCH_SETTLE_MS)
                        playItemAt(nextIndex, overridePositionMs = null)
                    } else {
                        cancelPrewarm()
                        navigationManager.goBack()
                        state.update { PlayExternalState() }
                        launched.update { false }
                    }
                } catch (_: CancellationException) {
                } catch (ex: Exception) {
                    Timber.e(ex, "Error during external playback of %s", itemId)
                    state.update { it.copy(loading = LoadingState.Error(ex)) }
                }
            }
        }

        fun reportException(ex: Exception) {
            Timber.e(ex, "Error launching activity")
            state.update { it.copy(loading = LoadingState.Error(ex)) }
        }

        companion object {
            private const val TAG = "WholphinJP"
            private const val RESUME_MIN_MS = 10_000L

            // Watched fraction (%) at/above which a backed-out null-runtime item is marked played,
            // using the duration Just Player reports on exit (Jellyfin can't do this without a runtime).
            private const val WATCHED_PERCENT = 90L
            private const val KEY_ID = "itemId"
            private const val KEY_MEDIA_ID = "mediaId"
            private const val KEY_INDEX = "queueIndex"
            private const val KEY_RUNTIME_TICKS = "runtimeTicks"

            // jf-resolve's candidate walk + ffprobe gate can take a while on a cold item, so give the
            // read a wide window (must exceed jf-resolve's stream_probe_timeout_seconds, default 10s).
            private const val RESOLVE_CONNECT_TIMEOUT_MS = 15_000
            private const val RESOLVE_READ_TIMEOUT_MS = 45_000

            // Hard cap per pre-resolve Jellyfin call (getItem / queue build). Jellyfin can stall
            // for minutes ffprobing a cold debrid CDN link for a .strm item (server-side
            // "Error in Probe Provider"); see prepStep.
            private const val PREP_TIMEOUT_MS = 20_000L

            // Pre-warm the next item this long after the current one starts. Also a guard: browsing
            // in/out of an episode faster than this never triggers a background resolve.
            private const val PREWARM_DELAY_MS = 180_000L // 3 min

            // Discard a pre-warmed link older than this — kept comfortably under the debrid link's
            // ~60-min validity (and jf-resolve's 60-min resolve cache).
            private const val PREWARM_TTL_MS = 2_700_000L // 45 min

            // A "completion" that returns faster than this is treated as a failed launch (the relaunch
            // race), not a real finish — real episodes run for minutes. See onResult.
            private const val MIN_REAL_PLAY_MS = 5_000L
            // Settle time to let the external player fully close before we relaunch the next item.
            private const val RELAUNCH_SETTLE_MS = 2_500L
            // How many times to re-try a suspect fast-completion (relaunch race) before giving up.
            // Retry delay grows (RELAUNCH_SETTLE_MS * attempt) so a stubborn race gets a bigger gap.
            private const val MAX_RELAUNCH_RETRIES = 3
        }
    }

/** Result of resolving a jf-resolve URL to its final direct debrid link. */
sealed interface ResolveResult {
    data class Success(
        val url: String,
    ) : ResolveResult

    data class Error(
        val code: Int,
        val reason: String,
    ) : ResolveResult
}

data class PlayExternalState(
    val loading: LoadingState = LoadingState.Pending,
    val intent: Intent = Intent(),
    // Incremented each time a new item is ready to launch; drives the launcher effect.
    val launchToken: Int = 0,
    // Non-null while resolving a debrid source; shown in the loading modal ("Finding source…").
    val statusMessage: String? = null,
)

@Composable
fun PlayExternalPage(
    preferences: UserPreferences,
    destination: Destination,
    modifier: Modifier = Modifier,
    viewModel: PlayExternalViewModel =
        hiltViewModel(
            viewModelStoreOwner = LocalContext.current.findActivity() as AppCompatActivity,
        ),
) {
    val activity = LocalContext.current.findActivity() as MainActivity

    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        Log.i("WholphinJP", "PlayExternalPage compose: launched=${viewModel.launched.value} dest=$destination")
        // If a playback is already in flight (e.g. this page was recomposed while an external player
        // is up), don't re-init - that would rebuild the queue and relaunch, racing the auto-advance
        // and flicking between episodes. The activity-level result handler drives the return instead.
        if (!viewModel.launched.value) {
            viewModel.init(destination)
        }
    }

    when (val l = state.loading) {
        LoadingState.Pending -> {
            LoadingPage(modifier, false)
        }

        LoadingState.Loading,
        -> {
            val msg = state.statusMessage
            if (msg != null) {
                ResolvingPage(msg, modifier)
            } else {
                LoadingPage(modifier)
            }
        }

        is LoadingState.Error -> {
            ErrorMessage(l, modifier)
        }

        LoadingState.Success -> {
            LoadingPage(modifier)
            // Launch (or re-launch for the next queue item) whenever launchToken changes.
            LaunchedEffect(state.launchToken) {
                if (state.launchToken > 0) {
                    Timber.i("Launching external playback (token=%d)", state.launchToken)
                    viewModel.launched.update { true }
                    try {
                        activity.launchExternalPlayer(state.intent)
                    } catch (ex: Exception) {
                        viewModel.reportException(ex)
                    }
                }
            }
        }
    }
}

/** Full-screen loading modal that shows a spinner plus a status line (e.g. "Finding source…"). */
@Composable
private fun ResolvingPage(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.border,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
