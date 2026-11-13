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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.cards.ItemCardImage

/**
 * Poster tile for the Downloads library. Same image + overlay component the normal library grid
 * uses ([ItemCardImage]: watched tick, unwatched count, progress bar) but with a plain clickable
 * so it works by finger tap and by D-pad alike. Long-press (finger or D-pad centre) and the small
 * cross button both call [onDelete].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadPosterCard(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    watched: Boolean,
    unwatchedCount: Int,
    watchedPercent: Double?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: Float = AspectRatios.TALL,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .scale(if (focused) 1.04f else 1f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = 2.dp,
                        color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    ).combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onDelete,
                        onClickLabel = title,
                    ),
        ) {
            ItemCardImage(
                imageUrl = imageUrl,
                name = title,
                showOverlay = true,
                favorite = false,
                watched = watched,
                unwatchedCount = unwatchedCount,
                watchedPercent = watchedPercent,
                numberOfVersions = 0,
                useFallbackText = true,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio),
            )
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SmallIconButton(R.string.fa_xmark, "Delete", danger = true, onClick = onDelete)
        }
    }
}

/** Compact focusable icon button (touch + D-pad). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmallIconButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
                .combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick, onClickLabel = label),
    ) {
        Text(
            text = stringResource(iconRes),
            fontFamily = FontAwesome,
            fontSize = 14.sp,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
