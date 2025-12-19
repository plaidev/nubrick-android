package io.nubrick.nubrick.component

import androidx.compose.foundation.isSystemInDarkTheme
  import androidx.compose.material3.MaterialTheme
  import androidx.compose.material3.darkColorScheme
  import androidx.compose.material3.lightColorScheme
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.graphics.Color

  private val LightScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),

    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),

    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF000000),
  )

  private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),

    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFFFFFFF),

    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFFFFFFF),
  )

  @Composable
  fun NubrickTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
  ) {
    MaterialTheme(
      colorScheme = if (darkTheme) DarkScheme else LightScheme,
      content = content
    )
  }