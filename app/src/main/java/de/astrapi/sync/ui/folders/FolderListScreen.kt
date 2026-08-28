package de.astrapi.sync.ui.folders

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun FolderListScreen(viewModel: FolderListViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Welcher Ordner gerade per SAF-Picker verbunden werden soll -- der
    // Picker selbst kennt keinen "Kontext"-Parameter, daher hier
    // zwischengehalten.
    var pendingFolderId by remember { mutableStateOf<String?>(null) }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val folderId = pendingFolderId
        if (uri != null && folderId != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val description = state.folders.find { it.folderId == folderId }?.description ?: folderId
            viewModel.bindFolder(folderId, description, uri)
        }
        pendingFolderId = null
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Sync-Ordner", style = MaterialTheme.typography.headlineSmall)

        when {
            state.isLoading -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            state.errorMessage != null -> Text(
                state.errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )

            state.folders.isEmpty() -> Text(
                "Diesem Gerät ist noch kein Ordner freigegeben.",
                modifier = Modifier.padding(top = 16.dp),
            )

            else -> LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                items(state.folders, key = { it.folderId }) { item ->
                    FolderRow(
                        item = item,
                        onPickFolder = {
                            pendingFolderId = item.folderId
                            pickFolderLauncher.launch(null)
                        },
                        onSyncNow = { viewModel.syncNow(item.folderId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(item: FolderUiItem, onPickFolder: () -> Unit, onSyncNow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(item.description, style = MaterialTheme.typography.titleMedium)
            Text(
                if (item.boundUri != null) item.boundUri.toString() else "Nicht verbunden",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.statusText != null) {
                Text(item.statusText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Row(modifier = Modifier.padding(top = 10.dp)) {
                OutlinedButton(onClick = onPickFolder) {
                    Text(if (item.boundUri == null) "Ordner wählen" else "Ordner ändern")
                }
                if (item.boundUri != null) {
                    Button(
                        onClick = onSyncNow,
                        enabled = !item.isSyncing,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        if (item.isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(if (item.isSyncing) "Synchronisiere …" else "Jetzt synchronisieren")
                    }
                }
            }
        }
    }
}
