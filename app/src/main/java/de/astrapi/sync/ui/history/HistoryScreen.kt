package de.astrapi.sync.ui.history

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** Zeigt den datei-genauen Sync-Verlauf EINES Ordners (T-338-SYNC),
 * server-seitig gespeist über HistoryViewModel. Gleiches Grundgerüst wie
 * ConflictsScreen (Top-Bar mit Zurück, LazyColumn aus Cards, Empty-State). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(folderId: String, onBack: () -> Unit, viewModel: HistoryViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(folderId) { viewModel.refresh(folderId) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        state.folderDescription.ifBlank { "Verlauf" },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh(folderId) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            when {
                state.isLoading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                state.errorMessage != null -> Text(
                    state.errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 24.dp),
                )

                state.items.isEmpty() -> EmptyHistoryState()

                else -> LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                    items(
                        state.items,
                        key = { "${it.path}/${it.syncedAt}/${it.action}" },
                    ) { item -> HistoryRow(item) }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            "Noch keine Synchronisation",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Hier erscheint, wann welche Datei\nhoch- oder heruntergeladen wurde.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun actionIcon(action: String): ImageVector = when (action) {
    "uploaded" -> Icons.Default.CloudUpload
    "downloaded" -> Icons.Default.CloudDownload
    "deleted_local", "deleted_remote" -> Icons.Default.Delete
    "conflict" -> Icons.Default.WarningAmber
    "dir_created_local", "dir_created_remote" -> Icons.Default.CreateNewFolder
    "dir_deleted_local", "dir_deleted_remote" -> Icons.Default.Delete
    else -> Icons.Default.History
}

/** synced_at kommt als "YYYY-MM-DD HH:MM:SS" (Server-Lokalzeit, siehe
 * history.py) -- kein echtes DateTimeParse nötig für eine relative
 * Anzeige, ein grobes manuelles Parsing reicht und bleibt tolerant
 * gegenüber unerwartetem Format (zeigt dann einfach den Rohwert). */
private fun relativeTimeOrRaw(syncedAt: String): String = runCatching {
    val (datePart, timePart) = syncedAt.split(" ")
    val (y, mo, d) = datePart.split("-").map { it.toInt() }
    val (h, mi, s) = timePart.split(":").map { it.toInt() }
    val cal = java.util.Calendar.getInstance()
    cal.set(y, mo - 1, d, h, mi, s)
    DateUtils.getRelativeTimeSpanString(
        cal.timeInMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}.getOrElse { syncedAt }

@Composable
private fun HistoryRow(item: HistoryUiItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    actionIcon(item.action),
                    contentDescription = null,
                    tint = if (item.action == "conflict") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Text(item.path, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(
                "${item.actionLabel} · ${relativeTimeOrRaw(item.syncedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Gerät: ${item.deviceLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
