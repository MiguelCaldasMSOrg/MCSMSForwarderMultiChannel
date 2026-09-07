package com.miguelcaldas.mcsmsforwardermultichannel.ui.log

import android.content.Intent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miguelcaldas.mcsmsforwardermultichannel.R
import com.miguelcaldas.mcsmsforwardermultichannel.ui.theme.LogFailureDark
import com.miguelcaldas.mcsmsforwardermultichannel.ui.theme.LogFailureLight
import com.miguelcaldas.mcsmsforwardermultichannel.ui.theme.LogFilteredDark
import com.miguelcaldas.mcsmsforwardermultichannel.ui.theme.LogFilteredLight
import com.miguelcaldas.mcsmsforwardermultichannel.ui.theme.LogRemoteDark
import com.miguelcaldas.mcsmsforwardermultichannel.ui.theme.LogRemoteLight
import com.miguelcaldas.mcsmsforwardermultichannel.ui.theme.LogSuccessDark
import com.miguelcaldas.mcsmsforwardermultichannel.ui.theme.LogSuccessLight
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogScreen(viewModel: LogViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val clearState by viewModel.clearState.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(clearState) {
        if (clearState == LogClearState.Failed) {
            snackbarHostState.showSnackbar("Could not clear logs")
            viewModel.clearFailureShown()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                "Most recent first. Entries older than ~1 month are auto-removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Filter-rejected entries contain the full raw sender and message.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == LogFilter.All, onClick = { viewModel.setFilter(LogFilter.All) }, label = { Text("All") })
                FilterChip(selected = filter == LogFilter.SendOk, onClick = { viewModel.setFilter(LogFilter.SendOk) }, label = { Text("Send OK") })
                FilterChip(selected = filter == LogFilter.SendFailed, onClick = { viewModel.setFilter(LogFilter.SendFailed) }, label = { Text("Failed") })
                FilterChip(selected = filter == LogFilter.FilterRejected, onClick = { viewModel.setFilter(LogFilter.FilterRejected) }, label = { Text("Filter rejected") })
                FilterChip(selected = filter == LogFilter.RemoteRules, onClick = { viewModel.setFilter(LogFilter.RemoteRules) }, label = { Text("Remote rules") })
                FilterChip(selected = filter == LogFilter.Boot, onClick = { viewModel.setFilter(LogFilter.Boot) }, label = { Text("Boot/Tile") })
            }

            Spacer(Modifier.height(12.dp))

            if (logs.isEmpty()) {
                val empty = if (filter == LogFilter.All) "No logs yet." else "No entries match this filter."
                Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            } else {
                val annotated = remember(logs, dark) { buildLogText(logs, dark) }
                SelectionContainer {
                    Text(annotated, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    val payload = viewModel.visibleLogs().joinToString("\n")
                    if (payload.isNotEmpty()) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "MC SMS Forwarder log")
                            putExtra(Intent.EXTRA_TEXT, payload)
                        }
                        context.startActivity(Intent.createChooser(send, "Share log"))
                    }
                }) {
                    Icon(painterResource(R.drawable.ic_share_24), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    enabled = clearState != LogClearState.Clearing,
                    onClick = { viewModel.clear() },
                ) {
                    Text(if (clearState == LogClearState.Clearing) "Clearing\u2026" else "Clear logs")
                }
            }
        }
    }
}

// Color each entry by its generated prefix so uncontrolled message text cannot spoof a category.
private fun buildLogText(logs: List<String>, dark: Boolean): AnnotatedString {
    val success: Color = if (dark) LogSuccessDark else LogSuccessLight
    val failure: Color = if (dark) LogFailureDark else LogFailureLight
    val filtered: Color = if (dark) LogFilteredDark else LogFilteredLight
    val remote: Color = if (dark) LogRemoteDark else LogRemoteLight
    return buildAnnotatedString {
        logs.forEach { entry ->
            val color = when (classifyLogEntry(entry)) {
                LogEntryType.FilterRejected -> filtered
                LogEntryType.RemoteRule -> remote
                LogEntryType.SendFailed -> failure
                LogEntryType.SendOk -> success
                LogEntryType.Boot, LogEntryType.Other -> Color.Unspecified
            }
            if (color == Color.Unspecified) {
                append(entry)
            } else {
                withStyle(SpanStyle(color = color)) {
                    append(entry)
                }
            }
            append("\n\n")
        }
    }
}
