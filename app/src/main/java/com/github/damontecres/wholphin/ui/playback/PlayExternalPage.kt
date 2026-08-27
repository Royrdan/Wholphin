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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

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

                    val queriedItem = api.userLibraryApi.getItem(itemId).content
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
         * Resolves and launches the queue item at [index]. If [overridePositionMs] is null the
         * item's own resume position is used (so auto-advanced items resume where left off).
         */
        private suspend fun playItemAt(
            index: Int,
            overridePositionMs: Long?,
        ) {
            if (index !in queue.indices) {
                Timber.v("No item at index %d, finishing queue", index)
                navigationManager.goBack()
                state.update { PlayExternalState() }
                launched.update { false }
                return
            }
            val itemId = queue[index]
            val prefs = userPreferencesService.getCurrent()
            val item = BaseItem(api.userLibraryApi.getItem(itemId).content)
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

            val uri =
                api.videosApi
                    .getVideoStreamUrl(
                        itemId = item.id,
                        mediaSourceId = mediaSource.id,
                        static = true,
                    ).toUri()
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
                )
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
                        playItemAt(nextIndex, overridePositionMs = null)
                    } else {
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
            private const val KEY_ID = "itemId"
            private const val KEY_MEDIA_ID = "mediaId"
            private const val KEY_INDEX = "queueIndex"
            private const val KEY_RUNTIME_TICKS = "runtimeTicks"
        }
    }

data class PlayExternalState(
    val loading: LoadingState = LoadingState.Pending,
    val intent: Intent = Intent(),
    // Incremented each time a new item is ready to launch; drives the launcher effect.
    val launchToken: Int = 0,
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
            LoadingPage(modifier)
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
