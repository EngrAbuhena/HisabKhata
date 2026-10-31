package com.hisabkhata.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F6952),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8EFDE),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF52655C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8DC),
    onSecondaryContainer = Color(0xFF102018),
    tertiary = Color(0xFF80591B),
    surface = Color(0xFFF7FAF6),
    onSurface = Color(0xFF191D1B),
    surfaceVariant = Color(0xFFDBE5DD),
    onSurfaceVariant = Color(0xFF3F4943),
    outline = Color(0xFF6F7972),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF85D6B6),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF00513D),
    onPrimaryContainer = Color(0xFFC8EFDE),
    secondary = Color(0xFFB9CCC0),
    onSecondary = Color(0xFF25342C),
    secondaryContainer = Color(0xFF3B4B41),
    onSecondaryContainer = Color(0xFFD5E8DC),
    tertiary = Color(0xFFF1C27D),
    surface = Color(0xFF101512),
    onSurface = Color(0xFFDFE4DF),
    surfaceVariant = Color(0xFF3F4943),
    onSurfaceVariant = Color(0xFFBFC9C1),
    outline = Color(0xFF89938B),
    error = Color(0xFFFFB4AB)
)

@Composable
fun HisabKhataTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
