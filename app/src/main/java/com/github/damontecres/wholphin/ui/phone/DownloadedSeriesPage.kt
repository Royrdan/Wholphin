package com.github.damontecres.wholphin.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.cards.BannerCard
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.SeriesName
import com.github.damontecres.wholphin.ui.components.TabDetails
import com.github.damontecres.wholphin.ui.components.TabRow
import com.github.damontecres.wholphin.ui.detail.series.FocusedEpisodeHeader
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.isPlayKeyUp
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import kotlinx.coroutines.flow.update

/**
 * The series page for a downloaded show: same shape as the normal series overview (season tabs,
 * focused-episode header, episode banner row, action buttons) but fed only from the downloads
 * database. Only seasons/episodes with a finished download appear; cards show the local thumbnail,
 * the watched tick and the progress bar from the local row. Uses the TV widgets so D-pad works and
 * the phone touch shim turns taps into clicks.
 */
@Composable
fun DownloadedSeriesPage(
    preferences: UserPreferences,
    destination: Destination.DownloadedSeries,
    modifier: Modifier = Modifier,
    viewModel: DownloadedSeriesViewModel =
        hiltViewModel<DownloadedSeriesViewModel, DownloadedSeriesViewModel.Factory>(
            creationCallback = { it.create(destination.seriesId) },
        ),
) {
    val state by viewModel.state.collectAsState()
    val position by viewModel.position.collectAsState()
    var deleteRequest by remember { mutableStateOf<DownloadedEpisode?>(null) }
    var deleteShowRequest by remember { mutableStateOf(false) }

    // The show disappears (last episode deleted) -> leave the page.
    LaunchedEffect(state.isEmpty) {
        if (state.isEmpty) viewModel.goBack()
    }

    val seasons = state.seasons
    val seasonIndex = position.seasonIndex.coerceIn(0, (seasons.size - 1).coerceAtLeast(0))
    val season = seasons.getOrNull(seasonIndex)
    val episodes = season?.episodes.orEmpty()
    val episodeIndex = position.episodeIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
    val focusedEpisode = episodes.getOrNull(episodeIndex)

    val tabFocusRequesters = remember(seasons.size) { List(seasons.size) { FocusRequester() } }
    val contentFocusRequesters = remember(seasons.size) { List(seasons.size) { FocusRequester() } }
    val rowFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }
    var requestFocusAfterSeason by remember { mutableStateOf(false) }

    val tabs =
        seasons.mapIndexed { index, s ->
            TabDetails(
                title = StringStringProvider(s.title),
                tabFocusRequester = tabFocusRequesters[index],
                contentFocusRequester = contentFocusRequesters[index],
            )
        }

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 16.dp)
                    .focusGroup()
                    .verticalScroll(rememberScrollState()),
        ) {
            if (tabs.isNotEmpty()) {
                TabRow(
                    selectedTabIndex = seasonIndex,
                    tabs = tabs,
                    onClick = { index ->
                        if (index != seasonIndex) {
                            viewModel.position.update { DownloadedSeriesPosition(index, 0) }
                            requestFocusAfterSeason = true
                        }
                    },
                    modifier = Modifier.padding(end = 16.dp).padding(bottom = 4.dp).fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = HeaderUtils.startPadding)) {
                SeriesName(state.name, modifier = Modifier.weight(1f))
                Text(
                    text = "Downloaded",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .padding(end = 24.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            FocusedEpisodeHeader(
                preferences = preferences,
                ep = focusedEpisode?.item,
                chosenStreams = null,
                overviewOnClick = {},
                overviewOnFocus = {},
                modifier = Modifier.fillMaxWidth(.7f),
            )

            if (episodes.isNotEmpty()) {
                if (requestFocusAfterSeason) {
                    LaunchedEffect(seasonIndex) {
                        firstItemFocusRequester.tryRequestFocus()
                        requestFocusAfterSeason = false
                    }
                }
                val listState = rememberLazyListState(episodeIndex)
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.focusRestorer(rowFocusRequester),
                ) {
                    itemsIndexed(episodes, key = { _, ep -> ep.id.toString() }) { index, episode ->
                        val interactionSource = remember { MutableInteractionSource() }
                        if (interactionSource.collectIsFocusedAsState().value && index != position.episodeIndex) {
                            viewModel.position.update { it.copy(episodeIndex = index) }
                        }
                        val row = episode.row
                        BannerCard(
                            name = row.title,
                            item = episode.item,
                            aspectRatio = AspectRatios.WIDE,
                            cornerText = row.episodeNumber?.let { "E$it" },
                            played = row.playedToCompletion,
                            playPercent = row.playedPercent,
                            onClick = { viewModel.play(episode) },
                            onLongClick = { deleteRequest = episode },
                            interactionSource = interactionSource,
                            cardHeight = 120.dp,
                            useSeriesForPrimary = false,
                            modifier =
                                Modifier
                                    .ifElse(index == episodeIndex, Modifier.focusRequester(firstItemFocusRequester).focusRequester(rowFocusRequester))
                                    .onKeyEvent {
                                        if (isPlayKeyUp(it)) {
                                            viewModel.play(episode)
                                            true
                                        } else {
                                            false
                                        }
                                    },
                        )
                    }
                }
            }

            focusedEpisode?.let { ep ->
                val row = ep.row
                val resumable = !row.playedToCompletion && row.lastPositionMs > 0L
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = HeaderUtils.startPadding, top = 4.dp).fillMaxWidth(),
                ) {
                    IconTextButton(R.string.fa_play, if (resumable) "Resume" else "Play") { viewModel.play(ep) }
                    if (resumable) {
                        IconTextButton(R.string.fa_rotate_left, "Restart") { viewModel.play(ep, fromStart = true) }
                    }
                    IconTextButton(
                        if (row.playedToCompletion) R.string.fa_eye_slash else R.string.fa_check,
                        if (row.playedToCompletion) "Mark unwatched" else "Mark watched",
                    ) { viewModel.setWatched(ep, !row.playedToCompletion) }
                    IconTextButton(R.string.fa_xmark, "Delete episode") { deleteRequest = ep }
                    IconTextButton(R.string.fa_xmark, "Delete show") { deleteShowRequest = true }
                }
                Text(
                    text = "${row.episodeTag} · ${com.github.damontecres.wholphin.services.download.formatBytes(row.bytesTotal)} · ${row.qualityTier.uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = HeaderUtils.startPadding),
                )
            }
        }
    }

    deleteRequest?.let { ep ->
        ConfirmDialog(
            title = "Delete download?",
            text = "${ep.row.episodeTag} ${ep.row.title} will be removed from this phone.",
            onConfirm = {
                viewModel.deleteEpisode(ep)
                deleteRequest = null
            },
            onDismiss = { deleteRequest = null },
        )
    }
    if (deleteShowRequest) {
        ConfirmDialog(
            title = "Delete all of ${state.name}?",
            text = "${seasons.sumOf { it.episodes.size }} downloaded episodes will be removed from this phone.",
            onConfirm = {
                deleteShowRequest = false
                viewModel.deleteShow()
            },
            onDismiss = { deleteShowRequest = false },
        )
    }
}

@Composable
private fun IconTextButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(iconRes), fontFamily = FontAwesome)
            Text(text = label, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onConfirm) { Text("Delete", color = Color(0xFFFF8A80)) }
            }
        }
    }
}
