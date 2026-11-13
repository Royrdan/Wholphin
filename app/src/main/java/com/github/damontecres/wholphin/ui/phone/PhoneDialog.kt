package com.github.damontecres.wholphin.ui.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/**
 * Drop-in replacement for [androidx.compose.ui.window.Dialog].
 *
 * A dialog is its own window with its own Compose root, so the touch shim on the activity content does
 * not see taps inside it. This wrapper attaches [phoneTouchShim] to the dialog content root so the
 * TV-material buttons/list items inside dialogs respond to finger taps on a phone. On TV it is a plain
 * pass-through.
 */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        Box(modifier = Modifier.phoneTouchShim()) {
            content()
        }
    }
}
