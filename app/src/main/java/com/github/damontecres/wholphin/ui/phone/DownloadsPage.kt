package com.github.damontecres.wholphin.ui.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.download.DownloadState
import com.github.damontecres.wholphin.data.download.DownloadedItem
import com.github.damontecres.wholphin.services.download.formatBytes
import com.github.damontecres.wholphin.ui.FontAwesome
import java.util.UUID

private sealed interface DeleteRequest {
    data class Item(
        val item: DownloadedItem,
    ) : DeleteRequest

    data class Show(
        val show: DownloadedShow,
    ) : DeleteRequest

    data object All : DeleteRequest
}

/**
 * Downloads home: an "In progress" section (queued / downloading / paused / failed, hidden when
 * empty) followed by a poster grid of shows and movies that have at least one finished download.
 * Shows open [DownloadedSeriesPage]; movies play straight away. Works by touch and by D-pad.
 * Needs no server: everything comes from the downloads database and local artwork.
 */
@Composable
fun DownloadsPage(
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }

    MaterialTheme(colorScheme = PhoneColorScheme) {
        BoxWithConstraints(
            modifier =
                modifier
                    .nativeTouch()
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        ) {
            val horizontalPadding = 20.dp
            val tileSpacing = 12.dp
            val minTile = 120.dp
            val columns = ((maxWidth - horizontalPadding * 2 + tileSpacing) / (minTile + tileSpacing)).toInt().coerceIn(2, 8)

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
            ) {
                item(key = "topbar") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                    ) {
                        Text(
                            "Downloads",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.anyActive) ActionChip("Pause all", R.string.fa_pause) { viewModel.pauseAll() }
                        if (state.anyPaused) ActionChip("Resume all", R.string.fa_play) { viewModel.resumeAll() }
                        if (!state.isEmpty) ActionChip("Delete all", R.string.fa_xmark, danger = true) { deleteRequest = DeleteRequest.All }
                    }
                    Text(
                        "Wi‑Fi only · kept until you delete them · watched state syncs when back online",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                if (state.loaded && state.isEmpty) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "No downloads yet.\nOpen a show or movie and tap Download.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ---- in progress ----
                if (state.inProgress.isNotEmpty()) {
                    item(key = "inprogress-header") { SectionHeader("In progress", "${state.inProgress.size}") }
                    items(state.inProgress, key = { "ip-" + it.itemId }) { item ->
                        DownloadRow(
                            item = item,
                            onPause = { viewModel.pause(item.itemId) },
                            onResume = { viewModel.resume(item.itemId) },
                            onRetry = { viewModel.retry(item.itemId) },
                            onDelete = { deleteRequest = DeleteRequest.Item(item) },
                        )
                    }
                }

                // ---- shows ----
                if (state.shows.isNotEmpty()) {
                    item(key = "shows-header") { SectionHeader("Shows", "${state.shows.size}") }
                    items(state.shows.chunked(columns), key = { row -> "shows-" + row.first().seriesId }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(tileSpacing), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { show ->
                                DownloadPosterCard(
                                    imageUrl = show.poster,
                                    title = show.name,
                                    subtitle = "${show.doneCount} episode${if (show.doneCount == 1) "" else "s"} · ${formatBytes(show.totalBytes)}",
                                    watched = show.allWatched,
                                    unwatchedCount = show.unwatchedCount,
                                    watchedPercent = null,
                                    onClick = { viewModel.openShow(show) },
                                    onDelete = { deleteRequest = DeleteRequest.Show(show) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                // ---- movies ----
                if (state.movies.isNotEmpty()) {
                    item(key = "movies-header") { SectionHeader("Movies", "${state.movies.size}") }
                    items(state.movies.chunked(columns), key = { row -> "movies-" + row.first().itemId }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(tileSpacing), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { movie ->
                                DownloadPosterCard(
                                    imageUrl = movie.displayImage,
                                    title = movie.title,
                                    subtitle =
                                        listOfNotNull(
                                            movie.premiereYear?.toString(),
                                            formatBytes(movie.bytesTotal),
                                            if (movie.playedToCompletion) "Watched" else null,
                                        ).joinToString(" · "),
                                    watched = movie.playedToCompletion,
                                    unwatchedCount = -1,
                                    watchedPercent = movie.playedPercent.takeIf { it > 0.0 },
                                    onClick = { viewModel.play(movie) },
                                    onDelete = { deleteRequest = DeleteRequest.Item(movie) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }

        deleteRequest?.let { req ->
            val (title, text) =
                when (req) {
                    is DeleteRequest.Item -> "Delete download?" to "${req.item.episodeTag} ${req.item.title}".trim()
                    is DeleteRequest.Show -> "Delete all of ${req.show.name}?" to "${req.show.doneCount} downloaded episodes will be removed from this phone."
                    DeleteRequest.All -> "Delete all downloads?" to "Every downloaded file will be removed from this phone."
                }
            AlertDialog(
                onDismissRequest = { deleteRequest = null },
                title = { Text(title) },
                text = { Text(text) },
                confirmButton = {
                    TextButton(onClick = {
                        when (req) {
                            is DeleteRequest.Item -> viewModel.delete(req.item.itemId)
                            is DeleteRequest.Show -> viewModel.deleteShow(req.show.seriesId)
                            DeleteRequest.All -> viewModel.deleteAll()
                        }
                        deleteRequest = null
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleteRequest = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One queued / downloading / paused / failed item with its controls. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    item: DownloadedItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 2.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                ).combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        when (item.state) {
                            DownloadState.PAUSED -> onResume()
                            DownloadState.FAILED -> onRetry()
                            else -> onPause()
                        }
                    },
                    onLongClick = onDelete,
                ).padding(10.dp),
    ) {
        AsyncImage(
            model = item.displayImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = if (item.isEpisode) "${item.seriesName ?: ""} ${item.episodeTag}  ${item.title}".trim() else item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.state == DownloadState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (item.bytesTotal > 0L) {
                LinearProgressIndicator(progress = { item.progressFraction }, modifier = Modifier.fillMaxWidth())
            } else if (item.state.isActive) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        when (item.state) {
            DownloadState.PAUSED -> IconAction(R.string.fa_play, "Resume", onResume)
            DownloadState.FAILED -> IconAction(R.string.fa_rotate_right, "Retry", onRetry)
            else -> IconAction(R.string.fa_pause, "Pause", onPause)
        }
        IconAction(R.string.fa_xmark, "Cancel", onDelete, danger = true)
    }
}

private fun statusLine(item: DownloadedItem): String {
    val size =
        when {
            item.bytesTotal > 0L -> "${formatBytes(item.bytesDone)} of ${formatBytes(item.bytesTotal)}"
            item.bytesDone > 0L -> formatBytes(item.bytesDone)
            else -> null
        }
    val tier = item.qualityTier.takeIf { it != "auto" }?.uppercase()
    val state =
        when (item.state) {
            DownloadState.QUEUED -> "Queued"
            DownloadState.RESOLVING -> "Finding source…"
            DownloadState.DOWNLOADING -> "Downloading"
            DownloadState.PAUSED -> "Paused"
            DownloadState.DONE -> "Done"
            DownloadState.FAILED -> "Failed: ${item.error ?: "unknown error"}"
        }
    return listOfNotNull(state, size, tier).joinToString(" · ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconAction(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                .combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick, onClickLabel = label),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(iconRes),
            fontFamily = FontAwesome,
            fontSize = 18.sp,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ActionChip(
    label: String,
    iconRes: Int,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(iconRes),
            fontFamily = FontAwesome,
            fontSize = 13.sp,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Suppress("unused")
private fun UUID.short() = toString().take(8)
