package com.miguelcaldas.mcsmsforwardermultichannel.ui.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miguelcaldas.mcsmsforwardermultichannel.R
import com.miguelcaldas.mcsmsforwardermultichannel.util.RemoteSmsRulesConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderRule
import com.miguelcaldas.mcsmsforwardermultichannel.util.isValidRemoteSmsHmacKey
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersScreen(onBack: () -> Unit, viewModel: FiltersViewModel = viewModel()) {
    val senders by viewModel.senders.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val template by viewModel.template.collectAsStateWithLifecycle()
    val remoteSmsEnabled by viewModel.remoteSmsEnabled.collectAsStateWithLifecycle()
    val remoteSmsKey by viewModel.remoteSmsKey.collectAsStateWithLifecycle()
    val remoteSmsKeySaved by viewModel.remoteSmsKeySaved.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Seed the test inputs once: message from the last test (blank otherwise), sender from the
    // last test or the first phone in the current senders list.
    val initialTestSender = remember { viewModel.defaultTestSender() }
    val initialTestMessage = remember { viewModel.lastTestMessage() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Filters") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back_24), contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SendersCard(
                senders = senders,
                onUpdate = { index, value ->
                    viewModel.updateSender(index, value)
                },
                onRegexChange = { index, isRegex ->
                    viewModel.setSenderRegex(index, isRegex)
                },
                onAdd = {
                    viewModel.addSender()
                },
                onRemove = { index ->
                    viewModel.removeSenderAt(index)
                },
            )

            RulesCard(
                rules = rules,
                onUpdate = { index, value ->
                    viewModel.updateRule(index, value)
                },
                onAdd = {
                    viewModel.addRule()
                },
                onRemove = { index ->
                    viewModel.removeRuleAt(index)
                },
            )

            TemplateCard(
                template = template,
                onChange = { value ->
                    viewModel.setTemplate(value)
                },
            )

            TestCard(
                initialSender = initialTestSender,
                initialMessage = initialTestMessage,
                onRunTest = { sender, message ->
                    viewModel.runTest(sender, message)
                },
            )

            RemoteSmsRulesCard(
                enabled = remoteSmsEnabled,
                key = remoteSmsKey,
                keySaved = remoteSmsKeySaved,
                onEnabledChange = viewModel::setRemoteSmsEnabled,
                onKeyChange = viewModel::setRemoteSmsKey,
                onRemoveKey = viewModel::removeRemoteSmsKey,
            )

            Button(
                onClick = {
                    val message = viewModel.save()
                    scope.launch {
                        snackbarHostState.showSnackbar(message)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }

        }
    }
}

@Composable
private fun RemoteSmsRulesCard(
    enabled: Boolean,
    key: String,
    keySaved: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onKeyChange: (String) -> Unit,
    onRemoveKey: () -> Unit,
) {
    val isSavedMask = key == RemoteSmsRulesConfig.HMAC_KEY_MASK
    val invalidKey = key.isNotEmpty() && !isSavedMask && !isValidRemoteSmsHmacKey(key)
    val supportingText = when {
        invalidKey -> "Enter a 43-character unpadded Base64URL key."
        isSavedMask -> "Key saved. Leave the mask unchanged to keep it."
        key.isNotEmpty() && keySaved -> "New key will replace the saved key when you tap Save."
        key.isNotEmpty() -> "New key will be saved when you tap Save."
        else -> "Generate the shared key with tools/New-RemoteRuleSms.ps1."
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Remote SMS commands", style = MaterialTheme.typography.titleMedium)
            Text(
                "Authenticated SMS commands can add one literal sender, sender RegEx, or message RegEx even while forwarding is paused. Command values remain readable in the SMS.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Enable remote SMS commands", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = key,
                    onValueChange = onKeyChange,
                    label = { Text("HMAC key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = invalidKey,
                    supportingText = { Text(supportingText) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onRemoveKey,
                    enabled = key.isNotEmpty() || keySaved,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_close_24),
                        contentDescription = "Remove saved HMAC key",
                    )
                }
            }
            Text(
                "Commands: MCSMSSL = literal sender, MCSMSSR = sender RegEx, MCSMSMR = message RegEx.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendersCard(
    senders: List<SenderRule>,
    onUpdate: (Int, String) -> Unit,
    onRegexChange: (Int, Boolean) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Allowed senders", style = MaterialTheme.typography.titleMedium)
            Text(
                "Incoming sender text is lowercased and stripped of accents before matching. Write text rules lowercase and accent-free. A sender matches if any literal or full-string RegEx rule matches; phone-number literals use phone-aware comparison. With no sender rules, no sender matches.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (senders.isEmpty()) {
                Text(
                    "No senders yet — nothing will be forwarded until you add at least one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                senders.forEachIndexed { index, sender ->
                    SenderEntryRow(
                        rule = sender,
                        onValueChange = { onUpdate(index, it) },
                        onRegexChange = { onRegexChange(index, it) },
                        onRemove = { onRemove(index) },
                    )
                }
            }
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Text("Add sender")
            }
        }
    }
}

