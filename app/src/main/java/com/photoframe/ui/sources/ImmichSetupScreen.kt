package com.photoframe.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.photoframe.data.source.SourceHealthStatus
import com.photoframe.data.source.immich.ImmichCatalog
import com.photoframe.ui.tv.rememberInitialFocusRequester

/**
 * Immich add/edit flow (DESIGN §5.1): server URL + API key with a "Test connection", then the
 * combinable selection (albums / people / favorites / whole-library-or-window). Save is enabled
 * only once connected with a non-empty selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmichSetupScreen(
    state: ImmichSetupState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
    onKey: (String) -> Unit,
    onTest: () -> Unit,
    onToggleAlbum: (String) -> Unit,
    onTogglePerson: (String) -> Unit,
    onFavorites: (Boolean) -> Unit,
    onWholeLibrary: (Boolean) -> Unit,
    onRollingWindow: (Int?) -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.editingId == null) "Add Immich" else "Edit Immich") },
                navigationIcon = {
                    // Focus the back button, not a text field: focusing an OutlinedTextField
                    // auto-opens the Fire TV on-screen keyboard over the album list, which is
                    // pure obstruction when the config arrived pre-filled via immich_import.json.
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
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = onName,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = onUrl,
                label = { Text("Server URL") },
                placeholder = { Text("https://photos.example.com") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = onKey,
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = state.baseUrl.isNotBlank() && state.apiKey.isNotBlank() && !state.testing,
                ) { Text("Test connection") }
                Spacer(Modifier.size(12.dp))
                when {
                    state.testing -> CircularProgressIndicator(Modifier.size(20.dp))
                    state.connection != null -> ConnectionResult(state)
                }
            }

            if (state.connected) {
                Divider()

                // Most setups show the whole library or favorites rather than hand-picked
                // albums, so those two + Save sit at the top — reachable without scrolling
                // past a long album list. Once connected, focus jumps straight here (skipping
                // the text fields, which would pop the Fire TV keyboard). The album/person
                // picker follows below for when specific albums are wanted.
                val quickFocus = remember { FocusRequester() }
                LaunchedEffect(state.connected) {
                    if (state.connected) runCatching { quickFocus.requestFocus() }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Whole library", Modifier.weight(1f))
                    Switch(
                        checked = state.selection.wholeLibrary,
                        onCheckedChange = onWholeLibrary,
                        modifier = Modifier.focusRequester(quickFocus),
                    )
                }
                if (state.selection.wholeLibrary) {
                    Text(
                        "Limit to recent",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RollingWindowChip("All", null, state.selection.rollingWindowDays, onRollingWindow)
                        RollingWindowChip("1 year", 365, state.selection.rollingWindowDays, onRollingWindow)
                        RollingWindowChip("6 months", 180, state.selection.rollingWindowDays, onRollingWindow)
                        RollingWindowChip("3 months", 90, state.selection.rollingWindowDays, onRollingWindow)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Favorites", Modifier.weight(1f))
                    Switch(checked = state.selection.favoritesOnly, onCheckedChange = onFavorites)
                }

                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White)
                    } else {
                        Text(if (state.editingId == null) "Add source & sync" else "Save & sync")
                    }
                }
                if (!state.canSave && !state.saving && state.selection.isEmpty) {
                    Text(
                        "Pick at least one album, person, favorites, or your library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Optional: hand-pick specific albums / people (loads after connecting).
                Divider()
                if (state.loadingCatalog) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Loading albums and people…")
                    }
                } else {
                    AlbumPersonPicker(
                        state = state,
                        onToggleAlbum = onToggleAlbum,
                        onTogglePerson = onTogglePerson,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionResult(state: ImmichSetupState) {
    val health = state.connection ?: return
    val (color, text) = when (health.status) {
        SourceHealthStatus.OK -> Color(0xFF4CAF50) to "Connected"
        else -> MaterialTheme.colorScheme.error to (health.message ?: "Connection failed")
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun AlbumPersonPicker(
    state: ImmichSetupState,
    onToggleAlbum: (String) -> Unit,
    onTogglePerson: (String) -> Unit,
) {
    val selection = state.selection

    Text("Or pick specific albums", fontWeight = FontWeight.SemiBold)
    if (state.albums.isEmpty()) {
        Text("No albums", style = MaterialTheme.typography.bodySmall)
    } else {
        state.albums.forEach { album ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = album.id in selection.albumIds,
                    onCheckedChange = { onToggleAlbum(album.id) },
                )
                Text(album.albumName, Modifier.weight(1f))
                Text("${album.assetCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (state.people.isNotEmpty()) {
        Spacer(Modifier.size(8.dp))
        Text("People", fontWeight = FontWeight.SemiBold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            items(state.people, key = { it.id }) { person ->
                PersonChip(
                    name = person.name,
                    thumbnailUrl = ImmichCatalog.personThumbnailUrl(state.baseUrl, person.id),
                    apiKey = state.apiKey,
                    selected = person.id in selection.personIds,
                    onClick = { onTogglePerson(person.id) },
                )
            }
        }
    }
}

@Composable
private fun RollingWindowChip(
    label: String,
    days: Int?,
    current: Int?,
    onSelect: (Int?) -> Unit,
) {
    FilterChip(
        selected = current == days,
        onClick = { onSelect(days) },
        label = { Text(label) },
    )
}

@Composable
private fun PersonChip(
    name: String,
    thumbnailUrl: String,
    apiKey: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderMod = if (selected) {
        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
    } else {
        Modifier
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).then(borderMod)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .addHeader("x-api-key", apiKey)
                    .crossfade(true)
                    .build(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
            if (selected) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0x334CAF50)))
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
