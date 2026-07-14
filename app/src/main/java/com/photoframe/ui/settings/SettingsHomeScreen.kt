package com.photoframe.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Settings hub (DESIGN §11) linking the section screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    onBack: () -> Unit,
    onSources: () -> Unit,
    onDisplay: () -> Unit,
    onSchedule: () -> Unit,
    onStorage: () -> Unit,
    onCache: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SettingsRow("Photo sources", "Immich servers, health, sync now", onSources)
            Divider()
            SettingsRow("Display", "Interval, transitions, Ken Burns, overlays", onDisplay)
            Divider()
            SettingsRow("Schedule", "Quiet hours, boot start, power rules", onSchedule)
            Divider()
            SettingsRow("Storage", "Cache location (internal / SD card), cap, usage", onStorage)
            Divider()
            SettingsRow("Sync", "Sync interval, deletion policy, Wi-Fi only", onCache)
            Divider()
            SettingsRow("Diagnostics", "Sync log, cache usage", onDiagnostics)
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
