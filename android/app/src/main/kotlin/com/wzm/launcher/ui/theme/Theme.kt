package com.wzm.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF79C370),
    secondary = Color(0xFF9AA39A),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E)
)

@Composable
fun WzmLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
