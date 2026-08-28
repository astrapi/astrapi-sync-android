package de.astrapi.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.astrapi.sync.ui.folders.FolderListScreen
import de.astrapi.sync.ui.pairing.PairingScreen
import de.astrapi.sync.ui.theme.AstrapiSyncTheme

private const val ROUTE_PAIRING = "pairing"
private const val ROUTE_FOLDERS = "folders"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SyncApp
        setContent {
            AstrapiSyncTheme {
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
            FolderListScreen()
        }
    }
}
