package com.miguelcaldas.mcsmsforwardermultichannel.ui.channels

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miguelcaldas.mcsmsforwardermultichannel.R
import com.miguelcaldas.mcsmsforwardermultichannel.util.ProvisioningBundle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private sealed interface ProvisioningInput {
    data class File(val uri: Uri): ProvisioningInput
    data class Code(val value: String): ProvisioningInput
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(onOpenChannel: (ChannelType) -> Unit, onOpenFilters: () -> Unit, viewModel: ChannelsViewModel = viewModel()) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val importState by viewModel.provisioningImportState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingInput by remember { mutableStateOf<ProvisioningInput?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var overflowExpanded by remember { mutableStateOf(false) }
    var pasteDialogVisible by remember { mutableStateOf(false) }
    var pastedCode by remember { mutableStateOf("") }
    val bundlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            pendingInput = ProvisioningInput.File(it)
            passphrase = ""
        }
    }
    val scanner = remember(context) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }
    fun openBundlePicker() {
        // A custom extension has no portable MIME mapping across document providers;
        // the importer strictly validates and authenticates the selected file itself.
        bundlePicker.launch(arrayOf("*/*"))
    }
    fun openQrScanner() {
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue
                if (value.isNullOrBlank()) {
                    viewModel.reportProvisioningMessage("The scanned QR code did not contain configuration data.")
                } else {
                    pendingInput = ProvisioningInput.Code(value)
                    passphrase = ""
                }
            }
            .addOnFailureListener {
                viewModel.reportProvisioningMessage("Could not start the QR scanner.")
            }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(importState) {
        val complete = importState as? ProvisioningImportState.Complete ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(complete.message)
        viewModel.clearProvisioningImportResult()
    }

    pendingInput?.let { input ->
        ProvisioningPassphraseDialog(
            passphrase = passphrase,
            onPassphraseChange = { passphrase = it },
            onDismiss = {
                passphrase = ""
                pendingInput = null
            },
            onImport = {
                pendingInput = null
                val submittedPassphrase = passphrase
                passphrase = ""
                when (input) {
                    is ProvisioningInput.File ->
                        viewModel.importProvisioningBundle(input.uri, submittedPassphrase)
                    is ProvisioningInput.Code ->
                        viewModel.importProvisioningCode(input.value, submittedPassphrase)
                }
            },
        )
    }

    if (pasteDialogVisible) {
        PasteProvisioningCodeDialog(
            value = pastedCode,
            onValueChange = { pastedCode = it },
            onDismiss = {
                pastedCode = ""
                pasteDialogVisible = false
            },
            onContinue = {
                pendingInput = ProvisioningInput.Code(pastedCode)
                pastedCode = ""
                pasteDialogVisible = false
                passphrase = ""
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Channels") },
                actions = {
                    Box {
                        IconButton(
                            onClick = { overflowExpanded = true },
                            enabled = importState != ProvisioningImportState.Importing,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert_24),
                                contentDescription = "More options",
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Choose file or cloud drive") },
                                enabled = importState != ProvisioningImportState.Importing,
                                onClick = {
                                    overflowExpanded = false
                                    openBundlePicker()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Scan configuration QR code") },
                                enabled = importState != ProvisioningImportState.Importing,
                                onClick = {
                                    overflowExpanded = false
                                    openQrScanner()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Paste encrypted import code") },
                                enabled = importState != ProvisioningImportState.Importing,
                                onClick = {
                                    overflowExpanded = false
                                    pasteDialogVisible = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            channels.forEach { summary ->
                ChannelCard(
                    summary = summary,
                    onClick = {
                        onOpenChannel(summary.type)
                    },
                    onToggle = { enabled ->
                        viewModel.setEnabled(summary.type, enabled)
                    },
                )
            }

            Card(onClick = onOpenFilters) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Senders, rules & template", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose which senders are allowed, the match rules, and the forwarding template. Applies to every channel.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PasteProvisioningCodeDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste encrypted import code") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Import code") },
                minLines = 5,
                maxLines = 8,
                supportingText = {
                    Text("Transfer the code separately from its passphrase.")
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onContinue,
                enabled = value.isNotBlank() && value.length <= ProvisioningBundle.MAX_IMPORT_CODE_CHARS,
            ) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ProvisioningPassphraseDialog(
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import encrypted configuration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the bundle passphrase. Supplied single-value settings are updated; senders and rules are merged without duplicates or deletions.",
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text("Bundle passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        Text(
                            "${ProvisioningBundle.MINIMUM_PASSPHRASE_LENGTH} to " +
                                "${ProvisioningBundle.MAXIMUM_PASSPHRASE_LENGTH} characters",
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onImport,
                enabled = passphrase.length in
                    ProvisioningBundle.MINIMUM_PASSPHRASE_LENGTH..
                    ProvisioningBundle.MAXIMUM_PASSPHRASE_LENGTH,
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelCard(summary: ChannelSummary, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(summary.type.iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(summary.type.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    summary.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = toneColor(summary.tone),
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = summary.enabled,
                onCheckedChange = { enabled ->
                    onToggle(enabled)
                },
            )
        }
    }
}

@Composable
private fun toneColor(tone: ChannelTone): Color {
    return when (tone) {
        ChannelTone.READY -> MaterialTheme.colorScheme.primary
        ChannelTone.INCOMPLETE -> MaterialTheme.colorScheme.error
        ChannelTone.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
