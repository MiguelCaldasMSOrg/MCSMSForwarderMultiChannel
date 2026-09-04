# Copilot Instructions — MC SMS Forwarder (Multi-Channel)

## Build

```powershell
.\gradlew.bat :app:assembleDebug          # build debug APK
.\gradlew.bat :app:installDebug           # build + install on connected device/emulator
.\gradlew.bat :app:testDebugUnitTest       # run JVM unit tests
```

Focused JVM tests cover the concurrency/deadline helper and encrypted provisioning format,
including PowerShell interoperability. No instrumentation suite is configured; use Gradle lint
for Android static checks.

The build uses AGP 9.3.2 with built-in Kotlin 2.2.10, Gradle 9.5, `compileSdk` 37,
`targetSdk` 36, AndroidX Core 1.19, Lifecycle 2.11, Compose BOM 2026.08.00
(Material 3 follows the BOM), and Navigation 2.10.

## Architecture

Single-module Android app (`:app`), Kotlin. The UI is **Jetpack Compose** (Material 3): a single
`MainActivity: ComponentActivity` calls `setContent { MCSmsForwarderTheme { AppRoot() } }`, and
`AppRoot` hosts a `NavController` that routes between screens (status, channels, filters, log).
Each screen has an `AndroidViewModel` exposing `StateFlow` draft state.

**Pipeline** (`SmsReceiver`): incoming SMS → master kill-switch (`MasterSwitchStore.load`, default ON),
default ON) → bail if **no channel is operational** (each channel: enabled toggle on AND credentials
present) or senders / regexes are empty → reassemble multipart → **SMS loop guard** (drop messages
from the SMS forward destination via `PhoneNumberUtils.areSamePhoneNumber`; SMS channel only) →
match sender against allowed list via `SenderMatcher` (`PhoneNumberUtils.areSamePhoneNumber` +
case-insensitive exact match for alphanumeric IDs; accents remain significant) → normalize body with
`TextNormalizer.normalizeForMatching` (NFD + strip combining marks + lowercase) → compile each
unchanged regex source and match any (`runCatching` per pattern; invalid patterns silently skip;
rules must therefore be lowercase and accent-free) → apply
optional `ForwardTemplate` (`%s`/`%t`/`%m` tokens) → `goAsync()` keeps the receiver alive →
**fan out the same body to every operational channel** (`WhatsAppCloudChannel`, `TelegramChannel`,
`SmsChannel`). A shared `AtomicInteger remaining` counts pending channel callbacks; each
channel's `onComplete(success)` decrements it, and when it reaches zero the receiver records
**exactly one** forward stat (if any channel succeeded) via `ForwardStatsStore.recordForward` and
calls `pending.finish()`. The original (accented, cased) body is what gets forwarded —
normalization is only for matching.

**Loop guard (SMS only).** A message arriving from the SMS forward destination is suppressed so an
SMS→SMS echo cannot bounce indefinitely. WhatsApp and Telegram run on a different transport and
cannot re-trigger the pipeline, so the guard is scoped to the SMS channel's destination.

**Channel pattern.** Each channel is a `XxxConfig` immutable data class (`enabled` flag + fields +
`hasCredentials`/`isOperational` + `load(prefs/context)` + `KEY_*` consts) paired with a `XxxChannel`
`object` exposing `send(context, config, body, onComplete: (Boolean) -> Unit = {}): Boolean`. Channels log
`SEND OK [Channel]` / `SEND FAILED [Channel]` and never log secrets. **Channels never record
stats** — `SmsReceiver` owns the single increment per matched SMS.

**WhatsApp Cloud channel** (`util/WhatsAppCloudChannel.kt`): `object` with a cached daemon
`Executor` named `wa-sender`; overlapping sends run concurrently rather than queueing or failing busy.
The message template is **fixed in code** (constants `TEMPLATE_NAME`,
`TEMPLATE_LANGUAGE`, `TEMPLATE_USER`) — it is intentionally not selectable in the config
or the UI. It points at the approved `titled_forwarded_sms` template, whose body has two `{{n}}`
parameters: `{{1}}` is bound to the fixed user `TEMPLATE_USER` (`"Miguel"`) and `{{2}}` to the
forwarded SMS body. `send`
builds the template JSON via `buildPayload`, strips the leading `+` from the recipient, opens
`HttpURLConnection` to
`https://graph.facebook.com/v21.0/{phoneNumberId}/messages`, writes the body with
`setFixedLengthStreamingMode`, sets bearer authorization, and applies 8 s connect/read safeguards.
The receiver-facing completion callback has an 8.5 s overall deadline; if that expires, delivery
is logged as unknown while the transport finishes unwinding.
then logs `SEND OK [WhatsApp] → {recipient} (HTTP {code})` or the matching `SEND FAILED` with the
Meta `error.{code,type,message}` summary. The access token never appears in logs.

