package com.github.damontecres.wholphin.ui.phone

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.services.download.DownloadQuality
import com.github.damontecres.wholphin.services.download.formatBytes
import kotlin.time.Duration

/**
 * Touch-friendly bottom sheet to queue offline downloads. Opened from the Download button on
 * series / season / episode / movie pages.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadPickerSheet(
    target: DownloadTarget,
    onDismiss: () -> Unit,
    viewModel: DownloadPickerViewModel = hiltViewModel(),
) {
    LaunchedEffect(target) { viewModel.load(target) }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    MaterialTheme(colorScheme = PhoneColorScheme) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Column(
                modifier =
                    Modifier
                        .nativeTouch()
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Download" + if (state.title.isNotBlank()) " · ${state.title}" else "",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                if (state.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.width(24.dp))
                        Text("Loading episodes…")
                    }
                    Spacer(Modifier.padding(bottom = 24.dp))
                    return@Column
                }
                state.error?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismiss) { Text("Close") }
                    return@Column
                }

                // ---- quality ----
                Text("Quality", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DownloadQuality.entries.forEach { q ->
                        FilterChip(
                            selected = state.quality == q,
                            onClick = { viewModel.setQuality(q) },
                            label = { Text(q.label) },
                        )
                    }
                }

                // ---- scope (shows only) ----
                if (state.isShow) {
                    Text("Which episodes", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DownloadScope.entries.forEach { s ->
                            val enabled =
                                s != DownloadScope.THIS_SEASON ||
                                    state.currentSeasonId != null ||
                                    state.currentSeasonNumber != null
                            if (enabled) {
                                FilterChip(
                                    selected = state.scope == s,
                                    onClick = { viewModel.setScope(s) },
                                    label = { Text(s.label) },
                                )
                            }
                        }
                    }
                    if (state.scope == DownloadScope.NEXT_UNWATCHED) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("How many")
                            OutlinedButton(onClick = { viewModel.setNextCount(state.nextCount - 1) }, enabled = state.nextCount > 1) {
                                Text("−")
                            }
                            Text(
                                state.nextCount.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.width(32.dp),
                            )
                            OutlinedButton(onClick = { viewModel.setNextCount(state.nextCount + 1) }) { Text("+") }
                        }
                    }
                    if (state.scope == DownloadScope.PICK) {
                        EpisodeChecklist(state, viewModel)
                    }
                }

                HorizontalDivider()

                // ---- summary ----
                Text(summaryLine(state), style = MaterialTheme.typography.bodyLarge)
                if (state.isShow && state.selected.isEmpty()) {
                    Text(
                        "Nothing selected. Everything in this range is watched, already downloaded, or has no downloadable source.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = {
                        viewModel.enqueue { count ->
                            Toast
                                .makeText(
                                    context,
                                    if (count > 0) "Queued $count download${if (count == 1) "" else "s"}" else "Nothing new to download",
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                        onDismiss()
                    },
                    enabled = state.toDownload > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.toDownload > 0) {
                            "Download ${state.toDownload} ${if (state.isShow) "episode" else "item"}${if (state.toDownload == 1) "" else "s"}"
                        } else {
                            "Download"
                        },
                    )
                }
                Text(
                    "Downloads run on Wi‑Fi only and are kept until you delete them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun EpisodeChecklist(
    state: DownloadPickerState,
    viewModel: DownloadPickerViewModel,
) {
    val ordered =
        state.episodes.sortedWith(
            compareBy({ it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }),
        )
    val seasons = ordered.map { it.seasonNumber }.distinct()
    LazyColumn(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
    ) {
        seasons.forEach { season ->
            item(key = "season-$season") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (season == null) "Other" else if (season == 0) "Specials" else "Season $season",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.pickSeason(season) }) { Text("All") }
                }
            }
            items(ordered.filter { it.seasonNumber == season }, key = { it.id.toString() }) { ep ->
                val checked = ep.id in state.picked
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { viewModel.togglePicked(ep.id) },
                        enabled = ep.downloadable,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ep.label, maxLines = 1)
                        val note =
                            when {
                                ep.existing != null -> "already downloaded"
                                !ep.downloadable -> "no downloadable source"
                                ep.played -> "watched"
                                else -> null
                            }
                        if (note != null) {
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun summaryLine(state: DownloadPickerState): String {
    val parts = mutableListOf<String>()
    val n = state.selectedCount
    parts += if (state.isShow) "$n episode${if (n == 1) "" else "s"}" else (if (n == 1) "1 item" else "$n items")
    val runtime = state.totalRuntime
    if (runtime > Duration.ZERO) {
        val approx = if (state.runtimeKnownFor < n) "~" else ""
        parts += approx + formatRuntime(runtime)
    }
    when {
        state.toDownload == 0 -> Unit
        state.estimatedBytes != null -> parts += "~${formatBytes(state.estimatedBytes!!)}"
        state.sizePending -> parts += "size pending"
        state.sizeUnavailable -> parts += "size unknown"
    }
    if (state.alreadyDownloaded > 0) parts += "${state.alreadyDownloaded} already downloaded"
    return parts.joinToString(" · ")
}

fun formatRuntime(d: Duration): String {
    val h = d.inWholeHours
    val m = d.inWholeMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
