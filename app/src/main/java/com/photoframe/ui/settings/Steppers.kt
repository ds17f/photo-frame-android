package com.photoframe.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoframe.ui.tv.tvFocusHighlight

/**
 * A value stepper: label + current value, then a focusable −/＋ pair flanking a progress track.
 * Replaces Material `Slider`, whose thumb cannot be grabbed with a TV remote's D-pad — the whole
 * app must be operable from the Fire TV remote (FIRETV_PLAN §T3). Works identically for touch
 * (tap −/＋) and remote (focus a button, press or hold select). [step] is the per-press
 * increment; holding a button relies on key-repeat for wide ranges.
 */
@Composable
fun StepperRow(
    label: String,
    valueLabel: String,
    position: Int,
    range: IntRange,
    step: Int = 1,
    onPosition: (Int) -> Unit,
) {
    val span = (range.last - range.first).coerceAtLeast(1)
    val fraction = ((position - range.first).toFloat() / span).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 16.sp)
            Text(valueLabel, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepButton(Icons.Filled.Remove, "Decrease $label", position > range.first) {
                onPosition((position - step).coerceAtLeast(range.first))
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            StepButton(Icons.Filled.Add, "Increase $label", position < range.last) {
                onPosition((position + step).coerceAtMost(range.last))
            }
        }
    }
}

@Composable
private fun StepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).clip(CircleShape).tvFocusHighlight(CircleShape),
    ) {
        Icon(icon, contentDescription)
    }
}
