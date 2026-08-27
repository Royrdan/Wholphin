package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomePageSettings
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.SUPPORTED_HOME_PAGE_SETTINGS_VERSION
import com.github.damontecres.wholphin.data.model.createGenreDestination
import com.github.damontecres.wholphin.data.model.createStudioDestination
import com.github.damontecres.wholphin.preferences.DefaultUserConfiguration
import com.github.damontecres.wholphin.preferences.HomePagePreferences
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.ui.ProgramItemFields
import com.github.damontecres.wholphin.ui.components.getGenreImageMap
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.main.settings.favoriteOptions
import com.github.damontecres.wholphin.ui.playback.getTypeFor
import com.github.damontecres.wholphin.ui.toBaseItems
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.ui.util.ResArgStringProvider
import com.github.damontecres.wholphin.ui.util.ResProviderStringProvider
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.ui.util.StringProvider
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.GetGenresRequestHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.GetLiveTvChannelsRequestHandler
import com.github.damontecres.wholphin.util.GetPersonsHandler
import com.github.damontecres.wholphin.util.GetProgramsDtoHandler
import com.github.damontecres.wholphin.util.GetRecordingsRequestHandler
import com.github.damontecres.wholphin.util.GetStudiosRequestHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.HomeRowLoadingState.Success
import com.github.damontecres.wholphin.util.supportedHomeCollectionTypes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.GetProgramsDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.request.GetGenresRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import org.jellyfin.sdk.model.api.request.GetPersonsRequest
import org.jellyfin.sdk.model.api.request.GetRecordingsRequest
import org.jellyfin.sdk.model.api.request.GetStudiosRequest
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles getting home page settings and data
 */
