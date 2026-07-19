package com.photoframe.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * TV / D-pad focus helpers. The frame is normally driven by touch (Fire HD) but must also be
 * fully operable with only a remote on Fire TV, where two things are needed that touch never
 * requires: something must hold focus when a screen appears (otherwise the D-pad does nothing),
 * and the focused element must be obvious from across the room.
 */

/**
 * A [FocusRequester] that pulls focus to itself once, when the screen first composes, so a remote
 * can immediately drive it. Attach the returned requester to the first interactive element:
 *
 *     val first = rememberInitialFocusRequester()
 *     Button(..., modifier = Modifier.focusRequester(first))
 */
@Composable
fun rememberInitialFocusRequester(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The node may not be attached on the very first frame; requesting focus then throws.
        runCatching { requester.requestFocus() }
    }
    return requester
}

/**
 * Draws a bright ring (and faint fill) while the element is D-pad focused. Material's default
 * focus indication is too subtle for a 10-foot view, so custom clickable rows use this to make
 * the current selection unmistakable. Pass the element's [shape] so the ring matches its corners.
 */
fun Modifier.tvFocusHighlight(shape: Shape = RectangleShape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.primary
    this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) {
                Modifier
                    .background(ring.copy(alpha = 0.18f), shape)
                    .border(2.dp, ring, shape)
            } else {
                Modifier
            }
        )
}
