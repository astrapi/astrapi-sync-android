package de.astrapi.sync.ui.folders

import android.graphics.Color as AndroidColor
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.astrapi.sync.network.FolderInfo
import de.astrapi.sync.sync.AllFilesAccess
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    onOpenSettings: () -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenHistory: (folderId: String) -> Unit,
    viewModel: FolderListViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val conflictCount by viewModel.conflictCount.collectAsState()
    val context = LocalContext.current

    // Welcher (noch nicht verbundene) Server-Ordner gerade per
    // FolderPickerDialog verbunden werden soll -- der Picker selbst kennt
    // keinen "Kontext"-Parameter, daher hier zwischengehalten.
    var pendingFolder by remember { mutableStateOf<FolderInfo?>(null) }
    var showFolderPicker by remember { mutableStateOf(false) }
    // Zeigt den Hinweis-Dialog, falls MANAGE_EXTERNAL_STORAGE noch nicht
    // erteilt ist -- kann sich nur außerhalb der App ändern (System-
    // Settings), daher bei jedem Compose-Durchlauf frisch geprüft statt
    // einmalig gecacht.
    var showPermissionDialog by remember { mutableStateOf(false) }

    fun startBindingFlow(folder: FolderInfo) {
        if (AllFilesAccess.isGranted()) {
            pendingFolder = folder
            showFolderPicker = true
        } else {
            pendingFolder = folder
            showPermissionDialog = true
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("astrapi sync", style = MaterialTheme.typography.titleLarge)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    if (conflictCount > 0) {
                        IconButton(onClick = onOpenConflicts) {
                            BadgedBox(badge = { Badge { Text("$conflictCount") } }) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = "$conflictCount Konflikt(e)",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
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
                )

                state.folders.isEmpty() -> EmptyState(onAddClicked = viewModel::onAddClicked)

                else -> LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                    items(state.folders, key = { it.folderId }) { item ->
                        FolderRow(
                            item = item,
                            onSyncNow = { viewModel.syncNow(item.folderId) },
                            onOpenHistory = { onOpenHistory(item.folderId) },
                            onRemove = { viewModel.onRemoveClicked(item.folderId) },
                        )
                    }
                }
            }
        }
    }

    if (state.showAddSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = viewModel::onAddDismissed, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Ordner hinzufügen", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Für dieses Gerät freigegebene Ordner",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
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
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )

                    else -> Column {
                        state.availableFolders.forEach { folder ->
                            AvailableFolderRow(
                                folder = folder,
                                onClick = { startBindingFlow(folder) },
                            )
                        }
                        Spacer(modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        }
    }

    state.pendingDeleteConfirmation?.let { pending ->
        DeleteConfirmationDialog(
            pending = pending,
            onConfirm = viewModel::confirmPendingDeletions,
            onDismiss = viewModel::dismissPendingDeletions,
        )
    }

    state.pendingUnbind?.let { item ->
        UnbindConfirmationDialog(
            item = item,
            onConfirm = viewModel::confirmUnbind,
            onDismiss = viewModel::dismissUnbind,
        )
    }

    if (showPermissionDialog) {
        AllFilesAccessDialog(
            onConfirm = {
                showPermissionDialog = false
                context.startActivity(AllFilesAccess.settingsIntent(context))
            },
            onDismiss = {
                showPermissionDialog = false
                pendingFolder = null
            },
        )
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            onPicked = { dir ->
                val folder = pendingFolder
                showFolderPicker = false
                pendingFolder = null
                if (folder != null) viewModel.bindFolder(folder, dir)
            },
            onDismiss = {
                showFolderPicker = false
                pendingFolder = null
            },
        )
    }
}

/** Seit T-340-SYNC Voraussetzung fürs Ordner-Binden -- lässt sich anders
 * als normale Laufzeit-Permissions nicht per Systemdialog gewähren,
 * sondern nur über einen eigenen Settings-Screen (siehe
 * AllFilesAccess.settingsIntent()). Erklärt daher zuerst, wohin es geht,
 * statt den Nutzer unangekündigt in die Systemeinstellungen zu schicken. */
