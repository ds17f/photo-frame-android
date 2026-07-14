package com.photoframe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoframe.data.source.DeletionPolicy
import com.photoframe.settings.SyncSettings

/**
 * Cache & sync settings (DESIGN §6/§11): storage cap, sync interval, deletion policy, network
 * constraint, plus a live cache-usage readout. Changes reschedule background sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    settings: SyncSettings,
    cacheUsageBytes: Long,
    onBack: () -> Unit,
    onUpdate: ((SyncSettings) -> SyncSettings) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cache & sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            // Sync interval
            Column {
                Text("Sync every ${settings.syncIntervalMinutes} min", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = settings.syncIntervalMinutes.toFloat(),
                    onValueChange = { v ->
                        onUpdate { it.copy(syncIntervalMinutes = v.toInt()) }
                    },
                    valueRange = SyncSettings.INTERVAL_RANGE.first.toFloat()..SyncSettings.INTERVAL_RANGE.last.toFloat(),
                )
                Text(
                    "How often the frame checks Immich for new photos (min 15 min).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Divider()

            // Cache cap — the retained-pool buffer size (the frame sees the whole library regardless).
            Column {
                Text("Cache cap: ${formatBytes(settings.cacheCapBytes)}", fontWeight = FontWeight.SemiBold)
                val capGb = settings.cacheCapBytes / (1024.0 * 1024 * 1024)
                Slider(
                    value = capGb.toFloat(),
                    onValueChange = { gb ->
                        onUpdate { it.copy(cacheCapBytes = (gb.toDouble() * 1024 * 1024 * 1024).toLong()) }
                    },
                    valueRange = 0.25f..32f,
                )
                Text(
                    "Using ${formatBytes(cacheUsageBytes)} of ${formatBytes(settings.cacheCapBytes)}. " +
                        "A retained buffer of recently-shown photos — not a limit on how many photos the frame sees.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Divider()

            // Deletion policy
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mirror deletions", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Remove photos from the frame when they leave the Immich selection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.deletionPolicy == DeletionPolicy.MIRROR,
                    onCheckedChange = { mirror ->
                        onUpdate {
                            it.copy(
                                deletionPolicy = if (mirror) DeletionPolicy.MIRROR else DeletionPolicy.KEEP_UNTIL_EVICTED,
                            )
                        }
                    },
                )
            }
            Divider()

            // Network constraint
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi only", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Only sync on unmetered networks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.requireUnmetered,
                    onCheckedChange = { on -> onUpdate { it.copy(requireUnmetered = on) } },
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}
