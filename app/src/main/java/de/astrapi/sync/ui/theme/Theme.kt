package de.astrapi.sync.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Nur Primärfarbe pro Ton -- secondary bleibt bewusst neutral (dezenter
 * Slate-Ton), tertiary bewusst immer Teal (Bedeutung "aktuell/synchron"
 * bei "Zuletzt synchronisiert", soll unabhängig von der gewählten
 * Akzentfarbe konsistent bleiben) statt bei jeder Akzentfarbe
 * mitzuwandern. */
data class AccentTone(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

enum class AccentColor(val label: String, private val light: AccentTone, private val dark: AccentTone) {
    INDIGO(
        "Indigo",
        AccentTone(Color(0xFF4756C7), Color(0xFFFFFFFF), Color(0xFFE0E1FF), Color(0xFF141A6E)),
        AccentTone(Color(0xFFC0C2FF), Color(0xFF1B2384), Color(0xFF303B9C), Color(0xFFE0E1FF)),
    ),
    BLUE(
        "Blau",
        AccentTone(Color(0xFF2F6FED), Color(0xFFFFFFFF), Color(0xFFDCE6FF), Color(0xFF002C71)),
        AccentTone(Color(0xFFAFC6FF), Color(0xFF002E6D), Color(0xFF14418E), Color(0xFFDCE6FF)),
    ),
    TEAL(
        "Türkis",
        AccentTone(Color(0xFF0B8571), Color(0xFFFFFFFF), Color(0xFFA6F2DF), Color(0xFF00201A)),
        AccentTone(Color(0xFF79D9BD), Color(0xFF00382D), Color(0xFF005141), Color(0xFFA6F2DF)),
    ),
    PURPLE(
        "Violett",
        AccentTone(Color(0xFF8146C1), Color(0xFFFFFFFF), Color(0xFFEEDBFF), Color(0xFF2C0060)),
        AccentTone(Color(0xFFD9B8FF), Color(0xFF45237A), Color(0xFF603C92), Color(0xFFEEDBFF)),
    ),
    ORANGE(
        "Orange",
        AccentTone(Color(0xFFB5590A), Color(0xFFFFFFFF), Color(0xFFFFDCC0), Color(0xFF3A1800)),
        AccentTone(Color(0xFFFFB786), Color(0xFF5F2C00), Color(0xFF874200), Color(0xFFFFDCC0)),
    ),
    ROSE(
        "Rosé",
        AccentTone(Color(0xFFC2185B), Color(0xFFFFFFFF), Color(0xFFFFD9E2), Color(0xFF3F0018)),
        AccentTone(Color(0xFFFFB1C8), Color(0xFF5F1136), Color(0xFF7D2A4D), Color(0xFFFFD9E2)),
    ),
    ;

    fun tone(darkTheme: Boolean): AccentTone = if (darkTheme) dark else light

    /** Repräsentative Farbe fürs Auswahl-Icon (Settings-Screen) --
     * bewusst der helle Ton, unabhängig vom aktuell aktiven App-Theme,
     * damit die Palette immer gleich aussieht. */
    fun previewColor(): Color = light.primary
}

/** Schwarz oder Weiß, je nachdem was auf dieser Farbe besser lesbar ist
 * -- für das Auswahl-Häkchen auf den Akzentfarb-Kacheln. */
fun Color.contrastingIcon(): Color = if (luminance() > 0.5f) Color.Black else Color.White

private val NeutralSecondaryLight = Color(0xFF5B5D77)
private val NeutralOnSecondaryLight = Color(0xFFFFFFFF)
private val NeutralSecondaryContainerLight = Color(0xFFE1E0F9)
private val NeutralOnSecondaryContainerLight = Color(0xFF181A2F)
private val TertiaryLight = Color(0xFF12876D)
private val OnTertiaryLight = Color(0xFFFFFFFF)
private val TertiaryContainerLight = Color(0xFFA9F2DB)
private val OnTertiaryContainerLight = Color(0xFF002016)
private val BackgroundLight = Color(0xFFFAF9FF)
private val SurfaceVariantLight = Color(0xFFE4E1EC)

private val NeutralSecondaryDark = Color(0xFFC4C5E1)
private val NeutralOnSecondaryDark = Color(0xFF2C2F45)
private val NeutralSecondaryContainerDark = Color(0xFF43455C)
private val NeutralOnSecondaryContainerDark = Color(0xFFE1E0F9)
private val TertiaryDark = Color(0xFF8CD6BE)
private val OnTertiaryDark = Color(0xFF00382A)
private val TertiaryContainerDark = Color(0xFF00513E)
private val OnTertiaryContainerDark = Color(0xFFA9F2DB)
private val BackgroundDark = Color(0xFF121218)
private val SurfaceVariantDark = Color(0xFF46464F)

private fun colorSchemeFor(accent: AccentColor, darkTheme: Boolean): ColorScheme {
    val tone = accent.tone(darkTheme)
    return if (darkTheme) {
        darkColorScheme(
            primary = tone.primary,
            onPrimary = tone.onPrimary,
            primaryContainer = tone.primaryContainer,
            onPrimaryContainer = tone.onPrimaryContainer,
            secondary = NeutralSecondaryDark,
            onSecondary = NeutralOnSecondaryDark,
            secondaryContainer = NeutralSecondaryContainerDark,
            onSecondaryContainer = NeutralOnSecondaryContainerDark,
            tertiary = TertiaryDark,
            onTertiary = OnTertiaryDark,
            tertiaryContainer = TertiaryContainerDark,
            onTertiaryContainer = OnTertiaryContainerDark,
            background = BackgroundDark,
            surface = BackgroundDark,
            surfaceVariant = SurfaceVariantDark,
        )
    } else {
        lightColorScheme(
            primary = tone.primary,
            onPrimary = tone.onPrimary,
            primaryContainer = tone.primaryContainer,
            onPrimaryContainer = tone.onPrimaryContainer,
            secondary = NeutralSecondaryLight,
            onSecondary = NeutralOnSecondaryLight,
            secondaryContainer = NeutralSecondaryContainerLight,
            onSecondaryContainer = NeutralOnSecondaryContainerLight,
            tertiary = TertiaryLight,
            onTertiary = OnTertiaryLight,
            tertiaryContainer = TertiaryContainerLight,
            onTertiaryContainer = OnTertiaryContainerLight,
            background = BackgroundLight,
            surface = BackgroundLight,
            surfaceVariant = SurfaceVariantLight,
        )
    }
}

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

/** Dynamische Farben (aus dem Hintergrundbild, Material You) sind erst
 * ab Android 12 (API 31) verfügbar -- auf älteren Geräten (minSdk 26)
 * fällt das UI dafür konsequent auf die manuelle Akzentfarbe zurück,
 * siehe SettingsScreen. */
fun dynamicColorSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Öffentlich statt nur intern in AstrapiSyncTheme, da MainActivity
 * dieselbe Auflösung braucht, um Status-/Navigationsleisten-Kontrast
 * passend zum tatsächlich aktiven Theme zu setzen (nicht nur zur
 * Systemeinstellung -- die kann von der In-App-Auswahl abweichen). */
@Composable
fun resolveDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AstrapiSyncTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentColor: AccentColor = AccentColor.INDIGO,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useDark = resolveDarkTheme(themeMode)
    val context = LocalContext.current
    val colors = if (useDynamicColor && dynamicColorSupported()) {
        if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        colorSchemeFor(accentColor, useDark)
    }
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}
