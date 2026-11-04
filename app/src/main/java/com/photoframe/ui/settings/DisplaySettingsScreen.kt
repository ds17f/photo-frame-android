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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.delay
import com.photoframe.ui.tv.rememberInitialFocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoframe.playlist.model.OrderMode
import com.photoframe.playlist.model.PortraitMode
import com.photoframe.settings.Corner
import com.photoframe.settings.DisplaySettings
import com.photoframe.settings.DashboardMode
import com.photoframe.settings.isValidDashboardUrl
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
    var dashboardUrlDraft by rememberSaveable { mutableStateOf(settings.dashboardUrl) }
    var dashboardUrlEdited by rememberSaveable { mutableStateOf(false) }
    var dashboardUrlSaveState by rememberSaveable { mutableStateOf("Saved") }
    val latestSettings by rememberUpdatedState(settings)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    val dashboardUrlValid = dashboardUrlDraft.isBlank() || isValidDashboardUrl(dashboardUrlDraft.trim())
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val commitDashboardUrl = {
        if (dashboardUrlEdited) {
            val candidate = dashboardUrlDraft.trim()
            if (candidate.isBlank() || isValidDashboardUrl(candidate)) {
                dashboardUrlDraft = candidate
                if (candidate == latestSettings.dashboardUrl) {
                    dashboardUrlEdited = false
                    dashboardUrlSaveState = "Saved"
                } else {
                    latestOnUpdate { it.copy(dashboardUrl = candidate) }
                    dashboardUrlSaveState = "Saving…"
                }
            }
        }
    }
    val leaveScreen = {
        commitDashboardUrl()
        onBack()
    }
    BackHandler { leaveScreen() }
    LaunchedEffect(dashboardUrlDraft, dashboardUrlEdited) {
        if (!dashboardUrlEdited) return@LaunchedEffect
        val candidate = dashboardUrlDraft.trim()
        if (candidate.isNotBlank() && !isValidDashboardUrl(candidate)) {
            dashboardUrlSaveState = "Invalid URL — saved URL is unchanged."
            return@LaunchedEffect
        }
        if (candidate == settings.dashboardUrl) {
            dashboardUrlEdited = false
            dashboardUrlSaveState = "Saved"
            return@LaunchedEffect
        }
        dashboardUrlSaveState = "Unsaved — saving automatically…"
        delay(700)
        if (candidate != latestSettings.dashboardUrl) {
            latestOnUpdate { it.copy(dashboardUrl = candidate) }
            dashboardUrlSaveState = "Saving…"
        } else {
            dashboardUrlEdited = false
            dashboardUrlSaveState = "Saved"
        }
    }
    LaunchedEffect(settings.dashboardUrl) {
        if (!dashboardUrlEdited) {
            dashboardUrlDraft = settings.dashboardUrl
            dashboardUrlSaveState = "Saved"
        } else if (dashboardUrlDraft.trim() == settings.dashboardUrl) {
            dashboardUrlEdited = false
            dashboardUrlSaveState = "Saved"
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Display") },
                navigationIcon = {
                    IconButton(
                        onClick = leaveScreen,
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
                    // Smooth boost by capture date: a photo this old is shown ~2x as often as an
                    // old one (today's ~3x), so newer photos lead without old ones going quiet.
                    label = "Newer photos: boost halves every",
                    valueLabel = recencyLabel(settings.recencyWindowDays),
                    position = settings.recencyWindowDays,
                    range = DisplaySettings.RECENCY_WINDOW_DAYS_RANGE.first..DisplaySettings.RECENCY_WINDOW_DAYS_RANGE.last,
                    step = 30,
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

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Desktop dashboard")
            ChoiceRow(
                label = "Show dashboard",
                options = DashboardMode.entries,
                selected = settings.dashboardMode,
                labelOf = { mode -> when (mode) {
                    DashboardMode.OFF -> "Off"
                    DashboardMode.ON_TAP -> "On tap"
                    DashboardMode.ALWAYS -> "Always on"
                } },
                onSelect = { mode -> onUpdate { it.copy(dashboardMode = mode) } },
            )
            Text(
                "On tap: tap to open the dashboard, then tap again or press Back to hide it. " +
                    "Taps and Back still show the slideshow controls. The dashboard is a passive display.",
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = dashboardUrlDraft,
                onValueChange = {
                    dashboardUrlDraft = it
                    dashboardUrlEdited = true
                    dashboardUrlSaveState = "Unsaved — saving automatically…"
                },
                label = { Text("Dashboard URL") },
                placeholder = { Text("https://dashboard.example.com/frame") },
                singleLine = true,
                isError = !dashboardUrlValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.None, autoCorrect = false),
                keyboardActions = KeyboardActions(onDone = {
                    commitDashboardUrl()
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }),
                supportingText = {
                    Text(if (!dashboardUrlValid) "Invalid URL — enter a valid HTTPS URL without embedded credentials. The last saved URL is unchanged."
                    else if (dashboardUrlDraft.trim() == settings.dashboardUrl) "Saved · Use an HTTPS page with a transparent background."
                    else dashboardUrlSaveState)
                },
                modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                    if (!state.isFocused) commitDashboardUrl()
                },
            )
            StepperRow(
                label = "Dim photos behind dashboard",
                valueLabel = "${settings.dashboardDimPercent}%",
                position = settings.dashboardDimPercent,
                range = DisplaySettings.DASHBOARD_DIM_PERCENT_RANGE,
                step = 5,
                onPosition = { value -> onUpdate { it.copy(dashboardDimPercent = value) } },
            )

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

private fun recencyLabel(days: Int): String =
    if (days < 360) "$days days" else String.format(java.util.Locale.US, "%.1f years", days / 365.0)

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
