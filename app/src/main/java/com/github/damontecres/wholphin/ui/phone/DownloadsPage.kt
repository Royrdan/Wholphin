package com.github.damontecres.wholphin.ui.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

    data class Group(
        val group: DownloadGroup,
    ) : DeleteRequest

    data object All : DeleteRequest
}

/**
 * Offline downloads, grouped by show. Works by touch and by D-pad (every row and button is a
 * focusable clickable).
 */
@Composable
fun DownloadsPage(
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsState()
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }

    MaterialTheme(colorScheme = PhoneColorScheme) {
        Column(
            modifier =
                modifier
                    .nativeTouch()
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 20.dp),
        ) {
            // ---- top bar ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            ) {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                val anyActive = groups.any { g -> g.items.any { it.state.isActive } }
                val anyPaused = groups.any { g -> g.items.any { it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED } }
                if (anyActive) {
                    ActionChip("Pause all", R.string.fa_pause) { viewModel.pauseAll() }
                }
                if (anyPaused) {
                    ActionChip("Resume all", R.string.fa_play) { viewModel.resumeAll() }
                }
                if (groups.isNotEmpty()) {
                    ActionChip("Delete all", R.string.fa_xmark, danger = true) { deleteRequest = DeleteRequest.All }
                }
            }
            Text(
                "Wi‑Fi only · kept until you delete them",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No downloads yet.\nOpen a show or movie and tap Download.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                groups.forEach { group ->
                    item(key = "group-${group.key}") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${group.items.size} item${if (group.items.size == 1) "" else "s"} · ${group.doneCount} done · ${formatBytes(group.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            ActionChip("Delete", R.string.fa_xmark, danger = true) { deleteRequest = DeleteRequest.Group(group) }
                        }
                    }
                    items(group.items, key = { it.itemId.toString() }) { item ->
                        DownloadRow(
                            item = item,
                            onPlay = { viewModel.play(item) },
                            onPause = { viewModel.pause(item.itemId) },
                            onResume = { viewModel.resume(item.itemId) },
                            onRetry = { viewModel.retry(item.itemId) },
                            onDelete = { deleteRequest = DeleteRequest.Item(item) },
                        )
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }

        deleteRequest?.let { req ->
            val (title, text) =
                when (req) {
                    is DeleteRequest.Item -> "Delete download?" to "${req.item.episodeTag} ${req.item.title}".trim()
                    is DeleteRequest.Group -> "Delete all of ${req.group.title}?" to "${req.group.items.size} downloads will be removed from this phone."
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
                            is DeleteRequest.Group -> viewModel.deleteGroup(req.group.key)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    item: DownloadedItem,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val playable = item.state == DownloadState.DONE
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
                            DownloadState.DONE -> onPlay()
                            DownloadState.PAUSED -> onResume()
                            DownloadState.FAILED -> onRetry()
                            else -> onPause()
                        }
                    },
                    onLongClick = onDelete,
                ).padding(10.dp),
    ) {
        AsyncImage(
            model = item.imageUrl,
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
                text = if (item.isEpisode) "${item.episodeTag}  ${item.title}" else item.title,
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
            if (item.state.isActive || item.state == DownloadState.PAUSED) {
                Spacer(Modifier.height(6.dp))
                if (item.bytesTotal > 0L) {
                    LinearProgressIndicator(progress = { item.progressFraction }, modifier = Modifier.fillMaxWidth())
                } else if (item.state != DownloadState.PAUSED) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (playable && item.runTimeTicks != null && item.lastPositionMs > 0L && !item.playedToCompletion) {
                Spacer(Modifier.height(6.dp))
                val frac = (item.lastPositionMs * 10_000.0 / item.runTimeTicks).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            }
        }
        when (item.state) {
            DownloadState.DONE -> IconAction(R.string.fa_play, "Play", onPlay)
            DownloadState.PAUSED -> IconAction(R.string.fa_play, "Resume", onResume)
            DownloadState.FAILED -> IconAction(R.string.fa_rotate_right, "Retry", onRetry)
            else -> IconAction(R.string.fa_pause, "Pause", onPause)
        }
        IconAction(R.string.fa_xmark, "Delete", onDelete, danger = true)
    }
}

private fun statusLine(item: DownloadedItem): String {
    val size =
        when {
            item.bytesTotal > 0L && item.state != DownloadState.DONE -> "${formatBytes(item.bytesDone)} of ${formatBytes(item.bytesTotal)}"
            item.bytesTotal > 0L -> formatBytes(item.bytesTotal)
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
            DownloadState.DONE -> if (item.playedToCompletion) "Watched" else "Ready to play"
            DownloadState.FAILED -> "Failed: ${item.error ?: "unknown error"}"
        }
    return listOfNotNull(state, size, tier).joinToString(" · ")
}

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

@Composable
private fun ActionChip(
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