@Singleton
class HomeSettingsService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val userPreferencesService: UserPreferencesService,
        private val navDrawerService: NavDrawerService,
        private val latestNextUpService: LatestNextUpService,
        private val imageUrlService: ImageUrlService,
        private val suggestionService: SuggestionService,
        private val displayPreferencesService: DisplayPreferencesService,
        private val watchlistService: WatchlistService,
    ) {
        @OptIn(ExperimentalSerializationApi::class)
        val jsonParser =
            Json {
                isLenient = true
                ignoreUnknownKeys = true
                allowTrailingComma = true
            }

        /**
         * The current home page settings
         */
        val currentSettings = MutableStateFlow(HomePageResolvedSettings.EMPTY)

        /**
         * Saves a [HomePageSettings] to the server for the user under the display preference ID
         *
         * @see loadFromServer
         */
        suspend fun saveToServer(
            userId: UUID,
            settings: HomePageSettings,
            displayPreferencesId: String = DisplayPreferencesService.DEFAULT_DISPLAY_PREF_ID,
        ) {
            displayPreferencesService.updateDisplayPreferences(userId, displayPreferencesId) {
                put(CUSTOM_PREF_ID, jsonParser.encodeToString(settings))
            }
        }

        /**
         * Reads a [HomePageSettings] from the server for the user and display preference ID
         *
         * Returns null if there is none saved
         *
         * @see saveToServer
         */
        suspend fun loadFromServer(
            userId: UUID,
            displayPreferencesId: String = DisplayPreferencesService.DEFAULT_DISPLAY_PREF_ID,
        ): HomePageSettings? =
            displayPreferencesService
                .getDisplayPreferences(userId, displayPreferencesId)
                .customPrefs[CUSTOM_PREF_ID]
                ?.let {
                    val jsonElement = jsonParser.parseToJsonElement(it)
                    decode(jsonElement)
                }

        /**
         * Computes the filename for locally saved [HomePageSettings]
         */
        private fun filename(userId: UUID) = "${CUSTOM_PREF_ID}_${userId.toServerString()}.json"

        /**
         * Save the [HomePageSettings] for the user locally on the device
         *
         * @see loadFromLocal
         */
        @OptIn(ExperimentalSerializationApi::class)
        suspend fun saveToLocal(
            userId: UUID,
            settings: HomePageSettings,
        ) {
            val dir = File(context.filesDir, CUSTOM_PREF_ID)
            dir.mkdirs()
            File(dir, filename(userId)).outputStream().use {
                jsonParser.encodeToStream(settings, it)
            }
        }

        /**
         * Reads [HomePageSettings] for the user if it exists
         *
         * @see saveToLocal
         */
        @OptIn(ExperimentalSerializationApi::class)
        suspend fun loadFromLocal(userId: UUID): HomePageSettings? {
            val dir = File(context.filesDir, CUSTOM_PREF_ID)
            val file = File(dir, filename(userId))
            return if (file.exists()) {
                val fileContents = file.readText()
                val jsonElement = jsonParser.parseToJsonElement(fileContents)
                decode(jsonElement)
            } else {
                null
            }
        }

        /**
         * Decodes [HomePageSettings] from a [JsonElement] skipping any unknown/unparsable rows
         *
         * This is public only for testing
         */
        fun decode(element: JsonElement): HomePageSettings {
            val version = element.jsonObject["version"]?.jsonPrimitive?.intOrNull
            if (version == null || version > SUPPORTED_HOME_PAGE_SETTINGS_VERSION) {
                throw UnsupportedHomeSettingsVersionException(version)
            }
            val rowsElement = element.jsonObject["rows"]?.jsonArray
            val rows =
                rowsElement
                    ?.mapNotNull { row ->
                        try {
                            jsonParser.decodeFromJsonElement<HomeRowConfig>(row)
                        } catch (ex: Exception) {
                            Timber.w(ex, "Unknown row %s", row)
                            // TODO maybe use placeholder instead of null?
                            null
                        }
                    }.orEmpty()
            return HomePageSettings(rows, version)
        }

        /**
         * Loads [HomePageSettings] into [currentSettings]
         *
         * First checks locally, then on the server, and finally creates a default if needed
         *
         * Does not persist either the server nor default
         */
        suspend fun loadCurrentSettings(userId: UUID) {
            Timber.v("Getting setting for %s", userId)
            // User local then server/remote otherwise create a default
            val settings =
                try {
                    val local = loadFromLocal(userId)
                    Timber.v("Found local? %s", local != null)
                    // Ignore an empty saved layout (e.g. persisted before load finished) and fall
                    // through to the server / default so the home page can never end up blank.
                    local?.takeIf { it.rows.isNotEmpty() }
                } catch (ex: Exception) {
                    Timber.w(ex, "Error loading local settings")
                    // TODO show toast?
                    null
                } ?: try {
                    val remote = loadFromServer(userId)
                    Timber.v("Found remote? %s", remote != null)
                    remote?.takeIf { it.rows.isNotEmpty() }
                } catch (ex: Exception) {
                    Timber.w(ex, "Error loading remote settings")
                    null
                }
            val resolvedSettings =
                if (settings != null) {
                    Timber.v("Found settings")
                    // Resolve
                    val resolvedRows =
                        settings.rows.mapIndexed { index, config ->
                            resolve(index, config)
                        }
                    HomePageResolvedSettings(resolvedRows)
                } else {
                    createDefault(userId)
                }

            currentSettings.update { resolvedSettings }
        }

        /**
         * Resolve the settings and set them to be the current settings
         */
        suspend fun updateCurrent(settings: HomePageSettings) {
            val resolvedRows =
                settings.rows.mapIndexed { index, config ->
                    resolve(index, config)
                }
            val resolvedSettings = HomePageResolvedSettings(resolvedRows)
            currentSettings.update { resolvedSettings }
        }

        /**
         * Create a default [HomePageResolvedSettings] using the available libraries
         */
        suspend fun createDefault(userId: UUID): HomePageResolvedSettings {
            Timber.v("Creating default settings")
            val user = serverRepository.currentUser?.takeIf { it.id == userId }
            val userDto = serverRepository.currentUserDto?.takeIf { it.id == userId }
            val libraries =
                if (user != null) {
                    navDrawerService.getFilteredUserLibraries(user, userDto?.tvAccess ?: false)
                } else {
                    navDrawerService.getAllUserLibraries(userId, userDto?.tvAccess ?: false)
                }

            // Netflix-style default layout. Order: continue watching, recommended (movies/shows),
            // recently added, then taste-ranked genre rows (the genres the user actually watches, first).
            val rows = mutableListOf<HomeRowConfigDisplay>()
            var nextId = 0
            fun add(
                title: StringProvider,
                config: HomeRowConfig,
            ) {
                rows += HomeRowConfigDisplay(nextId++, title, config)
            }

            val movieLib = libraries.firstOrNull { it.collectionType == CollectionType.MOVIES }
            val showLib = libraries.firstOrNull { it.collectionType == CollectionType.TVSHOWS }

            // 1. Continue watching + next up
            add(ResStringProvider(R.string.combine_continue_next), HomeRowConfig.ContinueWatchingCombined())

            // 2. New episodes from shows the user actually watches
            if (showLib != null) {
                add(ResStringProvider(R.string.new_episodes), HomeRowConfig.NewEpisodes())
            }

            // 3. Recommended (movies then shows) - driven by the user's watch history
            movieLib?.let { add(ResArgStringProvider(R.string.suggestions_for, it.name), HomeRowConfig.Suggestions(it.itemId)) }
            showLib?.let { add(ResArgStringProvider(R.string.suggestions_for, it.name), HomeRowConfig.Suggestions(it.itemId)) }

            // 4. Watchlist / My List
            add(ResStringProvider(R.string.watchlist_title), HomeRowConfig.Watchlist())

            // 3. Recently added (movies, shows, then any other libraries / live tv)
            movieLib?.let { add(getRecentlyAddedTitle(it.name), HomeRowConfig.RecentlyAdded(it.itemId)) }
            showLib?.let { add(getRecentlyAddedTitle(it.name), HomeRowConfig.RecentlyAdded(it.itemId)) }
            libraries
                .filter { it != movieLib && it != showLib }
                .forEach {
                    if (it.collectionType == CollectionType.LIVETV) {
                        add(ResStringProvider(R.string.watch_live), HomeRowConfig.TvPrograms())
                    } else {
                        add(getRecentlyAddedTitle(it.name), HomeRowConfig.RecentlyAdded(it.itemId))
                    }
                }

            // 4. Taste-ranked genre rows: the categories the user watches most, first. Shows before
            // movies since that is the heavier usage. Each row shows unplayed titles in that genre.
            // Rank both libraries in parallel so this doesn't delay the first paint. Titles are
            // suffixed ("Comedy Shows" / "Comedy Movies") so a genre shared by both isn't ambiguous.
            val (showGenres, movieGenres) =
                coroutineScope {
                    val shows =
                        async {
                            showLib?.let { rankedGenres(userId, it.itemId, BaseItemKind.SERIES, MAX_GENRE_ROWS) }.orEmpty()
                        }
                    val movies =
                        async {
                            movieLib?.let { rankedGenres(userId, it.itemId, BaseItemKind.MOVIE, MAX_GENRE_ROWS) }.orEmpty()
                        }
                    shows.await() to movies.await()
                }
            showLib?.let { lib ->
                showGenres.forEach { (gid, gname) ->
                    val label = "$gname Shows"
                    add(StringStringProvider(label), genreRow(label, lib.itemId, BaseItemKind.SERIES, gid))
                }
            }
            movieLib?.let { lib ->
                movieGenres.forEach { (gid, gname) ->
                    val label = "$gname Movies"
                    add(StringStringProvider(label), genreRow(label, lib.itemId, BaseItemKind.MOVIE, gid))
                }
            }

            return HomePageResolvedSettings(rows)
        }

        /**
         * A single genre row of unplayed titles in [genreId], reusing the [HomeRowConfig.GetItems] engine.
         */
        private fun genreRow(
            name: String,
            parentId: UUID,
            kind: BaseItemKind,
            genreId: UUID,
        ): HomeRowConfig.GetItems =
            HomeRowConfig.GetItems(
                name = name,
                getItems =
                    GetItemsRequest(
                        parentId = parentId,
                        includeItemTypes = listOf(kind),
                        genreIds = listOf(genreId),
                        isPlayed = false,
                        recursive = true,
                        // Indexed sort (newest first) instead of RANDOM: RANDOM forces the server to
                        // shuffle the whole recursive set, which times out when many genre rows load
                        // at once. "Newest in <genre>" is cheap and reads well as a Netflix-style row.
                        sortBy = listOf(ItemSortBy.DATE_CREATED),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        // Skip the expensive server-side total count; the row only needs the first page
                        enableTotalRecordCount = false,
                    ),
            )

        /**
         * Rank the genres the user actually watches, most-played first, for a given library.
         *
         * Uses the user's played items (episodes for a series library) and tallies their genres.
         */
        private suspend fun rankedGenres(
            userId: UUID,
            parentId: UUID,
            kind: BaseItemKind,
            max: Int,
        ): List<Pair<UUID, String>> =
            try {
                val historyKind = if (kind == BaseItemKind.SERIES) BaseItemKind.EPISODE else kind
                val played =
                    GetItemsRequestHandler
                        .execute(
                            api,
                            GetItemsRequest(
                                parentId = parentId,
                                userId = userId,
                                includeItemTypes = listOf(historyKind),
                                recursive = true,
                                isPlayed = true,
                                fields = listOf(ItemFields.GENRES),
                                sortBy = listOf(ItemSortBy.DATE_PLAYED),
                                sortOrder = listOf(SortOrder.DESCENDING),
                                // Recent 150 plays are plenty to gauge taste and keep this query light
                                limit = 150,
                                enableTotalRecordCount = false,
                                imageTypeLimit = 0,
                            ),
                        ).content.items
                        .orEmpty()
                val counts = LinkedHashMap<UUID, Int>()
                val names = HashMap<UUID, String>()
                played.forEach { item ->
                    item.genreItems?.forEach { g ->
                        val gname = g.id.let { names[it] } ?: g.name
                        if (gname != null) {
                            names[g.id] = gname
                            counts[g.id] = (counts[g.id] ?: 0) + 1
                        }
                    }
                }
                counts.entries
                    .sortedByDescending { it.value }
                    .take(max)
                    .mapNotNull { e -> names[e.key]?.let { e.key to it } }
            } catch (ex: Exception) {
                Timber.w(ex, "Could not rank genres for %s", parentId)
                emptyList()
            }

        /**
         * Create home page settings from the user's web UI home page settings
         */
        suspend fun parseFromWebConfig(userId: UUID): HomePageResolvedSettings? {
            val customPrefs =
                displayPreferencesService
                    .getDisplayPreferences(
                        displayPreferencesId = "usersettings",
                        userId = userId,
                        client = "emby",
                    ).customPrefs
            val userDto by api.userApi.getUserById(userId)
            val config = userDto.configuration ?: DefaultUserConfiguration
            val libraries =
                api.userViewsApi
                    .getUserViews(userId = userId)
                    .content.items
                    .filter {
                        it.collectionType in supportedHomeCollectionTypes &&
                            it.id !in config.latestItemsExcludes
                    }

            return if (customPrefs.isNotEmpty()) {
                var id = 0
                val rowConfigs =
                    (0..9)
                        .mapNotNull { idx ->
                            val sectionType =
                                HomeSectionType.fromString(customPrefs["homesection$idx"]?.lowercase())
                            Timber.v(
                                "sectionType=$sectionType, %s",
                                customPrefs["homesection$idx"]?.lowercase(),
                            )
                            val config =
                                when (sectionType) {
                                    HomeSectionType.ACTIVE_RECORDINGS -> {
                                        HomeRowConfigDisplay(
                                            id = id++,
                                            title = ResStringProvider(R.string.active_recordings),
                                            config = HomeRowConfig.Recordings(),
                                        )
                                    }

                                    HomeSectionType.RESUME -> {
                                        HomeRowConfigDisplay(
                                            id = id++,
                                            title = ResStringProvider(R.string.continue_watching),
                                            config = HomeRowConfig.ContinueWatching(),
                                        )
                                    }

                                    HomeSectionType.NEXT_UP -> {
                                        HomeRowConfigDisplay(
                                            id = id++,
                                            title = ResStringProvider(R.string.next_up),
                                            config = HomeRowConfig.NextUp(),
                                        )
                                    }

                                    HomeSectionType.LIVE_TV -> {
                                        if (userDto.tvAccess) {
                                            HomeRowConfigDisplay(
                                                id = id++,
                                                title = ResStringProvider(R.string.watch_live),
                                                config = HomeRowConfig.TvPrograms(),
                                            )
                                        } else {
                                            null
                                        }
                                    }

                                    HomeSectionType.LATEST_MEDIA -> {
                                        // Handled below
                                        null
                                    }

                                    // Unsupported
                                    HomeSectionType.RESUME_AUDIO,
                                    HomeSectionType.RESUME_BOOK,
                                    -> {
                                        null
                                    }

                                    HomeSectionType.SMALL_LIBRARY_TILES,
                                    HomeSectionType.LIBRARY_BUTTONS,
                                    HomeSectionType.NONE,
                                    null,
                                    -> {
                                        null
                                    }
                                }
                            if (sectionType == HomeSectionType.LATEST_MEDIA) {
                                libraries.map {
                                    HomeRowConfigDisplay(
                                        id = id++,
                                        title =
                                            ResArgStringProvider(
                                                R.string.recently_added_in,
                                                it.name ?: "",
                                            ),
                                        config = HomeRowConfig.RecentlyAdded(it.id),
                                    )
                                }
                            } else if (config != null) {
                                listOf(config)
                            } else {
                                null
                            }
                        }.flatten()
                HomePageResolvedSettings(rowConfigs)
            } else {
                null
            }
        }

        /**
         * Converts a [HomeRowConfig] into [HomeRowConfigDisplay] for UI purposes
         */
        suspend fun resolve(
            id: Int,
            config: HomeRowConfig,
        ): HomeRowConfigDisplay =
            when (config) {
                is HomeRowConfig.ByParent -> {
                    val name = getItemName(null, config.parentId)
                    HomeRowConfigDisplay(
                        id,
                        name,
                        config,
                    )
                }

                is HomeRowConfig.ContinueWatching -> {
                    HomeRowConfigDisplay(
                        id,
                        ResStringProvider(R.string.continue_watching),
                        config,
                    )
                }

                is HomeRowConfig.ContinueWatchingCombined -> {
                    HomeRowConfigDisplay(
                        id,
                        ResStringProvider(R.string.combine_continue_next),
                        config,
                    )
                }

                is HomeRowConfig.Genres -> {
                    val title = getItemName(R.string.genres_in, config.parentId)
                    HomeRowConfigDisplay(
                        id,
                        title,
                        config,
                    )
                }

                is HomeRowConfig.Studios -> {
                    val title = getItemName(R.string.studios_in, config.parentId)
                    HomeRowConfigDisplay(
                        id,
                        title,
                        config,
                    )
                }

                is HomeRowConfig.GetItems -> {
                    HomeRowConfigDisplay(id, StringStringProvider(config.name), config)
                }

                is HomeRowConfig.NextUp -> {
                    HomeRowConfigDisplay(
                        id,
                        ResStringProvider(R.string.next_up),
                        config,
                    )
                }

                is HomeRowConfig.RecentlyAdded -> {
                    val title = getItemName(R.string.recently_added_in, config.parentId)
                    HomeRowConfigDisplay(
                        id,
                        title,
                        config,
                    )
                }

                is HomeRowConfig.RecentlyReleased -> {
                    val title = getItemName(R.string.recently_released_in, config.parentId)
                    HomeRowConfigDisplay(
                        id,
                        title,
                        config,
                    )
                }

                is HomeRowConfig.Favorite -> {
                    val name =
                        ResProviderStringProvider(
                            R.string.favorite_items_title,
                            ResStringProvider(favoriteOptions[config.kind]!!),
                        )
                    HomeRowConfigDisplay(id, name, config)
                }

                is HomeRowConfig.Recordings -> {
                    HomeRowConfigDisplay(
                        id = id,
                        title = ResStringProvider(R.string.active_recordings),
                        config,
                    )
                }

                is HomeRowConfig.TvPrograms -> {
                    HomeRowConfigDisplay(
                        id = id,
                        title = ResStringProvider(R.string.watch_live),
                        config,
                    )
                }

                is HomeRowConfig.TvChannels -> {
                    HomeRowConfigDisplay(
                        id = id,
                        title = ResStringProvider(R.string.channels),
                        config,
                    )
                }

                is HomeRowConfig.Suggestions -> {
                    val title = getItemName(R.string.suggestions_for, config.parentId)
                    HomeRowConfigDisplay(
                        id = id,
                        title = title,
                        config,
                    )
                }

                is HomeRowConfig.NewEpisodes -> {
                    HomeRowConfigDisplay(id, ResStringProvider(R.string.new_episodes), config)
                }

                is HomeRowConfig.Watchlist -> {
                    HomeRowConfigDisplay(id, ResStringProvider(R.string.watchlist_title), config)
                }
            }

        private suspend fun getItemName(
            @StringRes stringRes: Int?,
            itemId: UUID,
            default: StringProvider = StringStringProvider(""),
        ): StringProvider =
            try {
                api.userLibraryApi
                    .getItem(
                        userId = serverRepository.currentUser?.id,
                        itemId = itemId,
                    ).content.name
                    ?.let {
                        if (stringRes == null) {
                            StringStringProvider(it)
                        } else {
                            ResArgStringProvider(stringRes, it)
                        }
                    } ?: default
            } catch (ex: Exception) {
                Timber.e(ex, "Could not get name for %s", itemId)
                ResStringProvider(R.string.unknown)
            }

        /**
         * Fetch the data from the server for a given [HomeRowConfig]
         */
        suspend fun fetchDataForRow(
            row: HomeRowConfig,
            scope: CoroutineScope,
            prefs: HomePagePreferences,
            userDto: UserDto,
            libraries: List<Library>,
            limit: Int = prefs.maxItemsPerRow,
            isRefresh: Boolean,
            usePaging: Boolean = false,
        ): HomeRowLoadingState =
            filterUnreleased(
                prefs,
                when (row) {
                is HomeRowConfig.ContinueWatching -> {
                    val resume =
                        latestNextUpService.getResume(
                            userDto.id,
                            limit,
                            true,
                            row.viewOptions.useSeries,
                        )

                    Success(
                        title = ResStringProvider(R.string.continue_watching),
                        items = resume,
                        viewOptions = row.viewOptions,
                        rowType = row,
                        showViewMore = resume.size >= limit,
                    )
                }

                is HomeRowConfig.NextUp -> {
                    val nextUp =
                        latestNextUpService.getNextUp(
                            userDto.id,
                            limit,
                            prefs.enableRewatchingNextUp,
                            false,
                            prefs.maxDaysNextUp,
                            row.viewOptions.useSeries,
                        )

                    Success(
                        title = ResStringProvider(R.string.next_up),
                        items = nextUp,
                        viewOptions = row.viewOptions,
                        rowType = row,
                        showViewMore = nextUp.size >= limit,
                    )
                }

                is HomeRowConfig.ContinueWatchingCombined -> {
                    val resume =
                        latestNextUpService.getResume(
                            userDto.id,
                            limit,
                            true,
                            row.viewOptions.useSeries,
                        )
                    val nextUp =
                        latestNextUpService.getNextUp(
                            userDto.id,
                            limit,
                            prefs.enableRewatchingNextUp,
                            false,
                            prefs.maxDaysNextUp,
                            row.viewOptions.useSeries,
                        )
                    val combined = latestNextUpService.buildCombined(resume, nextUp)

                    Success(
                        title = ResStringProvider(R.string.continue_watching),
                        items = combined.take(limit),
                        viewOptions = row.viewOptions,
                        rowType = row,
                        showViewMore = combined.size >= limit,
                    )
                }

                is HomeRowConfig.Genres -> {
                    val request =
                        GetGenresRequest(
                            parentId = row.parentId,
                            userId = userDto.id,
                            limit = limit,
                        )
                    val items =
                        GetGenresRequestHandler
                            .execute(api, request)
                            .content.items
                    val genreIds = items.map { it.id }
                    val genreImages =
                        getGenreImageMap(
                            api = api,
                            userId = serverRepository.currentUser?.id,
                            scope = scope,
                            imageUrlService = imageUrlService,
                            genres = genreIds,
                            parentId = row.parentId,
                            includeItemTypes = null,
                            cardWidthPx = null,
                            useCache = isRefresh,
                        )
                    val library =
                        libraries
                            .firstOrNull { it.itemId == row.parentId }

                    val title =
                        library?.name?.let { ResArgStringProvider(R.string.genres_in, it) }
                            ?: ResStringProvider(R.string.genres)
                    val genres =
                        items.map {
                            BaseItem(
                                it,
                                false,
                                genreImages[it.id],
                                createGenreDestination(
                                    genreId = it.id,
                                    genreName = it.name ?: "",
                                    parentId = row.parentId,
                                    parentName = library?.name,
                                    includeItemTypes =
                                        library?.collectionType?.let {
                                            getTypeFor(it)?.let {
                                                listOf(it)
                                            }
                                        },
                                    collectionType =
                                        library?.collectionType
                                            ?: CollectionType.UNKNOWN,
                                ),
                            )
                        }

                    Success(
                        title,
                        genres,
                        viewOptions = row.viewOptions,
                        rowType = row,
                        showViewMore = genres.size >= limit,
                    )
                }

                is HomeRowConfig.Studios -> {
                    val request =
                        GetStudiosRequest(
                            parentId = row.parentId,
                            userId = userDto.id,
                            limit = limit,
                            includeItemTypes = listOf(BaseItemKind.SERIES),
                        )
                    val items =
                        GetStudiosRequestHandler
                            .execute(api, request)
                            .content.items
                    val library =
                        libraries
                            .firstOrNull { it.itemId == row.parentId }
                    val title =
                        library?.name?.let { ResArgStringProvider(R.string.studios_in, it) }
                            ?: ResStringProvider(R.string.studios)
                    val studios =
                        items.map {
                            val imageUrl =
                                imageUrlService.getItemImageUrl(
                                    itemId = it.id,
                                    imageType = ImageType.THUMB,
                                )
                            BaseItem(
                                it,
                                false,
                                imageUrl,
                                createStudioDestination(
                                    studioId = it.id,
                                    name = it.name ?: "",
                                    parentId = row.parentId,
                                    parentName = library?.name,
                                    includeItemTypes =
                                        library?.collectionType?.let {
                                            getTypeFor(it)?.let {
                                                listOf(it)
                                            }
                                        },
                                ),
                            )
                        }

                    Success(
                        title,
                        studios,
                        viewOptions = row.viewOptions,
                        showViewMore = studios.size >= limit,
                    )
                }

                is HomeRowConfig.RecentlyAdded -> {
                    val library = libraries.firstOrNull { it.itemId == row.parentId }
                    val title = getRecentlyAddedTitle(library?.name)
                    val request =
                        GetLatestMediaRequest(
                            fields = library.itemFields,
                            imageTypeLimit = 1,
                            parentId = row.parentId,
                            groupItems = true,
                            limit = limit,
                            isPlayed = null, // Server will handle user's preference
                        )
                    val latest =
                        api.userLibraryApi
                            .getLatestMedia(request)
                            .content
                            .map { BaseItem(it, row.viewOptions.useSeries) }
                            .let {
                                Success(
                                    title,
                                    it,
                                    row.viewOptions,
                                    rowType = row,
                                    showViewMore = it.size >= limit,
                                )
                            }
                    latest
                }

                is HomeRowConfig.RecentlyReleased -> {
                    val library = libraries.firstOrNull { it.itemId == row.parentId }
                    val title =
                        library?.name?.let {
                            ResArgStringProvider(R.string.recently_released_in, it)
                        } ?: ResStringProvider(R.string.recently_released)
                    val request =
                        GetItemsRequest(
                            parentId = row.parentId,
                            limit = limit,
                            sortBy =
                                listOf(
                                    ItemSortBy.PREMIERE_DATE,
                                    ItemSortBy.SERIES_SORT_NAME,
                                    ItemSortBy.AIRED_EPISODE_ORDER,
                                ),
                            sortOrder =
                                listOf(
                                    SortOrder.DESCENDING,
                                    SortOrder.ASCENDING,
                                    SortOrder.DESCENDING,
                                ),
                            fields = library.itemFields,
                            recursive = true,
                            maxPremiereDate = LocalDateTime.now(),
                            isUnaired = false,
                        )
                    if (usePaging) {
                        ApiRequestPager(
                            api,
                            request,
                            GetItemsRequestHandler,
                            scope,
                            useSeriesForPrimary = row.viewOptions.useSeries,
                        ).init()
                    } else {
                        GetItemsRequestHandler
                            .execute(api, request)
                            .content.items
                            .map { BaseItem.from(it, api, row.viewOptions.useSeries) }
                    }.let {
                        Success(
                            title,
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.ByParent -> {
                    val library = libraries.firstOrNull { it.itemId == row.parentId }
                    val request =
                        GetItemsRequest(
                            userId = userDto.id,
                            parentId = row.parentId,
                            recursive = row.recursive,
                            sortBy =
                                row.sort?.let {
                                    buildList {
                                        if (it.sort == ItemSortBy.RANDOM) {
                                            add(ItemSortBy.SORT_NAME)
                                            add(ItemSortBy.RANDOM)
                                        } else {
                                            add(it.sort)
                                            if (it.sort != ItemSortBy.SORT_NAME) {
                                                add(ItemSortBy.SORT_NAME)
                                            }
                                        }
                                    }
                                },
                            sortOrder =
                                row.sort?.let {
                                    buildList {
                                        if (it.sort == ItemSortBy.RANDOM) {
                                            add(SortOrder.ASCENDING)
                                            add(it.direction)
                                        } else {
                                            add(it.direction)
                                            if (it.sort != ItemSortBy.SORT_NAME) {
                                                add(SortOrder.ASCENDING)
                                            }
                                        }
                                    }
                                },
                            limit = limit,
                            fields = library.itemFields,
                        )

                    // Not using getItemName because we want to throw the 404
                    val title =
                        api.userLibraryApi
                            .getItem(
                                userId = serverRepository.currentUser?.id,
                                itemId = row.parentId,
                            ).content.name
                            ?.let { StringStringProvider(it) }
                            ?: ResStringProvider(R.string.collection)
                    if (usePaging) {
                        ApiRequestPager(
                            api,
                            request,
                            GetItemsRequestHandler,
                            scope,
                            useSeriesForPrimary = row.viewOptions.useSeries,
                        ).init()
                    } else {
                        GetItemsRequestHandler
                            .execute(api, request)
                            .content.items
                            .map { BaseItem(it, row.viewOptions.useSeries) }
                    }.let {
                        Success(
                            title,
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.GetItems -> {
                    val request =
                        row.getItems.let {
                            if (it.limit == null) {
                                it.copy(
                                    userId = userDto.id,
                                    limit = limit,
                                )
                            } else {
                                it.copy(
                                    userId = userDto.id,
                                )
                            }
                        }
                    if (usePaging) {
                        ApiRequestPager(
                            api,
                            request,
                            GetItemsRequestHandler,
                            scope,
                            useSeriesForPrimary = row.viewOptions.useSeries,
                        ).init()
                    } else {
                        GetItemsRequestHandler
                            .execute(api, request)
                            .content.items
                            .map { BaseItem(it, row.viewOptions.useSeries) }
                    }.let {
                        Success(
                            StringStringProvider(row.name),
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.Favorite -> {
                    val title =
                        ResProviderStringProvider(
                            R.string.favorite_items_title,
                            ResStringProvider(favoriteOptions[row.kind]!!),
                        )
                    if (row.kind == BaseItemKind.PERSON) {
                        val request =
                            GetPersonsRequest(
                                userId = userDto.id,
                                limit = limit,
                                fields = HomeItemFields,
                                isFavorite = true,
                                enableImages = true,
                                enableImageTypes = listOf(ImageType.PRIMARY),
                            )
                        GetPersonsHandler
                            .execute(api, request)
                            .content.items
                            .map { BaseItem(it, true) }
                            .let {
                                Success(
                                    title,
                                    it,
                                    row.viewOptions,
                                    showViewMore = it.size >= limit,
                                )
                            }
                    } else {
                        val fields =
                            if (row.kind == BaseItemKind.BOX_SET) {
                                HomeItemFieldsBoxSets
                            } else {
                                HomeItemFields
                            }
                        val request =
                            GetItemsRequest(
                                userId = userDto.id,
                                recursive = true,
                                limit = limit,
                                fields = fields,
                                includeItemTypes = listOf(row.kind),
                                isFavorite = true,
                            )
                        if (usePaging) {
                            ApiRequestPager(
                                api,
                                request,
                                GetItemsRequestHandler,
                                scope,
                                useSeriesForPrimary = row.viewOptions.useSeries,
                            ).init()
                        } else {
                            GetItemsRequestHandler
                                .execute(api, request)
                                .content.items
                                .map { BaseItem(it, row.viewOptions.useSeries) }
                        }.let {
                            Success(
                                title,
                                it,
                                row.viewOptions,
                                rowType = row,
                                showViewMore = it.size >= limit,
                            )
                        }
                    }
                }

                is HomeRowConfig.Recordings -> {
                    val request =
                        GetRecordingsRequest(
                            userId = userDto.id,
                            isInProgress = true,
                            fields = HomeItemFields,
                            limit = limit,
                            enableImages = true,
                            enableUserData = true,
                            enableTotalRecordCount = false,
                        )
                    if (usePaging) {
                        ApiRequestPager(
                            api,
                            request,
                            GetRecordingsRequestHandler,
                            scope,
                            useSeriesForPrimary = row.viewOptions.useSeries,
                        ).init()
                    } else {
                        api.liveTvApi
                            .getRecordings(request)
                            .content.items
                            .map { BaseItem(it, row.viewOptions.useSeries) }
                    }.let {
                        Success(
                            ResStringProvider(R.string.active_recordings),
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.TvPrograms -> {
                    val request =
                        GetProgramsDto(
                            userId = userDto.id,
                            fields = ProgramItemFields,
                            limit = limit,
                            enableUserData = true,
                            enableImages = true,
                            enableImageTypes = listOf(ImageType.PRIMARY, ImageType.LOGO),
                            imageTypeLimit = 1,
                            isAiring = true,
                            minEndDate = DateTime.now().plusMinutes(1),
                        )
                    if (usePaging) {
                        ApiRequestPager(
                            api,
                            request,
                            GetProgramsDtoHandler,
                            scope,
                            useSeriesForPrimary = row.viewOptions.useSeries,
                        ).init()
                    } else {
                        api.liveTvApi
                            .getPrograms(request)
                            .content.items
                            .map { BaseItem(it, row.viewOptions.useSeries) }
                    }.let {
                        Success(
                            ResStringProvider(R.string.watch_live),
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.TvChannels -> {
                    val request =
                        GetLiveTvChannelsRequest(
                            userId = userDto.id,
                            fields = HomeItemFields,
                            limit = limit,
                            enableImages = true,
                        )
                    if (usePaging) {
                        ApiRequestPager(
                            api,
                            request,
                            GetLiveTvChannelsRequestHandler,
                            scope,
                            useSeriesForPrimary = row.viewOptions.useSeries,
                        ).init()
                    } else {
                        api.liveTvApi
                            .getLiveTvChannels(request)
                            .toBaseItems(api, row.viewOptions.useSeries)
                    }.let {
                        Success(
                            ResStringProvider(R.string.channels),
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.Suggestions -> {
                    val library =
                        api.userLibraryApi
                            .getItem(itemId = row.parentId)
                            .content
                    val title = ResArgStringProvider(R.string.suggestions_for, library.name ?: "")
                    val itemKind = SuggestionsWorker.getTypeForCollection(library.collectionType)
                    if (itemKind != null) {
                        // Compute recommendations inline from the user's most-watched genres rather
                        // than the background SuggestionsWorker. The worker is async + cached and on a
                        // cold cache left the row stuck on "Loading" (and, by holding a load slot,
                        // starved the other rows and the settings editor's preview). This is a single
                        // fast query: a random mix of unplayed titles across the genres they watch most.
                        val topGenres =
                            rankedGenres(userDto.id, row.parentId, itemKind, 5).map { it.first }
                        val items =
                            if (topGenres.isEmpty()) {
                                emptyList()
                            } else {
                                GetItemsRequestHandler
                                    .execute(
                                        api,
                                        GetItemsRequest(
                                            parentId = row.parentId,
                                            userId = userDto.id,
                                            includeItemTypes = listOf(itemKind),
                                            genreIds = topGenres,
                                            isPlayed = false,
                                            recursive = true,
                                            sortBy = listOf(ItemSortBy.RANDOM),
                                            limit = limit,
                                            enableTotalRecordCount = false,
                                        ),
                                    ).content.items
                                    .map { BaseItem(it, row.viewOptions.useSeries) }
                            }
                        Success(
                            title,
                            items,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = items.size >= limit,
                        )
                    } else {
                        HomeRowLoadingState.Error(
                            title = title,
                            message = "Unsupported type ${library.collectionType}",
                        )
                    }
                }

                is HomeRowConfig.NewEpisodes -> {
                    val title = ResStringProvider(R.string.new_episodes)
                    // "Next up" for the shows you actually follow (the original, correct source) - but
                    // only keep episodes that actually aired recently (within ~6 months). This stops
                    // old episodes of long-running shows (e.g. restarting South Park at season 10) from
                    // showing up as "new". Filter on the EPISODE's air date, not the series', so a
                    // genuinely new episode of an old show still counts. Fetch extra since the filter
                    // trims the list.
                    val cutoff = LocalDateTime.now().minusMonths(6)
                    val items =
                        latestNextUpService
                            .getNextUp(
                                userDto.id,
                                maxOf(limit * 6, 60),
                                prefs.enableRewatchingNextUp,
                                false,
                                prefs.maxDaysNextUp,
                                row.viewOptions.useSeries,
                            ).filter { item ->
                                item?.data?.premiereDate?.let { it >= cutoff } ?: false
                            }
                            // One episode per show (its next-up episode), then on to the next show.
                            .distinctBy { it?.data?.seriesId }
                            .take(limit)
                    Success(
                        title,
                        items,
                        row.viewOptions,
                        rowType = row,
                        showViewMore = items.size >= limit,
                    )
                }

                is HomeRowConfig.Watchlist -> {
                    val title = ResStringProvider(R.string.watchlist_title)
                    val items =
                        watchlistService
                            .items(userDto.id, HomeItemFields)
                            // Hide-but-keep: watched items drop out of view but stay on the list
                            .filter { it.userData?.played != true }
                            .map { BaseItem(it, row.viewOptions.useSeries) }
                    Success(
                        title,
                        items,
                        row.viewOptions,
                        rowType = row,
                        showViewMore = items.size >= limit,
                    )
                }
                },
            )

        /**
         * Client-side safety net: unless the user has opted in to seeing unreleased content, drop
         * items whose premiere/air date is still in the future from any row. Backs up the
         * server-side (Jellyfin) fix so not-yet-released titles never leak into the home view.
         */
        private fun filterUnreleased(
            prefs: HomePagePreferences,
            state: HomeRowLoadingState,
        ): HomeRowLoadingState =
            if (prefs.showUnreleased || state !is Success) {
                state
            } else {
                val now = LocalDateTime.now()
                state.copy(
                    items =
                        state.items.filterNot { item ->
                            item?.data?.premiereDate?.let { it > now } ?: false
                        },
                )
            }

        companion object {
            const val CUSTOM_PREF_ID = "home_settings"

            /** Max taste-ranked genre rows to generate per library in the default layout */
            const val MAX_GENRE_ROWS = 6
        }
    }

/**
 * A [HomeRowConfig] with a resolved ID and title so it is usable in the UI
 */
data class HomeRowConfigDisplay(
    val id: Int,
    val title: StringProvider,
    val config: HomeRowConfig,
)

/**
 * List of resolved [HomeRowConfig]s as [HomeRowConfigDisplay]s
 *
 * @see HomePageSettings
 */
data class HomePageResolvedSettings(
    val rows: List<HomeRowConfigDisplay>,
) {
    companion object {
        val EMPTY = HomePageResolvedSettings(listOf())
    }
}

// https://github.com/jellyfin/jellyfin/blob/v10.11.6/src/Jellyfin.Database/Jellyfin.Database.Implementations/Enums/HomeSectionType.cs
enum class HomeSectionType(
    val serialName: String,
) {
    NONE("none"),
    SMALL_LIBRARY_TILES("smalllibrarytitles"),
    LIBRARY_BUTTONS("librarybuttons"),
    ACTIVE_RECORDINGS("activerecordings"),
    RESUME("resume"),
    RESUME_AUDIO("resumeaudio"),
    LATEST_MEDIA("latestmedia"),
    NEXT_UP("nextup"),
    LIVE_TV("livetv"),
    RESUME_BOOK("resumebook"),
    ;

    companion object {
        fun fromString(homeKey: String?) = homeKey?.let { entries.firstOrNull { it.serialName == homeKey } }
    }
}

class UnsupportedHomeSettingsVersionException(
    val unsupportedVersion: Int?,
    val maxSupportedVersion: Int = SUPPORTED_HOME_PAGE_SETTINGS_VERSION,
) : Exception("Unsupported version $unsupportedVersion, max supported is $maxSupportedVersion")

fun getRecentlyAddedTitle(name: String?): StringProvider =
    name?.let { ResArgStringProvider(R.string.recently_added_in, it) }
        ?: ResStringProvider(R.string.recently_added)

private val Library?.itemFields: List<ItemFields>
    get() =
        if (this?.type == BaseItemKind.COLLECTION_FOLDER && this.collectionType == CollectionType.BOXSETS) {
            // Get child count to show in the header
            HomeItemFieldsBoxSets
        } else {
            HomeItemFields
        }

private val HomeItemFieldsBoxSets get() = HomeItemFields + listOf(ItemFields.CHILD_COUNT)
