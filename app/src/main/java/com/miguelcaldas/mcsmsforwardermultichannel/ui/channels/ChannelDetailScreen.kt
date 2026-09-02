package com.miguelcaldas.mcsmsforwardermultichannel.ui.channels

import android.telephony.PhoneNumberUtils
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miguelcaldas.mcsmsforwardermultichannel.R
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailScreen(type: ChannelType, onBack: () -> Unit, viewModel: ChannelsViewModel = viewModel()) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun toast(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(type.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back_24), contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
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
            when (type) {
                ChannelType.WhatsApp -> WhatsAppForm(viewModel, onSaved = { onBack() }, toast = { toast(it) })
                ChannelType.Telegram -> TelegramForm(viewModel, onSaved = { onBack() }, toast = { toast(it) })
                ChannelType.Sms -> SmsForm(viewModel, onSaved = { onBack() }, toast = { toast(it) })
            }
        }
    }
}

@Composable
private fun EnabledRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text("Enabled") },
        supportingContent = { Text("Forward matching messages through this channel.") },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    onToggle(checked)
                },
            )
        },
    )
}

@Composable
private fun WhatsAppForm(viewModel: ChannelsViewModel, onSaved: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val initial = remember { WhatsAppConfig.load(context) }
    // The field is pre-filled with a bullet mask the same length as the stored token.
    // Leaving the mask untouched keeps the saved token; clearing it deletes the token;
    // typing over it replaces the token. The real secret never enters Compose state.
    val tokenMask = remember { "\u2022".repeat(initial.accessToken.length) }

    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var phoneNumberId by rememberSaveable { mutableStateOf(initial.phoneNumberId) }
    var recipient by rememberSaveable { mutableStateOf(initial.recipient) }
    var token by rememberSaveable { mutableStateOf(tokenMask) }

    val recipientError = recipient.isNotEmpty() && !PhoneNumberUtils.isWellFormedSmsAddress(recipient)

    EnabledRow(enabled) { checked ->
        enabled = checked
    }

    OutlinedTextField(
        value = phoneNumberId,
        onValueChange = { phoneNumberId = it },
        label = { Text("WhatsApp Phone Number ID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("Access token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = recipient,
        onValueChange = { recipient = it },
        label = { Text("WhatsApp Recipient Phone Number") },
        singleLine = true,
        isError = recipientError,
        supportingText = if (recipientError) {
            { Text("Doesn't look like a valid phone number") }
        } else {
            { Text("Enter the full number with country code and no +, e.g. 351912345678.") }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )

    FormActions(
        onTest = {
            val testToken = if (token == tokenMask) initial.accessToken else token
            toast(viewModel.sendWhatsAppTest(phoneNumberId, recipient, testToken))
        },
        onSave = {
            viewModel.saveWhatsApp(enabled, phoneNumberId, recipient, if (token != tokenMask) token else null)
            onSaved()
        },
    )
}

@Composable
private fun TelegramForm(viewModel: ChannelsViewModel, onSaved: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val initial = remember { TelegramConfig.load(context) }
    val tokenMask = remember { "\u2022".repeat(initial.botToken.length) }

    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var chatId by rememberSaveable { mutableStateOf(initial.chatId) }
    var token by rememberSaveable { mutableStateOf(tokenMask) }

    EnabledRow(enabled) { checked ->
        enabled = checked
    }

    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("Bot token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        supportingText = {
            Text("From @BotFather, e.g. 123456:ABCDEF\u2026")
        },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = chatId,
        onValueChange = { chatId = it },
        label = { Text("Chat ID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    FormActions(
        onTest = {
            val testToken = if (token == tokenMask) initial.botToken else token
            toast(viewModel.sendTelegramTest(chatId, testToken))
        },
        onSave = {
            viewModel.saveTelegram(enabled, chatId, if (token != tokenMask) token else null)
            onSaved()
        },
    )
}

@Composable
private fun SmsForm(viewModel: ChannelsViewModel, onSaved: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mc_sms_fwd_wa", android.content.Context.MODE_PRIVATE) }
    val initial = remember { SmsConfig.load(prefs) }

    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var destination by rememberSaveable { mutableStateOf(initial.destination) }

    val destinationError = destination.isNotEmpty() && !PhoneNumberUtils.isWellFormedSmsAddress(destination)

    EnabledRow(enabled) { checked ->
        enabled = checked
    }

    OutlinedTextField(
        value = destination,
        onValueChange = { destination = it },
        label = { Text("Destination (E.164)") },
        singleLine = true,
        isError = destinationError,
        supportingText = if (destinationError) {
            { Text("Doesn't look like a valid SMS address") }
        } else {
            { Text("The message is re-sent from this device's SIM. No token needed.") }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )

    FormActions(
        onTest = {
            toast(viewModel.sendSmsTest(destination))
        },
        onSave = {
            val warning = viewModel.saveSms(enabled, destination)
            if (warning != null) {
                toast(warning)
            }
            onSaved()
        },
    )
}

@Composable
private fun FormActions(onTest: () -> Unit, onSave: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onTest,
            modifier = Modifier.weight(1f),
        ) {
            Text("Send test")
        }
        Button(
            onClick = onSave,
            modifier = Modifier.weight(1f),
        ) {
            Text("Save")
        }
    }
}
