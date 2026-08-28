package de.astrapi.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.astrapi.sync.ui.folders.FolderListScreen
import de.astrapi.sync.ui.pairing.PairingScreen
import de.astrapi.sync.ui.settings.SettingsScreen
import de.astrapi.sync.ui.theme.AstrapiSyncTheme
import de.astrapi.sync.ui.theme.resolveDarkTheme

private const val ROUTE_PAIRING = "pairing"
private const val ROUTE_FOLDERS = "folders"
private const val ROUTE_SETTINGS = "settings"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SyncApp
        setContent {
            val themeMode by app.preferences.themeMode.collectAsState()
            val accentColor by app.preferences.accentColor.collectAsState()
            val useDynamicColor by app.preferences.useDynamicColor.collectAsState()
            val darkTheme = resolveDarkTheme(themeMode)

            // Die In-App-Auswahl (Hell/Dunkel) kann von der Systemeinstellung
            // abweichen -- das System kennt diese Abweichung nicht und würde
            // den Statusleisten-/Navigationsleisten-Kontrast sonst falsch
            // setzen (z.B. helle Icons auf unserem hellen Hintergrund).
            val view = LocalView.current
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }

            AstrapiSyncTheme(
                themeMode = themeMode,
                accentColor = accentColor,
                useDynamicColor = useDynamicColor,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(startPaired = app.securePrefs.isPaired)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(startPaired: Boolean) {
    val navController: NavHostController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (startPaired) ROUTE_FOLDERS else ROUTE_PAIRING,
    ) {
        composable(ROUTE_PAIRING) {
            PairingScreen(onPaired = {
                navController.navigate(ROUTE_FOLDERS) {
                    popUpTo(ROUTE_PAIRING) { inclusive = true }
                }
            })
        }
        composable(ROUTE_FOLDERS) {
            FolderListScreen(onOpenSettings = { navController.navigate(ROUTE_SETTINGS) })
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
