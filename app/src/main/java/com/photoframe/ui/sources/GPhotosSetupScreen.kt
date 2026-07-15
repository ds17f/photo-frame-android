package com.photoframe.ui.sources

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Google Photos Picker add / "add more photos" flow (DESIGN §5.2). One phase-routed takeover:
 * enter the Desktop OAuth client + sign in on the tablet (once), then a session QR the user scans
 * on their **phone** to pick photos in Google Photos, then a download-progress step. The QR is the
 * nice part — picking happens on the phone where the user is already signed into Google Photos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GPhotosSetupScreen(
    state: GPhotosSetupState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onClientId: (String) -> Unit,
    onClientSecret: (String) -> Unit,
    onReplace: (Boolean) -> Unit,
    onSignIn: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.sourceId == null) "Add Google Photos" else "Add photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state.phase) {
                GPhotosPhase.CONFIG -> ConfigStep(state, onName, onClientId, onClientSecret, onSignIn)
                GPhotosPhase.SIGNING_IN -> SigningInStep()
                GPhotosPhase.SESSION -> SessionStep(state, onReplace)
                GPhotosPhase.DOWNLOADING -> DownloadingStep(state)
                GPhotosPhase.DONE -> DoneStep(state, onBack)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ConfigStep(
    state: GPhotosSetupState,
    onName: (String) -> Unit,
    onClientId: (String) -> Unit,
    onClientSecret: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    Text(
        "Use a Google Cloud \"Desktop app\" OAuth client. See docs/GOOGLE_SETUP.md for the one-time " +
            "setup. You'll sign in here on the tablet once; picking photos happens on your phone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.name,
        onValueChange = onName,
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.clientId,
        onValueChange = onClientId,
        label = { Text("OAuth client ID") },
        placeholder = { Text("…apps.googleusercontent.com") },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.clientSecret,
        onValueChange = onClientSecret,
        label = { Text("OAuth client secret") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onSignIn,
        enabled = state.canSignIn,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Sign in with Google") }
}

@Composable
private fun SigningInStep() {
    Spacer(Modifier.size(24.dp))
    CircularProgressIndicator()
    Spacer(Modifier.size(12.dp))
    Text(
        "Complete sign-in in the browser that just opened, then return here.",
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SessionStep(state: GPhotosSetupState, onReplace: (Boolean) -> Unit) {
    val context = LocalContext.current
    val uri = state.pickerUri
    Spacer(Modifier.size(8.dp))
    if (uri == null) {
        CircularProgressIndicator()
        Text("Starting a picking session…")
        return
    }
    Text("Scan to pick photos", style = MaterialTheme.typography.titleMedium)
    Text(
        "Scan with your phone, then pick photos in Google Photos. They'll download here as you finish.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.size(8.dp))
    QrImage(uri)
    Spacer(Modifier.size(8.dp))
    OutlinedButton(onClick = {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }) { Text("Open here instead") }

    if (state.sourceId != null) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Replace existing photos", Modifier.weight(1f))
            Switch(checked = state.replace, onCheckedChange = onReplace)
        }
    }
}

@Composable
private fun DownloadingStep(state: GPhotosSetupState) {
    Spacer(Modifier.size(24.dp))
    val total = state.downloadedTotal
    if (total > 0) {
        LinearProgressIndicator(
            progress = state.downloadedDone.toFloat() / total,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(8.dp))
        Text("Downloading ${state.downloadedDone} of $total…")
    } else {
        CircularProgressIndicator()
        Spacer(Modifier.size(8.dp))
        Text("Downloading picked photos…")
    }
}

@Composable
private fun DoneStep(state: GPhotosSetupState, onBack: () -> Unit) {
    Spacer(Modifier.size(24.dp))
    Text("Done!", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.size(8.dp))
    Text(
        when (val n = state.addedCount) {
            null, 0 -> "No new photos were added."
            1 -> "Added 1 photo to the frame."
            else -> "Added $n photos to the frame."
        },
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.size(16.dp))
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun QrImage(content: String) {
    val bitmap = remember(content) { qrBitmap(content, QR_PX) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Picker QR code",
        modifier = Modifier.size(240.dp),
    )
}

private const val QR_PX = 512

/** Render [content] as a QR-code bitmap (ZXing, no scanner dependency). */
private fun qrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val offset = y * size
        for (x in 0 until size) {
            pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}
