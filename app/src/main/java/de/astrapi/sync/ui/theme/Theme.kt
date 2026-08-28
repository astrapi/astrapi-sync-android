package de.astrapi.sync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Volles Tonal-Set statt nur eines einzelnen primary-Werts -- eine
 * einzelne Farbe auf die M3-Baseline-Defaults (Standard-Lila für
 * secondary/tertiary) draufgesetzt war Teil dessen, was die App "nach
 * Vorlage" statt bewusst gestaltet aussehen ließ. Indigo als Haupt-,
 * Teal als Akzentfarbe (passt zum "synchron/aktuell"-Gefühl bei
 * "Zuletzt synchronisiert"), ohne zusätzliche Farb-Utility-Bibliothek
 * von Hand abgestimmt statt aus einer einzelnen Seed-Farbe generiert. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF4756C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E1FF),
    onPrimaryContainer = Color(0xFF141A6E),
    secondary = Color(0xFF5B5D77),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E0F9),
    onSecondaryContainer = Color(0xFF181A2F),
    tertiary = Color(0xFF12876D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA9F2DB),
    onTertiaryContainer = Color(0xFF002016),
    background = Color(0xFFFAF9FF),
    surface = Color(0xFFFAF9FF),
    surfaceVariant = Color(0xFFE4E1EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC0C2FF),
    onPrimary = Color(0xFF1B2384),
    primaryContainer = Color(0xFF303B9C),
    onPrimaryContainer = Color(0xFFE0E1FF),
    secondary = Color(0xFFC4C5E1),
    onSecondary = Color(0xFF2C2F45),
    secondaryContainer = Color(0xFF43455C),
    onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFF8CD6BE),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF00513E),
    onTertiaryContainer = Color(0xFFA9F2DB),
    background = Color(0xFF121218),
    surface = Color(0xFF121218),
    surfaceVariant = Color(0xFF46464F),
)

/** Großzügigere Rundungen als die M3-Standardwerte -- durchgängig statt
 * pro Composable einzeln, wirkt sich u.a. auf Cards, FAB und
 * Bottom-Sheet aus. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun AstrapiSyncTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (useDark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}