@Composable
private fun SenderEntryRow(
    rule: SenderRule,
    onValueChange: (String) -> Unit,
    onRegexChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val invalidRegex = rule.isRegex && runCatching { Regex(rule.value) }.isFailure
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = rule.value,
            onValueChange = onValueChange,
            label = { Text(if (rule.isRegex) "Sender pattern" else "Sender") },
            singleLine = true,
            isError = invalidRegex,
            supportingText = if (invalidRegex) {
                { Text("Invalid regular expression") }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = rule.isRegex,
            onClick = { onRegexChange(!rule.isRegex) },
            label = { Text("RegEx") },
        )
        IconButton(onClick = onRemove) {
            Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Remove")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RulesCard(
    rules: List<String>,
    onUpdate: (Int, String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Message format rules", style = MaterialTheme.typography.titleMedium)
            Text(
                "Incoming message text is lowercased and stripped of accents before matching. Write RegEx rules lowercase and accent-free. A message matches if any RegEx rule matches. With no message rules, no message matches.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (rules.isEmpty()) {
                Text(
                    "No rules yet — nothing will be forwarded until you add at least one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                rules.forEachIndexed { index, rule ->
                    EntryRow(
                        value = rule,
                        label = "Rule",
                        onValueChange = { onUpdate(index, it) },
                        onRemove = { onRemove(index) },
                    )
                }
            }
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Text("Add rule")
            }
        }
    }
}

@Composable
private fun EntryRow(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onRemove) {
            Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Remove")
        }
    }
}

@Composable
private fun TemplateCard(template: String, onChange: (String) -> Unit) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Forwarding template", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = template,
                onValueChange = { value ->
                    onChange(value)
                },
                label = { Text("Template (optional)") },
                supportingText = { Text("%s = source, %t = time (hh:mm:ss), %m = original message. Empty = forward as-is.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TestCard(
    initialSender: String,
    initialMessage: String,
    onRunTest: (String, String) -> FiltersViewModel.TestOutcome,
) {
    var sender by rememberSaveable { mutableStateOf(initialSender) }
    var message by rememberSaveable { mutableStateOf(initialMessage) }
    var outcome by remember { mutableStateOf<FiltersViewModel.TestOutcome?>(null) }

    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Test a message", style = MaterialTheme.typography.titleMedium)
            Text(
                "Dry-run a sample sender and message against the filters shown above. This mirrors the live pipeline and never sends anything.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = sender,
                onValueChange = { sender = it },
                label = { Text("Sample sender") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Sample message") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    outcome = onRunTest(sender, message)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test")
            }
            outcome?.let { result ->
                SelectionContainer {
                    Text(
                        result.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = toneColor(result.tone),
                    )
                }
            }
        }
    }
}

@Composable
private fun toneColor(tone: FiltersViewModel.Tone): Color {
    return when (tone) {
        FiltersViewModel.Tone.POSITIVE -> MaterialTheme.colorScheme.primary
        FiltersViewModel.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }
}
