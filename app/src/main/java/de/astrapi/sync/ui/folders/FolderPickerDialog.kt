package de.astrapi.sync.ui.folders

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/** Ersetzt den bisherigen SAF-Ordnerwähler (ACTION_OPEN_DOCUMENT_TREE) --
 * seit T-340-SYNC hat die App über MANAGE_EXTERNAL_STORAGE echten
 * Rohzugriff aufs Dateisystem, ein eigener, einfacher Verzeichnisbrowser
 * ist daher robuster als der Versuch, eine SAF-Tree-Uri nachträglich in
 * einen echten Pfad umzurechnen (provider-/geräteabhängig). Startet am
 * primären externen Speicher, da MANAGE_EXTERNAL_STORAGE keinen Zugriff
 * auf andere Apps' privates Verzeichnis bietet, wohl aber auf den
 * gesamten freigegebenen Speicher. */
@Composable
fun FolderPickerDialog(
    onPicked: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val startDir = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(startDir) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val parent = currentDir.parentFile
                    IconButton(
                        onClick = { parent?.let { currentDir = it } },
                        enabled = parent != null && parent.canRead(),
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Nach oben")
                    }
                    Text(
                        currentDir.absolutePath,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.padding(top = 4.dp))

                val children = remember(currentDir) {
                    currentDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                        ?.sortedBy { it.name.lowercase() }
                        ?: emptyList()
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(children, key = { it.absolutePath }) { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentDir = dir }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(dir.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onPicked(currentDir) }) { Text("Diesen Ordner wählen") }
                }
            }
        }
    }
}
