package de.astrapi.sync.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.astrapi.sync.ui.theme.AccentColor
import de.astrapi.sync.ui.theme.ThemeMode
import de.astrapi.sync.ui.theme.contrastingIcon
import de.astrapi.sync.ui.theme.dynamicColorSupported

/** Aufbau (Überschrift + eigener Block pro Abschnitt) ist so gewählt,
 * dass sich weitere Einstellungen später ohne Umbau ergänzen lassen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val themeMode by viewModel.themeMode.collectAsState()
    val accentColor by viewModel.accentColor.collectAsState()
    val useDynamicColor by viewModel.useDynamicColor.collectAsState()
    val syncIntervalMinutes by viewModel.syncIntervalMinutes.collectAsState()
    val themeOptions = listOf(
        Triple(ThemeMode.SYSTEM, "System", Icons.Default.BrightnessAuto),
        Triple(ThemeMode.LIGHT, "Hell", Icons.Default.LightMode),
        Triple(ThemeMode.DARK, "Dunkel", Icons.Default.DarkMode),
    )
    // WorkManager erzwingt ein hartes Minimum von 15 Min. fuer
    // periodische Arbeit (kuerzere Intervalle werden vom System selbst
    // stillschweigend auf 15 Min. angehoben) -- 15 Min. ist deshalb die
    // kleinste hier angebotene, tatsaechlich erreichbare Option.
    val intervalOptions = listOf(15L to "15 Min", 30L to "30 Min", 60L to "1 Std")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(20.dp)) {
            Text("Design", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                themeOptions.forEachIndexed { index, (mode, label, icon) ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = themeOptions.size),
                        icon = {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    ) {
                        Text(label)
                    }
                }
            }

            Text("Akzentfarbe", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 28.dp))

            if (dynamicColorSupported()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .selectable(
                            selected = useDynamicColor,
                            onClick = { viewModel.setUseDynamicColor(!useDynamicColor) },
                        ),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dynamisch", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Aus deinem Hintergrundbild abgeleitet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useDynamicColor, onCheckedChange = viewModel::setUseDynamicColor)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .alpha(if (useDynamicColor) 0.4f else 1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AccentColor.entries.forEach { color ->
                    AccentSwatch(
                        color = color,
                        selected = !useDynamicColor && accentColor == color,
                        enabled = !useDynamicColor,
                        onClick = { viewModel.setAccentColor(color) },
                    )
                }
            }

            Text(
                "Sync-Intervall",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 28.dp),
            )
            Text(
                "Wie oft im Hintergrund automatisch synchronisiert wird",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                intervalOptions.forEachIndexed { index, (minutes, label) ->
                    SegmentedButton(
                        selected = syncIntervalMinutes == minutes,
                        onClick = { viewModel.setSyncIntervalMinutes(minutes) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = intervalOptions.size),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccentSwatch(color: AccentColor, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val swatch = color.previewColor()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .background(swatch, CircleShape)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier
                },
            )
            .selectable(selected = selected, enabled = enabled, onClick = onClick),
    ) {
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = color.label, tint = swatch.contrastingIcon())
        }
    }
}
