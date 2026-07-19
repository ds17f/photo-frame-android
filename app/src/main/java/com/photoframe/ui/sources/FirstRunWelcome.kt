package com.photoframe.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.photoframe.ui.tv.rememberInitialFocusRequester

/**
 * First-run welcome (DESIGN §11) — the entry to the wizard. "Get started" opens the Immich setup;
 * once the first source is added and synced, the slideshow takes over. The Immich path should
 * take under two minutes.
 */
@Composable
fun FirstRunWelcome(onGetStarted: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Welcome to your photo frame", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.size(16.dp))
        Text(
            "Connect your Immich server and pick which photos to show. New photos you take will " +
                "appear on the frame automatically.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(32.dp))
        Button(
            onClick = onGetStarted,
            modifier = Modifier.focusRequester(rememberInitialFocusRequester()),
        ) { Text("Get started") }
    }
}