**Telegram channel** (`util/TelegramChannel.kt`): sibling `object` on a cached `tg-sender`
daemon executor with the same timeout behavior.
POSTs `chat_id`+`text` (web previews disabled) to `https://api.telegram.org/bot{token}/sendMessage`
(token URL-encoded), logs `SEND OK/FAILED [Telegram]` with the Telegram `error_code`+`description`
summary. The bot token never appears in logs.

**SMS channel** (`util/SmsChannel.kt`): re-sends through the device modem via
`SmsManager.sendMultipartTextMessage` (obtained with `getSystemService(SmsManager::class.java)`,
API 31+). `onComplete(true)` fires when the message is successfully *handed to the modem* (no
exception), mirroring how the HTTP channels treat 2xx — neither guarantees delivery. A private,
dynamically-registered `BroadcastReceiver` (action `…SMS_SENT_RESULT`, `RECEIVER_NOT_EXPORTED`)
logs the modem's asynchronous per-segment result (`SEND OK [SMS]`, `no service`, `radio off`, …).
Needs the `SEND_SMS` permission. The only "credential" is the destination number; there is no token.

**Master switch**: `MasterSwitchTileService` (Quick Settings tile) and the Status screen's Compose
`Switch` both write the same `master_enabled` pref. `StatusViewModel` registers an
`OnSharedPreferenceChangeListener`, so flipping the tile reactively updates the on-screen switch
(no `onResume` re-sync needed).

**Battery exemption**: the Status readiness checklist treats
`PowerManager.isIgnoringBatteryOptimizations(packageName)` as required. Its action calls the
dedicated `requestBatteryOptimizationExemption` helper, which directly launches Android's
package-specific `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` confirmation. The helper has a
scoped, documented `BatteryLife` suppression because immediate forwarding is core task-automation
behavior. It catches `ActivityNotFoundException` and returns `false`, allowing the Compose screen
to show a snackbar; do not reintroduce `resolveActivity`, which triggers package-visibility lint.

**Boot**: `BootReceiver` exists purely to make the framework load the package on
`BOOT_COMPLETED` (no real work; just a log line) so the manifest SMS receiver is warm before the
first message.

**Persistence**: non-secret state uses a single `SharedPreferences` file named `mc_sms_fwd_wa`; the
two channel tokens use the separate encrypted store described below. There is no database. Lists
(senders, regexes) are newline-delimited strings. Logs use a
`timestamp\x1Fmessage` format with auto-pruning (35 days / 2000 entries); `LogUtils.addToLog`
collapses CR/LF/`\x1F` runs in the message to a space so multi-line bodies can't corrupt the
line-oriented format. WhatsApp credentials live under keys defined in `WhatsAppConfig`:
`waPhoneNumberId`, `waRecipient`, `waEnabled` (default true). The WhatsApp API template is fixed in
code (see the WhatsApp channel above); the separate shared forwarding template uses
`forwardTemplate`. Telegram: `tgEnabled` (default false), `tgChatId` (`TelegramConfig`). SMS:
`smsEnabled` (default false) and
`forwardTo` — the destination number (`SmsConfig`). **Secrets (the WhatsApp access token `waAccessToken` and
the Telegram bot token `tgBotToken`) are NOT in this file** — `SecureStore` encrypts them with
AES/GCM using a key held by Android Keystore, then stores the ciphertext in the private
`mc_sms_fwd_secure` preferences file. Configs read tokens by calling
`SecureStore.read(context, …)`, so `WhatsAppConfig.load`/`TelegramConfig.load` take a `Context`
(not a `SharedPreferences`).

**Encrypted provisioning**: the Channels screen can import a `.mcsmsconfig` bundle generated by
`tools/New-ProvisioningBundle.ps1`. The PowerShell 7 helper stays in one file, prompts securely by
default, refuses repository-local inputs/outputs, and encrypts a versioned JSON payload using
PBKDF2-HMAC-SHA256 plus AES-256-GCM. It can carry the master switch, WhatsApp, Telegram, SMS,
allowed senders, regex rules, and the shared forwarding template. `ProvisioningBundle` bounds and
validates the envelope before decrypting, writes tokens through `SecureStore.writeAll`, and writes
only supplied scalar fields to the normal preferences. Sender/rule lists are merge-only:
existing entries are never deleted or reordered, sender equivalence follows `SenderMatcher`, and
regex duplicates use exact equality. Reapplying a bundle is idempotent. Imports never log or retain
secrets, and manual edits remain available afterward. Applying a bundle first disables the master
switch and included channels, commits the public fields, commits encrypted secrets, and only then
restores the requested enabled states; rollback restores the prior snapshot, while an unrecoverable
partial write stays disabled. The `mc_sms_fwd_secure` preferences file is excluded from Android
backup and device transfer because its Keystore key cannot be transferred.