@Composable
private fun AllFilesAccessDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zugriff auf alle Dateien nötig") },
        text = {
            Text(
                "Um einen Ordner zu verbinden, benötigt astrapi sync die Berechtigung " +
                    "\"Alle Dateien verwalten\" -- damit Änderungen sofort erkannt werden " +
                    "können (statt nur alle paar Minuten). Auf der folgenden Seite bitte " +
                    "den Schalter für astrapi sync aktivieren.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Einstellungen öffnen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Reiner Kategorie-Farbpunkt statt der früheren Gruppen-Trennzeilen (siehe
 * T-317-SYNC) -- fehlertolerant bei kaputtem Hex-String, analog zur
 * gleichnamigen Server-Semantik (nur Anzeige, keine Gruppierung/Sortierung
 * mehr). */
private fun String?.toComposeColorOrNull(): Color? =
    this?.let { runCatching { Color(AndroidColor.parseColor(it)) }.getOrNull() }

@Composable
private fun ColorDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** Zeigt, welche Pfade der zuvor abgebrochene Lauf gelöscht hätte (siehe
 * SyncEngine.MAX_AUTO_DELETE / T-203-SYNC) -- ohne diese Liste sah der
 * Nutzer bisher nur die Gesamtzahl im statusText ("Abgebrochen: 10
 * Löschungen ..."), ohne zu wissen welche Dateien betroffen sind oder wie
 * er den Sync fortsetzen kann. */
@Composable
private fun DeleteConfirmationDialog(
    pending: PendingDeleteConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val total = pending.wouldDeleteLocal.size + pending.wouldDeleteRemote.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Löschungen bestätigen") },
        text = {
            Column {
                Text(
                    "Dieser Sync würde $total Datei(en) löschen -- mehr als die " +
                        "Sicherheitsgrenze erlaubt. Das kann auch bedeuten, dass der Server " +
                        "gerade nur vorübergehend nichts liefert. Vor dem Fortfahren prüfen:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.padding(top = 12.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    pending.wouldDeleteLocal.forEach { path ->
                        Text("Lokal: $path", style = MaterialTheme.typography.bodySmall)
                    }
                    pending.wouldDeleteRemote.forEach { path ->
                        Text("Server: $path", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Trotzdem löschen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

/** Trennt nur die Kopplung im Sinne von FolderListViewModel.confirmUnbind()
 * -- der Text stellt daher explizit klar, dass der lokale Ordnerinhalt
 * erhalten bleibt, um Verwechslung mit einer echten Datei-Löschung
 * (siehe DeleteConfirmationDialog) zu vermeiden. */
@Composable
private fun UnbindConfirmationDialog(
    item: FolderUiItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Synchronisation entfernen?") },
        text = {
            Text(
                "Die Synchronisation für \"${item.description}\" wird entfernt. " +
                    "Der lokale Ordner \"${File(item.boundPath).name}\" bleibt mitsamt " +
                    "seinem Inhalt unverändert erhalten -- nur die Kopplung zum Server " +
                    "wird aufgehoben.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Entfernen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun EmptyState(onAddClicked: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(88.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Default.CreateNewFolder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            "Noch keine Ordner verbunden",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            "Verbinde einen für dieses Gerät freigegebenen Ordner mit\neinem Speicherort auf deinem Handy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onAddClicked, modifier = Modifier.padding(top = 24.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Ordner verbinden")
        }
    }
}

@Composable
private fun AvailableFolderRow(folder: FolderInfo, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Text(folder.description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            folder.color.toComposeColorOrNull()?.let { ColorDot(it) }
        }
    }
}

@Composable
private fun FolderRow(
    item: FolderUiItem,
    onSyncNow: () -> Unit,
    onOpenHistory: () -> Unit,
    onRemove: () -> Unit,
) {
    // Seit T-340-SYNC ein echter Dateisystem-Pfad statt einer SAF-Tree-Uri
    // -- der Anzeigename ist damit einfach der letzte Pfadbestandteil,
    // ohne ContentResolver-Abfrage.
    val folderName = File(item.boundPath).name
    // "Noch nie synchronisiert" nur zeigen, wenn wirklich noch kein
    // erfolgreicher Lauf stattfand. lastSyncedAt wird inzwischen bei jedem
    // nicht abgebrochenen Lauf gesetzt (auch bei "Bereits aktuell", siehe
    // FolderListViewModel.runSync/SyncWorker.doWork) -- der statusText-
    // Fallback greift daher nur noch im Sonderfall eines abgebrochenen
    // allerersten Laufs (MAX_AUTO_DELETE-Bestätigungsdialog), wo weder ein
    // Zeitstempel noch "Noch nie synchronisiert" sinnvoll wären.
    val lastSyncedText = item.lastSyncedAt?.let {
        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    } ?: if (item.statusText == null) "Noch nie synchronisiert" else null

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.description, style = MaterialTheme.typography.titleMedium)
                        item.color.toComposeColorOrNull()?.let { dotColor ->
                            Spacer(modifier = Modifier.width(6.dp))
                            ColorDot(dotColor)
                        }
                    }
                    Text(
                        folderName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (lastSyncedText != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                lastSyncedText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
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
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Synchronisation entfernen")
                }
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Default.History, contentDescription = "Verlauf")
                }
                Spacer(modifier = Modifier.width(4.dp))
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
