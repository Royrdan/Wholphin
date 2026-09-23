package com.github.damontecres.wholphin.preferences

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.playback.isDown
import com.github.damontecres.wholphin.ui.playback.isUp
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Grace period applied when the preference has never been set - see the proto field */
const val DEFAULT_PROTECTION_GRACE_MINUTES = 30L

/**
 * How long a protected profile stays unlocked after the app leaves the foreground.
 *
 * Falls back to [DEFAULT_PROTECTION_GRACE_MINUTES] when never set, so installs that predate the
 * setting get the grace period rather than proto3's zero, which means "always ask".
 */
val AppPreferences.protectionGraceMinutes: Long
    get() =
        if (hasProfileProtectionGraceMinutes()) {
            profileProtectionGraceMinutes.toLong()
        } else {
            DEFAULT_PROTECTION_GRACE_MINUTES
        }

/**
 * Whether a protected profile should currently be let through without signing in again.
 *
 * True only inside the grace window after the app last left the foreground. A zero grace, or no
 * recorded exit (a genuinely cold start), always means ask.
 */
fun AppPreferences.withinProtectionGrace(nowMs: Long): Boolean {
    val grace = protectionGraceMinutes
    if (grace <= 0L || lastForegroundExitMs <= 0L) return false
    val elapsed = nowMs - lastForegroundExitMs
    // A clock that moved backwards (reboot, time sync) must not hand out an unlimited pass.
    return elapsed in 0..grace.minutes.inWholeMilliseconds
}

val PlaybackPreferences.skipBackOnResume: Duration?
    get() =
        if (skipBackOnResumeSeconds > 0) {
            skipBackOnResumeSeconds.milliseconds
        } else {
            null
        }

/**
 * Adds modifiers so that pressing Up on the first element scrolls to the bottom and vice versa
 */
@Composable
fun Modifier.lazyListWrapScrolling(
    state: LazyListState,
    focused: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    firstFocusRequester: FocusRequester,
    lastFocusRequester: FocusRequester,
): Modifier {
    val scope = rememberCoroutineScope()
    return this
        .ifElse(
            isFirst,
            Modifier
                .focusRequester(firstFocusRequester)
                .onKeyEvent {
                    if (focused && isUp(it) && it.type == KeyEventType.KeyDown) {
                        scope.launch {
                            state.animateScrollToItem(state.layoutInfo.totalItemsCount - 1)
                            lastFocusRequester.tryRequestFocus()
                        }
                        return@onKeyEvent true
                    }
                    false
                },
        ).ifElse(
            isLast,
            Modifier
                .focusRequester(lastFocusRequester)
                .onKeyEvent {
                    if (focused && isDown(it) && it.type == KeyEventType.KeyDown) {
                        scope.launch {
                            state.animateScrollToItem(0)
                            firstFocusRequester.tryRequestFocus()
                        }
                        return@onKeyEvent true
                    }
                    false
                },
        )
}
