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
import com.photoframe.data.source.DeletionPolicy
import com.photoframe.settings.SyncSettings

/**
 * Sync settings (DESIGN §6/§11): sync interval, deletion policy, network constraint. The cache cap
 * and location now live on the Storage screen (streaming-cache model). Changes reschedule sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    settings: SyncSettings,
    onBack: () -> Unit,
    onUpdate: ((SyncSettings) -> SyncSettings) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cache & sync") },
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
            // Sync interval — stepper (not Slider) so the Fire TV remote can adjust it; ±15 min.
            Column {
                StepperRow(
                    label = "Sync interval",
                    valueLabel = "${settings.syncIntervalMinutes} min",
                    position = settings.syncIntervalMinutes,
                    range = SyncSettings.INTERVAL_RANGE.first..SyncSettings.INTERVAL_RANGE.last,
                    step = 15,
                    onPosition = { v -> onUpdate { it.copy(syncIntervalMinutes = v) } },
                )
                Text(
                    "How often the frame checks Immich for new photos (min 15 min).",
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
