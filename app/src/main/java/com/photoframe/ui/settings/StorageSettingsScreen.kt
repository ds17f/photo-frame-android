package com.photoframe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import com.photoframe.ui.tv.rememberInitialFocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoframe.data.cache.CacheLocation
import com.photoframe.settings.SyncSettings

/**
 * Storage settings (streaming-cache model, docs/STORAGE_PLAN.md): cache location (Internal / SD
 * card, with free space) and the retained-pool cap. Switching location moves the files and the
 * prefetcher repopulates — the frame still sees the whole library either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(
    info: StorageInfo,
    settings: SyncSettings,
    cacheUsageBytes: Long,
    onBack: () -> Unit,
    onLocation: (CacheLocation) -> Unit,
    onCap: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.focusRequester(rememberInitialFocusRequester()),
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Location picker
            Column {
                Text("Cache location", fontWeight = FontWeight.SemiBold)
                LocationRow(
                    label = "Internal storage",
                    detail = "${formatBytes(info.internalFreeBytes)} free",
                    selected = info.location == CacheLocation.INTERNAL,
                    enabled = !info.switching,
                    onSelect = { onLocation(CacheLocation.INTERNAL) },
                )
                LocationRow(
                    label = "SD card",
                    detail = when {
                        info.sdFreeBytes != null -> "${formatBytes(info.sdFreeBytes)} free"
                        else -> "No SD card detected"
                    },
                    selected = info.location == CacheLocation.SD_CARD,
                    enabled = !info.switching && info.sdCardAvailable,
                    onSelect = { onLocation(CacheLocation.SD_CARD) },
                )
                Text(
                    if (info.switching) {
                        "Moving cache… the frame repopulates in a moment."
                    } else {
                        "Switching moves cached files; your photos and history are untouched."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Divider()

            // Cache cap (moved here from Cache & sync)
            Column {
                Text("Cache cap: ${formatBytes(settings.cacheCapBytes)}", fontWeight = FontWeight.SemiBold)
                val capGb = settings.cacheCapBytes / (1024.0 * 1024 * 1024)
                Slider(
                    value = capGb.toFloat(),
                    onValueChange = { gb -> onCap((gb.toDouble() * 1024 * 1024 * 1024).toLong()) },
                    valueRange = 0.25f..32f,
                )
                Text(
                    "Using ${formatBytes(cacheUsageBytes)} of ${formatBytes(settings.cacheCapBytes)}. " +
                        "A retained buffer of recently-shown photos — not a limit on how many the frame sees.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LocationRow(
    label: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onSelect)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}
