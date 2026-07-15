package com.photoframe.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoframe.data.SourceType
import com.photoframe.data.source.ConfiguredSource
import com.photoframe.data.source.SourceHealthStatus
import java.time.Duration
import java.time.Instant

/**
 * Source management list (DESIGN §11): every configured source with health, last-sync and photo
 * count, plus toggle / edit / delete and a global "Sync now". "Add source" opens the Immich flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    sources: List<ConfiguredSource>,
    onBack: () -> Unit,
    onAddImmich: () -> Unit,
    onAddGphotos: () -> Unit,
    onEdit: (ConfiguredSource) -> Unit,
    onPickMore: (ConfiguredSource) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onSyncNow: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo sources") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onSyncNow) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Sync now")
                    }
                    AddSourceMenu(onAddImmich = onAddImmich, onAddGphotos = onAddGphotos)
                },
            )
        },
    ) { padding ->
        if (sources.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No photo sources yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Add your Immich server, or pick from Google Photos, to start the slideshow.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(16.dp))
                TextButton(onClick = onAddImmich) { Text("Add Immich source") }
                TextButton(onClick = onAddGphotos) { Text("Add Google Photos") }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sources, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        onEdit = { onEdit(source) },
                        onPickMore = { onPickMore(source) },
                        onToggle = { onToggle(source.id, it) },
                        onDelete = { onDelete(source.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSourceMenu(onAddImmich: () -> Unit, onAddGphotos: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Add, contentDescription = "Add source")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Add Immich") },
                onClick = { expanded = false; onAddImmich() },
            )
            DropdownMenuItem(
                text = { Text("Add Google Photos") },
                onClick = { expanded = false; onAddGphotos() },
            )
        }
    }
}

@Composable
private fun SourceCard(
    source: ConfiguredSource,
    onEdit: () -> Unit,
    onPickMore: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val isGphotos = source.type == SourceType.GPHOTOS_PICKER
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthDot(source.health.status)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(source.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        source.type.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = source.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                statusLine(source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            source.health.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (confirmDelete) {
                    Text(
                        "Remove?",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                    TextButton(onClick = onDelete) { Text("Remove") }
                } else {
                    if (isGphotos) {
                        TextButton(onClick = onPickMore) { Text("Add more photos") }
                    } else {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthDot(status: SourceHealthStatus) {
    val color = when (status) {
        SourceHealthStatus.OK -> Color(0xFF4CAF50)
        SourceHealthStatus.DEGRADED -> Color(0xFFFFC107)
        SourceHealthStatus.OFFLINE -> Color(0xFFF44336)
        SourceHealthStatus.UNKNOWN -> Color(0xFF9E9E9E)
    }
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

private fun statusLine(source: ConfiguredSource): String {
    val count = "${source.photoCount} photo${if (source.photoCount == 1) "" else "s"}"
    // Picker sources are defined by when the user last picked, not by a server sync (DESIGN §5.2).
    source.gphotosConfig?.let { config ->
        val picked = config.lastPickedAt
            ?.let { "picked ${relativeTime(Instant.ofEpochMilli(it))}" }
            ?: "no photos picked yet"
        return "$count · $picked"
    }
    val synced = source.lastSyncAt?.let { "synced ${relativeTime(it)}" } ?: "never synced"
    return "$count · $synced"
}

private fun relativeTime(instant: Instant): String {
    val secs = Duration.between(instant, Instant.now()).seconds
    return when {
        secs < 60 -> "just now"
        secs < 3600 -> "${secs / 60} min ago"
        secs < 86400 -> "${secs / 3600} h ago"
        else -> "${secs / 86400} d ago"
    }
}
