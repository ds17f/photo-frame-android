package com.photoframe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import com.photoframe.ui.tv.rememberInitialFocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoframe.playlist.model.OrderMode
import com.photoframe.playlist.model.PortraitMode
import com.photoframe.settings.Corner
import com.photoframe.settings.DisplaySettings
import com.photoframe.settings.FontSize
import com.photoframe.settings.Transition
import kotlin.math.roundToInt

private val INTERVAL_STEPS = listOf(5, 10, 15, 30, 45, 60, 120, 300, 600, 1800, 3600)

/**
 * Display settings screen (DESIGN §11). Every control writes through to DataStore; because the
 * slideshow observes the same settings flow, changes apply live without a restart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(
    settings: DisplaySettings,
    onUpdate: ((DisplaySettings) -> DisplaySettings) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Display") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.focusRequester(rememberInitialFocusRequester()),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SectionHeader("Playback")
            StepperRow(
                label = "Photo interval",
                valueLabel = formatSeconds(settings.intervalSeconds),
                position = INTERVAL_STEPS.indexOfNearest(settings.intervalSeconds),
                range = 0..INTERVAL_STEPS.lastIndex,
                onPosition = { i -> onUpdate { it.copy(intervalSeconds = INTERVAL_STEPS[i]) } },
            )
            ChoiceRow(
                label = "Order",
                options = OrderMode.entries,
                selected = settings.orderMode,
                labelOf = ::labelOfOrder,
                onSelect = { m -> onUpdate { it.copy(orderMode = m) } },
            )
            if (settings.orderMode == OrderMode.RECENCY_BOOSTED_SHUFFLE) {
                StepperRow(
                    label = "Boost photos from the last",
                    valueLabel = "${settings.recencyWindowDays} days",
                    position = settings.recencyWindowDays,
                    range = DisplaySettings.RECENCY_WINDOW_DAYS_RANGE.first..DisplaySettings.RECENCY_WINDOW_DAYS_RANGE.last,
                    step = 5,
                    onPosition = { d -> onUpdate { it.copy(recencyWindowDays = d) } },
                )
            }
            // Favorites bias applies only in shuffle modes (ordered modes keep their strict order).
            if (settings.orderMode == OrderMode.SHUFFLE ||
                settings.orderMode == OrderMode.RECENCY_BOOSTED_SHUFFLE
            ) {
                StepperRow(
                    label = "Show favorites more often",
                    valueLabel = when (settings.favoriteSharePercent) {
                        0 -> "Off"
                        100 -> "Favorites only"
                        else -> "~${settings.favoriteSharePercent}% of photos"
                    },
                    position = settings.favoriteSharePercent,
                    range = DisplaySettings.FAVORITE_SHARE_PERCENT_RANGE.first..DisplaySettings.FAVORITE_SHARE_PERCENT_RANGE.last,
                    step = 10,
                    onPosition = { p -> onUpdate { it.copy(favoriteSharePercent = p) } },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Motion")
            ChoiceRow(
                label = "Transition",
                options = Transition.entries,
                selected = settings.transition,
                labelOf = { it.name.lowercase().replaceFirstChar(Char::uppercase).replace('_', ' ') },
                onSelect = { t -> onUpdate { it.copy(transition = t) } },
            )
            StepperRow(
                label = "Transition length",
                valueLabel = "%.1fs".format(settings.transitionSeconds),
                position = (settings.transitionSeconds * 10).roundToInt(),
                range = 5..30,
                step = 5,
                onPosition = { p -> onUpdate { it.copy(transitionSeconds = p / 10f) } },
            )
            SwitchRow("Ken Burns pan & zoom", settings.kenBurnsEnabled) { on ->
                onUpdate { it.copy(kenBurnsEnabled = on) }
            }
            ChoiceRow(
                label = "Portrait photos",
                options = PortraitMode.entries,
                selected = settings.portraitMode,
                labelOf = ::labelOfPortrait,
                onSelect = { m -> onUpdate { it.copy(portraitMode = m) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Overlays")
            SwitchRow("Show clock & date", settings.clockEnabled) { on ->
                onUpdate { it.copy(clockEnabled = on) }
            }
            if (settings.clockEnabled) {
                ChoiceRow(
                    label = "Clock corner",
                    options = Corner.entries,
                    selected = settings.clockCorner,
                    labelOf = ::labelOfCorner,
                    onSelect = { c -> onUpdate { it.copy(clockCorner = c) } },
                )
                ChoiceRow(
                    label = "Clock size",
                    options = FontSize.entries,
                    selected = settings.clockSize,
                    labelOf = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    onSelect = { s -> onUpdate { it.copy(clockSize = s) } },
                )
            }
            SwitchRow("Show photo caption", settings.captionEnabled) { on ->
                onUpdate { it.copy(captionEnabled = on) }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("Diagnostics")
            SwitchRow("Log shown photos to file", settings.playbackLogEnabled) { on ->
                onUpdate { it.copy(playbackLogEnabled = on) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 16.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { opt ->
                FilterChip(
                    selected = opt == selected,
                    onClick = { onSelect(opt) },
                    label = { Text(labelOf(opt)) },
                )
            }
        }
    }
}

private fun List<Int>.indexOfNearest(value: Int): Int {
    var best = 0
    var bestDiff = Int.MAX_VALUE
    forEachIndexed { i, v ->
        val d = kotlin.math.abs(v - value)
        if (d < bestDiff) { bestDiff = d; best = i }
    }
    return best
}

private fun formatSeconds(s: Int): String = when {
    s < 60 -> "${s}s"
    s % 60 == 0 && s < 3600 -> "${s / 60}m"
    s == 3600 -> "1h"
    else -> "${s / 60}m ${s % 60}s"
}

private fun labelOfOrder(m: OrderMode) = when (m) {
    OrderMode.SHUFFLE -> "Shuffle"
    OrderMode.CHRONOLOGICAL -> "Oldest first"
    OrderMode.NEWEST_FIRST -> "Newest first"
    OrderMode.RECENCY_BOOSTED_SHUFFLE -> "Recency shuffle"
}

private fun labelOfPortrait(m: PortraitMode) = when (m) {
    PortraitMode.AUTO -> "Auto pair"
    PortraitMode.PAIR -> "Always pair"
    PortraitMode.BLUR_FILL -> "Blur fill"
}

private fun labelOfCorner(c: Corner) = when (c) {
    Corner.TOP_START -> "Top left"
    Corner.TOP_END -> "Top right"
    Corner.BOTTOM_START -> "Bottom left"
    Corner.BOTTOM_END -> "Bottom right"
}
