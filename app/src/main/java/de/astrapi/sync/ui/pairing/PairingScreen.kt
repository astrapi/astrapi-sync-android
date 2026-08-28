package de.astrapi.sync.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    viewModel: PairingViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.paired) {
        if (state.paired) onPaired()
    }

    if (state.showScanner) {
        var hasCameraPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED,
            )
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasCameraPermission = granted
            if (!granted) viewModel.onScanCancelled()
        }
        LaunchedEffect(Unit) {
            if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        if (hasCameraPermission) {
            QrScanScreen(onResult = viewModel::onQrScanned, onCancel = viewModel::onScanCancelled)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Gerät koppeln", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Im Server-UI unter Geräte -> \"Gerät koppeln\" einen Pairing-Code erzeugen " +
                "und hier zusammen mit der Server-Adresse eingeben -- oder den dort " +
                "angezeigten QR-Code scannen.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        OutlinedButton(
            onClick = viewModel::onScanQrClicked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("QR-Code scannen")
        }

        Text(
            "oder von Hand eingeben",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )

        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text("Server-URL") },
            placeholder = { Text("http://sync.simpsons.lan:5004") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.pairingCode,
            onValueChange = viewModel::onPairingCodeChange,
            label = { Text("Pairing-Code") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        if (state.errorMessage != null) {
            Text(
                state.errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Button(
            onClick = viewModel::pair,
            enabled = !state.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            Text(if (state.isLoading) "Verbinde …" else "Koppeln")
        }
    }
}
