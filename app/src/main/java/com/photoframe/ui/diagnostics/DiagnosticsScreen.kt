package com.photoframe.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import com.photoframe.ui.tv.rememberInitialFocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.photoframe.data.db.SyncLogEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Diagnostics (DESIGN §11): the recent sync log — "invaluable for a headless frame" — plus a
 * cache-usage line. Read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    log: List<SyncLogEntity>,
    cacheUsageBytes: Long,
    photoCount: Int,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "$photoCount photos · ${formatBytes(cacheUsageBytes)} cached",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp),
            )
            Divider()
            if (log.isEmpty()) {
                Text(
                    "No sync activity yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(log, key = { it.id }) { entry -> LogRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: SyncLogEntity) {
    val color = when (entry.level) {
        SyncLogEntity.Level.ERROR.name -> MaterialTheme.colorScheme.error
        SyncLogEntity.Level.WARN.name -> Color(0xFFFFB300)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault())),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(entry.message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm:ss")

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}
