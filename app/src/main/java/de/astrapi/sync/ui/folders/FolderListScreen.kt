package de.astrapi.sync.ui.folders

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import de.astrapi.sync.network.FolderInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(onOpenSettings: () -> Unit, viewModel: FolderListViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Welcher (noch nicht verbundene) Server-Ordner gerade per SAF-Picker
    // verbunden werden soll -- der Picker selbst kennt keinen
    // "Kontext"-Parameter, daher hier zwischengehalten.
    var pendingFolder by remember { mutableStateOf<FolderInfo?>(null) }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val folder = pendingFolder
        if (uri != null && folder != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.bindFolder(folder.id, folder.description, uri)
        }
        pendingFolder = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync-Ordner") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::onAddClicked) {
                Icon(Icons.Default.Add, contentDescription = "Ordner hinzufügen")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when {
                state.isLoading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                state.errorMessage != null -> Text(
                    state.errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                )

                state.folders.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Noch keine Ordner hinzugefügt", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tippe unten rechts auf + , um einen freigegebenen Ordner zu verbinden.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                else -> LazyColumn {
                    items(state.folders, key = { it.folderId }) { item ->
                        FolderRow(item = item, onSyncNow = { viewModel.syncNow(item.folderId) })
                    }
                }
            }
        }
    }

    if (state.showAddSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = viewModel::onAddDismissed, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Ordner hinzufügen", style = MaterialTheme.typography.titleMedium)
                when {
                    state.isLoadingAvailable -> CircularProgressIndicator(
                        modifier = Modifier.padding(top = 16.dp),
                    )

                    state.addErrorMessage != null -> Text(
                        state.addErrorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp),
                    )

                    state.availableFolders.isEmpty() -> Text(
                        "Alle freigegebenen Ordner sind bereits verbunden.",
                        modifier = Modifier.padding(top = 16.dp),
                    )

                    else -> Column(modifier = Modifier.padding(top = 8.dp)) {
                        state.availableFolders.forEach { folder ->
                            OutlinedButton(
                                onClick = {
                                    pendingFolder = folder
                                    pickFolderLauncher.launch(null)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(folder.description)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Menschenlesbarer Name des SAF-Baums statt der rohen content://-URI --
 * DocumentFile.name macht dafür eine echte ContentResolver-Abfrage,
 * deshalb per remember(uri) nicht bei jeder Recomposition neu geholt.
 * Fällt defensiv zurück, falls der Zugriff zwischenzeitlich entzogen
 * wurde (Nutzer hat die Berechtigung außerhalb der App widerrufen). */
@Composable
private fun rememberFriendlyFolderName(context: Context, uri: android.net.Uri): String =
    remember(uri) {
        runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
            .getOrNull()
            ?: uri.lastPathSegment
            ?: uri.toString()
    }

@Composable
private fun FolderRow(item: FolderUiItem, onSyncNow: () -> Unit) {
    val context = LocalContext.current
    val folderName = rememberFriendlyFolderName(context, item.boundUri)
    val lastSyncedText = item.lastSyncedAt?.let {
        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    } ?: "noch nie synchronisiert"

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.description, style = MaterialTheme.typography.titleMedium)
                    Text(
                        folderName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Zuletzt synchronisiert: $lastSyncedText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (item.statusText != null) {
                        Text(
                            item.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = onSyncNow, enabled = !item.isSyncing) {
                    if (item.isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp).padding(end = 8.dp))
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (item.isSyncing) "Synchronisiere …" else "Jetzt synchronisieren")
                }
            }
        }
    }
}