**Screens** are Compose, each backed by an `AndroidViewModel`. Manual form edits mutate in-memory
draft `StateFlow`s and are persisted only when the user taps the screen's explicit **Save** button
(no debounced auto-save); encrypted bundle import is a separate explicit action that persists after
passphrase confirmation. On the Filters screen the allowed senders and message-format rules are each
rendered as a list of editable `OutlinedTextField` rows with a per-row delete button (order is not
significant); blank rows are dropped on save and ignored by the live pipeline. Channel **Send test**
actions use the currently displayed draft values without saving them.

## Conventions

- Utility stores, matchers, formatters, channels, and provisioning code in `util/` are Kotlin
  `object` singletons that take `SharedPreferences` or `Context` directly; there is no dependency
  injection. `WhatsAppConfig`, `TelegramConfig`, and `SmsConfig` are immutable data classes loaded
  by their companion objects.
- **Edge-to-edge** is enabled once in `MainActivity.onCreate` via `enableEdgeToEdge()`; Compose
  `Scaffold` + window-inset padding handle the rest per screen.
- **Regex matching** uses `TextNormalizer.normalizeForMatching` to transform the message body only
  (NFD + strip combining marks + lowercase). Regex source is unchanged, so patterns must be written
  lowercase and accent-free. Invalid regexes are silently treated as non-matches.
- **Version catalog** (`gradle/libs.versions.toml`) manages all dependency and SDK versions;
  `app/build.gradle.kts` references them via `libs.*`.
- **Kotlin formatting preference**: keep inheritance/type colons tight for class declarations
  (`class Foo: Parent()`), and keep short immutable config/data classes on one line when the
  full primary constructor remains readable.
- Release signing is opt-in via Gradle properties (`RELEASE_KEYSTORE_PATH`, etc.). No keystore
  or access token is committed to the repository.
- The WhatsApp access token and Telegram bot token are stored **encrypted at rest** via
  `SecureStore` (AES/GCM with an Android Keystore-held key), never in the plaintext `mc_sms_fwd_wa` prefs —
  never write them to logs, never include them in error messages, never paste them into bug
  reports. In the Settings UI the token fields are **write-only**: the saved value is never
  re-displayed (the field loads with a bullet mask); typing replaces the stored token, while
  leaving the mask untouched keeps the existing one. The SMS channel has no secret (it uses the
  device modem).
- **`FiltersViewModel.runTest` is a dry-run mirror of the live pipeline.** The Filters screen has
  an inline "Test a message" card (sample sender + message) that subjects the input to the
  **currently displayed (possibly unsaved) draft** filters — draft senders, draft rules (match any,
  invalid patterns skipped), draft template — plus the live SMS destination loop guard and the
  same channels `SmsReceiver` does (all three, via each config's `isOperational`). The last-used sender/message are persisted (`lastTestSender`,
  `lastTestMessage`); the sender otherwise defaults to the first phone-like entry in the senders
  list. If you add or change a channel or the matching logic, update `runTest` so the two cannot
  drift.

## Emulator test protocol

Real, temporary WhatsApp and Telegram credentials may be used for emulator functional testing
when the user has placed them in
`C:\Projects\MCSMSForwarderMultiChannelTemp\shortlived.json`. The expected JSON keys are
`waPhoneNumberId`, `waAccessToken`, `waRecipient`, `tgBotToken`, and `tgChatId`.

- The credential file is user-managed, read-only session material. Automation must never modify or
  delete it; the user deletes it at the end of the development session. It may be reused by
  multiple sequential tests.
- Before reading it, verify its resolved path is outside every Git checkout/worktree and outside
  Copilot session/artifact storage. Never call a viewing tool on it, print it, place it in chat,
  copy it into the repository, or expose values in command text/output.
- Load the JSON only inside a local PowerShell process with output suppressed. Use ADB to focus and
  populate the real Compose fields, then tap **Save**, so tokens follow the production
  `SecureStore`/Android Keystore path. Do not add debug importers, BuildConfig secrets, resources,
  environment-variable credentials, clipboard staging, or repository-local secret files.
- Run only the real sends needed for the requested verification. For incoming emulator SMS, use
  the telephony path (`adb emu sms send`), not a spoofed `SMS_RECEIVED` broadcast.
- Raw screenshots and UI hierarchy dumps from real-credential runs must be written only to a
  temporary directory outside the repository. Strongly pixelate sensitive regions before copying
  an image into `docs/screenshots`: tokens/masks, phone-number IDs, recipients, chat IDs, sender
  identifiers, message bodies, OTPs, and destinations. Preserve channel names, success/failure
  labels, HTTP codes, and timestamps.
- Strip screenshot metadata and validate only the redacted derivative. Exact-value/OCR checks must
  output pass/fail only. Copy only the redacted image into the repository, then delete its raw
  screenshot and UI hierarchy; do not delete the credential file.
- Before any commit or push, scan the working tree and Git index in memory for each exact
  credential value plus common secret patterns. Never print a matched value. If contamination is
  detected, stop and report only the affected path until it is removed and the credential rotated
  if necessary.
- At test cleanup, remove transient captures and clear emulator app data when appropriate. The
  external credential file remains untouched until the user removes it.
