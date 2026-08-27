package com.github.damontecres.wholphin.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.HomePagePreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.DatePlayedService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.HomePageResolvedSettings
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.LatestNextUpService
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.MediaReportService
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.WatchlistService
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.services.tvAccess
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.util.EmptyStringProvider
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserDto
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        val navigationManager: NavigationManager,
        val serverRepository: ServerRepository,
        val mediaReportService: MediaReportService,
        private val navDrawerService: NavDrawerService,
        private val homeSettingsService: HomeSettingsService,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val datePlayedService: DatePlayedService,
        private val backdropService: BackdropService,
        private val userPreferencesService: UserPreferencesService,
        private val mediaManagementService: MediaManagementService,
        private val latestNextUpService: LatestNextUpService,
        private val watchlistService: WatchlistService,
    ) : ViewModel() {
        private val _state = MutableStateFlow(HomeState.EMPTY)
        val state: StateFlow<HomeState> = _state

        init {
            datePlayedService.invalidateAll()
//            init()
        }

        /** Context captured at init so individual rows can be fetched lazily as they scroll in */
        private data class LoadContext(
            val prefs: HomePagePreferences,
            val userDto: UserDto,
            val libraries: List<Library>,
        )

        private var loadContext: LoadContext? = null
        private val requestedRows = mutableSetOf<Int>()
        private val rowSemaphore = Semaphore(4)

        fun init() {
            viewModelScope.launchIO {
                Timber.d("init HomeViewModel")
                try {
                    val preferences = userPreferencesService.getCurrent()
                    val prefs = preferences.appPreferences.homePagePreferences

                    serverRepository.currentUserDto?.let { userDto ->
                        val libraries =
                            navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)
                        val settings =
                            homeSettingsService.currentSettings.first { it != HomePageResolvedSettings.EMPTY }
                        loadContext = LoadContext(prefs, userDto, libraries)

                        val prev = state.value
                        val alreadyLoaded =
                            prev.loadingState == LoadingState.Success && prev.settings == settings
                        if (!alreadyLoaded) {
                            // Rows are fetched lazily by the UI (loadRow) as they scroll into view, so
                            // only the first screen loads up front and the page feels instant.
                            requestedRows.clear()
                            _state.update {
                                it.copy(
                                    loadingState = LoadingState.Success,
                                    refreshState = LoadingState.Success,
                                    settings = settings,
                                    homeRows =
                                        List(settings.rows.size) {
                                            HomeRowLoadingState.Pending(EmptyStringProvider)
                                        },
                                    loadGeneration = prev.loadGeneration + 1,
                                )
                            }
                        }
                        Timber.d("Home page settings ready")
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception during home page loading")
                    if (state.value.loadingState == LoadingState.Success) {
                        showToast(context, "Error refreshing home: ${ex.localizedMessage}")
                        _state.update { it.copy(refreshState = LoadingState.Error(ex)) }
                    } else {
                        _state.update {
                            it.copy(loadingState = LoadingState.Error(ex))
                        }
                    }
                }
            }
        }

        /**
         * Fetch a single row's data on demand - called by the UI as a row scrolls into view. Guarded
         * so each row is only fetched once (per generation), and bounded so a fast scroll can't fire
         * dozens of requests at once.
         */
        fun loadRow(index: Int) {
            val ctx = loadContext ?: return
            val row = state.value.settings.rows.getOrNull(index) ?: return
            if (!requestedRows.add(index)) return
            viewModelScope.launch(WholphinDispatchers.IO) {
                rowSemaphore.withPermit {
                    val result =
                        try {
                            homeSettingsService.fetchDataForRow(
                                row = row.config,
                                scope = viewModelScope,
                                prefs = ctx.prefs,
                                userDto = ctx.userDto,
                                libraries = ctx.libraries,
                                limit = ctx.prefs.maxItemsPerRow,
                                isRefresh = false,
                            )
                        } catch (ex: InvalidStatusException) {
                            if (ex.status == 404) {
                                HomeRowLoadingState.Success(row.title, emptyList())
                            } else {
                                Timber.e(ex, "Error %s on row %s", ex.status, row)
                                HomeRowLoadingState.Error(row.title, exception = ex)
                            }
                        } catch (ex: Exception) {
                            Timber.e(ex, "Error on row %s", row)
                            HomeRowLoadingState.Error(row.title, exception = ex)
                        }
                    _state.update { st ->
                        if (index < st.homeRows.size) {
                            st.copy(homeRows = st.homeRows.toMutableList().apply { set(index, result) })
                        } else {
                            st
                        }
                    }
                }
            }
        }

        /**
         * Force on-screen rows to reload (after a watched/favourite change). Clears the fetched-row
         * guard and bumps the generation so the UI re-requests whatever is currently visible.
         */
        private fun reload() {
            requestedRows.clear()
            _state.update {
                it.copy(
                    homeRows = List(it.settings.rows.size) { HomeRowLoadingState.Pending(EmptyStringProvider) },
                    loadGeneration = it.loadGeneration + 1,
                )
            }
        }

        fun setWatched(
            itemId: UUID,
            played: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favoriteWatchManager.setWatched(itemId, played)
            withContext(WholphinDispatchers.Main) {
                reload()
            }
        }

        fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favoriteWatchManager.setFavorite(itemId, favorite)
            withContext(WholphinDispatchers.Main) {
                reload()
            }
        }

        fun addToWatchlist(itemId: UUID) =
            viewModelScope.launch(ExceptionHandler(autoToast = true) + WholphinDispatchers.IO) {
                watchlistService.add(itemId)
                withContext(WholphinDispatchers.Main) { reload() }
            }

        fun removeFromWatchlist(itemId: UUID) =
            viewModelScope.launch(ExceptionHandler(autoToast = true) + WholphinDispatchers.IO) {
                watchlistService.remove(itemId)
                withContext(WholphinDispatchers.Main) { reload() }
            }

        fun updateBackdrop(item: BaseItem) {
            viewModelScope.launchIO {
                backdropService.submit(item)
            }
        }

        fun deleteItem(
            position: RowColumn,
            item: BaseItem,
        ) {
            deleteItem(context, mediaManagementService, item) {
                viewModelScope.launchDefault {
                    val row = state.value.homeRows.getOrNull(position.row)
                    if (row is HomeRowLoadingState.Success) {
                        _state.update {
                            val newRow =
                                row.items.toMutableList().apply {
                                    removeAt(position.column)
                                }
                            it.copy(
                                homeRows =
                                    it.homeRows.toMutableList().apply {
                                        set(position.row, row.copy(items = newRow))
                                    },
                            )
                        }
                    }
                }
            }
        }

        fun canDelete(
            item: BaseItem,
            appPreferences: AppPreferences,
        ): Boolean = mediaManagementService.canDelete(item, appPreferences)

        fun removeFromNextUp(item: BaseItem) {
            if (item.type == BaseItemKind.EPISODE) {
                viewModelScope.launchDefault {
                    serverRepository.currentUser?.id?.let { userId ->
                        latestNextUpService.removeFromNextUp(userId, item)
                        reload()
                    }
                }
            } else {
                Timber.w("Item is not an episode %s", item.id)
            }
        }
    }

data class HomeState(
    val loadingState: LoadingState,
    val refreshState: LoadingState,
    val homeRows: List<HomeRowLoadingState>,
    val settings: HomePageResolvedSettings,
    val loadGeneration: Int = 0,
) {
    companion object {
        val EMPTY =
            HomeState(
                LoadingState.Pending,
                LoadingState.Pending,
                listOf(),
                HomePageResolvedSettings.EMPTY,
            )
    }
}

/**
 * Whether a row is a "is watching" type
 */
private fun isWatchingRow(row: HomeRowConfig) =
    row is HomeRowConfig.ContinueWatching ||
        row is HomeRowConfig.NextUp ||
        row is HomeRowConfig.ContinueWatchingCombined
