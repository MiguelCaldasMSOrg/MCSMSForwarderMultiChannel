package com.miguelcaldas.mcsmsforwardermultichannel.ui.status

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miguelcaldas.mcsmsforwardermultichannel.R
import com.miguelcaldas.mcsmsforwardermultichannel.util.BuildMetadata
import kotlinx.coroutines.launch

internal fun HealthAction.runtimePermission(): String? = when (this) {
    HealthAction.GRANT_RECEIVE_SMS -> Manifest.permission.RECEIVE_SMS
    HealthAction.GRANT_SEND_SMS -> Manifest.permission.SEND_SMS
    HealthAction.BATTERY_SETTINGS,
    HealthAction.OPEN_CHANNELS,
    HealthAction.OPEN_FILTERS,
    -> null
}

internal fun permissionDeniedMessage(permission: String): String = when (permission) {
    Manifest.permission.RECEIVE_SMS -> "SMS receiving permission was not granted."
    Manifest.permission.SEND_SMS -> "SMS sending permission was not granted."
    else -> "Permission was not granted."
}

// Immediate forwarding is the app's core task-automation function and must remain available
// when an SMS arrives while the device is in Doze. The system still requires user confirmation.
@SuppressLint("BatteryLife")
private fun requestBatteryOptimizationExemption(context: Context): Boolean {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun openApplicationSettings(context: Context): Boolean {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(onOpenChannels: () -> Unit, onOpenFilters: () -> Unit, viewModel: StatusViewModel = viewModel()) {
    val context = LocalContext.current
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val blockers by viewModel.blockers.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingPermission by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permission = pendingPermission
        pendingPermission = null
        viewModel.refresh()
        if (!granted && permission != null) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = permissionDeniedMessage(permission),
                    actionLabel = "App settings",
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
                if (
                    result == SnackbarResult.ActionPerformed &&
                    !openApplicationSettings(context)
                ) {
                    snackbarHostState.showSnackbar("App settings are unavailable.")
                }
            }
        }
    }

    fun runHealthAction(action: HealthAction) {
        val permission = action.runtimePermission()
        if (permission != null) {
            pendingPermission = permission
            permissionLauncher.launch(permission)
            return
        }
        when (action) {
            HealthAction.BATTERY_SETTINGS -> {
                if (!requestBatteryOptimizationExemption(context)) {
                    scope.launch {
                        snackbarHostState.showSnackbar("Battery optimization settings are unavailable.")
                    }
                }
            }
            HealthAction.OPEN_CHANNELS -> {
                onOpenChannels()
            }
            HealthAction.OPEN_FILTERS -> {
                onOpenFilters()
            }
            HealthAction.GRANT_RECEIVE_SMS,
            HealthAction.GRANT_SEND_SMS,
            -> Unit
        }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Status") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MasterSwitchCard(
                enabled = masterEnabled,
                ready = blockers.isEmpty(),
                onToggle = { checked ->
                    viewModel.setMasterEnabled(checked)
                },
            )

            HealthSection(
                blockers = blockers,
                onFix = { action ->
                    runHealthAction(action)
                },
            )

            StatsCard(
                count = stats.count,
                first = stats.first,
                last = stats.last,
                onReset = {
                    viewModel.resetStats()
                },
            )

            BuildInfoCard()
        }
    }
}

@Composable
private fun BuildInfoCard() {
    OutlinedCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_sms_forwarder),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(BuildMetadata.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    BuildMetadata.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MasterSwitchCard(enabled: Boolean, ready: Boolean, onToggle: (Boolean) -> Unit) {
    val containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val title = if (enabled) "Forwarding active" else "Paused"
    val subtitle = when {
        !enabled -> "Incoming messages are ignored while paused."
        ready -> "Matching messages are forwarded to every enabled channel."
        else -> "Switched on, but some setup is still needed below."
    }

    Card(colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    onToggle(checked)
                },
            )
        }
    }
}

@Composable
private fun HealthSection(blockers: List<HealthItem>, onFix: (HealthAction) -> Unit) {
    if (blockers.isEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_check_circle_24), contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("All systems go", style = MaterialTheme.typography.titleMedium)
                    Text("Everything is configured and ready to forward.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        return
    }

    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                "Needs attention",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            blockers.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_warning_filled_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(item.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { onFix(item.action) }) {
                        Text(item.fixLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(count: String, first: String, last: String, onReset: () -> Unit) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Forwarded", style = MaterialTheme.typography.labelMedium)
                    Text(count, style = MaterialTheme.typography.displaySmall)
                }
                TextButton(onClick = onReset) {
                    Text("Reset")
                }
            }
            Spacer(Modifier.height(8.dp))
            StatRow("First", first)
            StatRow("Last", last)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}
