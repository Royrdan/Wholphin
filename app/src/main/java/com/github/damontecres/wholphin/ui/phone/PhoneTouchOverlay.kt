package com.github.damontecres.wholphin.ui.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.FontAwesome
import kotlinx.coroutines.delay

private const val SEEK_STEP_MS = 10_000L
private const val AUTO_HIDE_MS = 4_000L

/**
 * Simple touch controls layered over the player on phones: tap to show/hide, double-tap left or
 * right half to seek 10 seconds, play/pause, seek bar. Never shown on TV.
 */
@Composable
fun PhoneTouchOverlay(
    player: Player,
    title: String?,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    fun poke() {
        lastInteraction = System.currentTimeMillis()
        visible = true
    }

    // Poll the player while the overlay is visible (cheap; avoids a listener on the shared player)
    LaunchedEffect(visible) {
        while (true) {
            val d = player.duration
            durationMs = if (d == C.TIME_UNSET || d < 0) 0L else d
            if (!scrubbing) positionMs = player.currentPosition.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            delay(if (visible) 250L else 1000L)
        }
    }
    LaunchedEffect(lastInteraction, isPlaying) {
        if (isPlaying) {
            delay(AUTO_HIDE_MS)
            if (System.currentTimeMillis() - lastInteraction >= AUTO_HIDE_MS - 50) visible = false
        }
    }

    Box(
        modifier =
            modifier.pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (visible) visible = false else poke()
                    },
                    onDoubleTap = { offset ->
                        val forward = offset.x > size.width / 2f
                        val target =
                            (player.currentPosition + if (forward) SEEK_STEP_MS else -SEEK_STEP_MS)
                                .coerceAtLeast(0L)
                        player.seekTo(target)
                        poke()
                    },
                )
            },
    ) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top bar: back + title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    FaButton(R.string.fa_arrow_left_long, "Back", size = 40.dp) { onBack() }
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(
                            title ?: "",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // Centre transport
                Row(
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    FaButton(R.string.fa_rotate_left, "Back 10 seconds", size = 56.dp) {
                        player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
                        poke()
                    }
                    FaButton(if (isPlaying) R.string.fa_pause else R.string.fa_play, if (isPlaying) "Pause" else "Play", size = 72.dp) {
                        if (player.isPlaying) player.pause() else player.play()
                        poke()
                    }
                    FaButton(R.string.fa_rotate_right, "Forward 10 seconds", size = 56.dp) {
                        player.seekTo(player.currentPosition + SEEK_STEP_MS)
                        poke()
                    }
                }

                // Bottom: time + seek bar
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val shownPos = if (scrubbing) (scrubFraction * durationMs).toLong() else positionMs
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatClock(shownPos), color = Color.White, fontSize = 12.sp)
                        Text(formatClock(durationMs), color = Color.White, fontSize = 12.sp)
                    }
                    MaterialTheme(colorScheme = PhoneColorScheme) {
                        Slider(
                            value = if (scrubbing) scrubFraction else if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                            onValueChange = {
                                scrubbing = true
                                scrubFraction = it
                                poke()
                            },
                            onValueChangeFinished = {
                                if (durationMs > 0) player.seekTo((scrubFraction * durationMs).toLong())
                                scrubbing = false
                                poke()
                            },
                            enabled = durationMs > 0,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FaButton(
    iconRes: Int,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClickLabel = label, onClick = onClick),
    ) {
        Text(
            text = stringResource(iconRes),
            fontFamily = FontAwesome,
            fontSize = (size.value * 0.4f).sp,
            color = Color.White,
        )
    }
}

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
