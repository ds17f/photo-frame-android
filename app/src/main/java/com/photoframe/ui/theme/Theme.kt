package com.photoframe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// The frame is always dark — chrome must recede behind the photos (DESIGN.md §11).
private val FrameColorScheme = darkColorScheme(
    primary = Color(0xFF7FCFFF),
    onPrimary = Color(0xFF00344C),
    background = Color(0xFF000000),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFECECEC),
)

private val FrameTypography = Typography(
    displayLarge = TextStyle(fontSize = 72.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
)

@Composable
fun PhotoFrameTheme(
    // Ignored for now; the frame is intentionally always dark, but the param keeps the
    // call site honest for any future light-mode settings screen.
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = FrameColorScheme,
        typography = FrameTypography,
        content = content,
    )
}
