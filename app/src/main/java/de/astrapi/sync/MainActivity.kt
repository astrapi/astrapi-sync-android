package de.astrapi.sync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.astrapi.sync.ui.conflicts.ConflictsScreen
import de.astrapi.sync.ui.folders.FolderListScreen
import de.astrapi.sync.ui.history.HistoryScreen
import de.astrapi.sync.ui.pairing.PairingScreen
import de.astrapi.sync.ui.settings.SettingsScreen
import de.astrapi.sync.ui.theme.AstrapiSyncTheme
import de.astrapi.sync.ui.theme.resolveDarkTheme

private const val ROUTE_PAIRING = "pairing"
private const val ROUTE_FOLDERS = "folders"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_CONFLICTS = "conflicts"
private const val ROUTE_HISTORY = "history"

class MainActivity : ComponentActivity() {

    companion object {
        /** Von ConflictNotifications gesetzt -- öffnet nach einem
         * Hintergrund-Konflikt direkt die Konflikt-Liste statt der
         * Ordnerliste, damit der Nutzer nicht selbst danach suchen muss. */
        const val EXTRA_OPEN_CONFLICTS = "open_conflicts"
    }

    // mutableStateOf statt einer lokalen val in onCreate() -- launchMode
    // "singleTask" liefert eine bereits laufende Activity per
    // onNewIntent() nach, ohne onCreate()/setContent() erneut
    // aufzurufen. Ohne diesen mutablen Zustand würde ein Tap auf die
    // Konflikt-Benachrichtigung bei bereits offener App ins Leere laufen.
    private var openConflicts by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_CONFLICTS, false)) openConflicts = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SyncApp

        // Ab Android 13 (API 33) muss die Berechtigung explizit zur
        // Laufzeit erfragt werden, sonst bleibt ConflictNotifications
        // stumm -- direkt beim Start statt erst beim ersten Konflikt
        // gefragt, damit ein Hintergrund-Konflikt ohne offene App nicht
        // von Anfang an ungesehen bleibt. Bei Ablehnung kein Folgeschaden,
        // die App funktioniert unverändert, nur ohne Benachrichtigung.
        if (Build.VERSION.SDK_INT >= 33) {
            val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        openConflicts = intent?.getBooleanExtra(EXTRA_OPEN_CONFLICTS, false) ?: false
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
                    AppNavHost(startPaired = app.securePrefs.isPaired, openConflicts = openConflicts)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(startPaired: Boolean, openConflicts: Boolean = false) {
    val navController: NavHostController = rememberNavController()

    LaunchedEffect(openConflicts) {
        if (openConflicts && startPaired) navController.navigate(ROUTE_CONFLICTS)
    }

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
            FolderListScreen(
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onOpenConflicts = { navController.navigate(ROUTE_CONFLICTS) },
                onOpenHistory = { navController.navigate(ROUTE_HISTORY) },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_CONFLICTS) {
            ConflictsScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
    }
}
