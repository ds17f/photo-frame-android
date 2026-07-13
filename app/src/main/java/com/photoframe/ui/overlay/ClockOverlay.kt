package com.photoframe.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoframe.settings.Corner
import com.photoframe.settings.FontSize
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm")
private val AMPM_FORMAT = DateTimeFormatter.ofPattern("a")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d")

/**
 * Digital clock + date overlay (DESIGN §9). Semi-transparent white with a soft shadow so it
 * stays legible over any photo. Corner and font size come from settings.
 */
@Composable
fun ClockOverlay(corner: Corner, size: FontSize, modifier: Modifier = Modifier) {
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000L)
        }
    }

    val timeSp = when (size) {
        FontSize.SMALL -> 44.sp
        FontSize.MEDIUM -> 64.sp
        FontSize.LARGE -> 88.sp
    }
    val dateSp = when (size) {
        FontSize.SMALL -> 16.sp
        FontSize.MEDIUM -> 20.sp
        FontSize.LARGE -> 26.sp
    }
    val shadow = Shadow(color = Color.Black.copy(alpha = 0.75f), offset = Offset(0f, 2f), blurRadius = 8f)
    val alignEnd = !corner.isStart

    Box(modifier.fillMaxSize().padding(28.dp)) {
        Column(
            modifier = Modifier.align(corner.toAlignment()),
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        ) {
            Text(
                text = "${now.format(TIME_FORMAT)} ${now.format(AMPM_FORMAT)}",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = timeSp,
                    fontWeight = FontWeight.Light,
                    shadow = shadow,
                ),
            )
            Text(
                text = now.format(DATE_FORMAT),
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.86f),
                    fontSize = dateSp,
                    fontWeight = FontWeight.Normal,
                    shadow = shadow,
                ),
            )
        }
    }
}
