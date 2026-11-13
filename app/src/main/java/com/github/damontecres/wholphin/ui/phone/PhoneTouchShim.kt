package com.github.damontecres.wholphin.ui.phone

import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import timber.log.Timber

/**
 * Phone touch shim.
 *
 * The TV UI is built from `androidx.tv.material3` surfaces (Surface/Card/Button/ListItem). Their click
 * modifier (`tvClickable`) only registers `semantics { onClick/onLongClick }`, `focusable()` and a
 * DPAD-enter key handler. There is no pointer-input `clickable`, so a finger tap does nothing on a phone.
 *
 * On phones (no leanback feature) this modifier is attached to the root of every Compose window
 * (activity content + dialogs). It listens for taps/long-presses that no child consumed, looks up the
 * semantics node under the finger that exposes an OnClick / OnLongClick action, requests focus on it
 * (so the TV focus-driven UI updates) and then invokes the action.
 *
 * Nodes that already take touch natively (material3 widgets, text fields, the phone screens, the player
 * touch overlay) consume the pointer event themselves so the shim never sees the tap. As a second guard,
 * any subtree tagged with [nativeTouch] is skipped, as are editable text nodes and disabled nodes.
 */

/** Semantics tag marking a subtree that handles touch itself; the shim ignores it. */
val NativeTouch = SemanticsPropertyKey<Boolean>("NativeTouch")

var SemanticsPropertyReceiver.nativeTouch by NativeTouch

/** Mark this subtree as handling touch natively so the phone touch shim leaves it alone. */
fun Modifier.nativeTouch(): Modifier = semantics { nativeTouch = true }

/**
 * Attach to the root of a Compose window. No-op on Android TV devices.
 */
fun Modifier.phoneTouchShim(): Modifier =
    composed {
        if (!rememberIsPhone()) return@composed this
        val view = LocalView.current
        var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        this
            .onGloballyPositioned { coords = it }
            .pointerInput(view) {
                detectTapGestures(
                    onTap = { pos -> dispatchTouch(view, coords, pos, longPress = false) },
                    onLongPress = { pos -> dispatchTouch(view, coords, pos, longPress = true) },
                )
            }
    }

private class Hit(
    val node: SemanticsNode,
    val area: Float,
)

private fun dispatchTouch(
    view: View,
    coords: LayoutCoordinates?,
    local: Offset,
    longPress: Boolean,
) {
    try {
        val root = (view as? RootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return
        val window = coords?.takeIf { it.isAttached }?.localToWindow(local) ?: return
        val key = if (longPress) SemanticsActions.OnLongClick else SemanticsActions.OnClick
        val hit = findTarget(root, window, key) ?: return
        val node = hit.node
        node.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
        val fired = node.config.getOrNull(key)?.action?.invoke()
        Timber.v(
            "phoneTouchShim: %s at %s -> node %d (%s) fired=%s",
            if (longPress) "longPress" else "tap",
            window,
            node.id,
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text,
            fired,
        )
    } catch (ex: Exception) {
        Timber.w(ex, "phoneTouchShim failed")
    }
}

/**
 * Depth-first walk of the unmerged semantics tree. Returns the smallest node under [p] that exposes
 * [key], skipping subtrees tagged [NativeTouch], disabled nodes and editable text.
 */
private fun findTarget(
    root: SemanticsNode,
    p: Offset,
    key: SemanticsPropertyKey<*>,
): Hit? {
    var best: Hit? = null
    fun visit(node: SemanticsNode) {
        val config = node.config
        if (config.getOrNull(NativeTouch) == true) return
        val bounds = node.boundsInWindow
        if (config.contains(key) &&
            !config.contains(SemanticsProperties.Disabled) &&
            !config.contains(SemanticsProperties.EditableText) &&
            bounds.width > 0f &&
            bounds.height > 0f &&
            bounds.contains(p)
        ) {
            val area = bounds.width * bounds.height
            val current = best
            if (current == null || area <= current.area) best = Hit(node, area)
        }
        node.children.forEach { visit(it) }
    }
    visit(root)
    return best
}
