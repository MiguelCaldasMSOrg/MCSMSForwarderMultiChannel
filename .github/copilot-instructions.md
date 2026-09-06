# Copilot Instructions — MC SMS Forwarder (Multi-Channel)

## Build

```powershell
.\gradlew.bat :app:assembleDebug          # build debug APK
.\gradlew.bat :app:installDebug           # build + install on connected device/emulator
.\gradlew.bat :app:assembleRelease        # build standard release APK
.\gradlew.bat :app:assembleMinifiedRelease # build R8-minified release APK
.\gradlew.bat :app:testDebugUnitTest       # run JVM unit tests
.\gradlew.bat :app:lint                    # run Android static/resource checks
```

Focused JVM tests cover filtering/normalization, sender rules, log classification, permission
readiness, concurrency/deadline behavior, build metadata, authenticated remote-SMS command
parsing/merging, and encrypted provisioning (including PowerShell interoperability). No
instrumentation suite is configured; use Gradle lint for Android static checks.

The build uses AGP 9.3.2 with built-in Kotlin 2.2.10, Gradle 9.5, `compileSdk` 37,
`targetSdk` 36, AndroidX Core 1.19, Lifecycle 2.11, Compose BOM 2026.08.00
(Material 3 follows the BOM), Navigation 2.10, Google Code Scanner 16.1, and ShortcutBadger 1.1.22.
Gradle's per-variant `GenerateBuildMetadataTask` generates `GeneratedBuildMetadata` at task
execution; local builds use the current time and Git `HEAD` (`-dirty` when applicable), while
`BUILD_TIMESTAMP_EPOCH_MILLIS`/`BUILD_SOURCE_REVISION` overrides support deterministic builds.

**Release variants**: `release` is the standard unminified compatibility APK.
`minifiedRelease` uses `proguard-android-optimize.txt`, `app/proguard-rules.pro`, R8
minification/obfuscation, and resource shrinking; `debug` also stays unminified. Both release
variants use the same application ID, version, signing key, source revision, and workflow build
timestamp. ShortcutBadger's vendor classes are instantiated through `Class.newInstance()`, so
their public no-argument constructors must remain in the keep rules. The tag workflow uploads
`app/build/outputs/mapping/minifiedRelease/` as a private `r8-mapping-{tag}` artifact with 90-day
retention. It also publishes `MC.SMS.Forwarder.minified.mapping.txt.gz` as a durable release asset;
use that exact tagged mapping to retrace minified stack traces. Mapping contains symbols only,
never credentials.

**Icons**: launcher fallbacks are checked-in lossless WebP files in each `mipmap-*` density.
Adaptive descriptors live in `mipmap-anydpi-v26`; Android 13+ descriptors in
`mipmap-anydpi-v33` add `ic_launcher_monochrome` for themed icons. The About card uses the
full-color density-specific `ic_sms_forwarder` drawable. The Quick Settings tile uses the
alpha-only white `ic_stat_sms_forwarder` system glyph. Do not add the duplicate PNG launcher/UI
exports, raster adaptive layers, or SVG masters to `res/`; update all checked-in density variants
together and run lint plus a debug build after icon changes.

## Architecture

Single-module Android app (`:app`), Kotlin. The UI is **Jetpack Compose** (Material 3): a single
`MainActivity: ComponentActivity` calls `setContent { MCSmsForwarderTheme { AppRoot() } }`, and
`AppRoot` hosts a `NavController` that routes between screens (status, channels, filters, log).
Each screen has an `AndroidViewModel` exposing `StateFlow` draft state.

**Pipeline** (`SmsReceiver`): incoming SMS → reassemble multipart → intercept a reserved remote-SMS
rule command before all forwarding gates (see below) → master kill-switch (`MasterSwitchStore.load`,
default ON) → bail if **no channel is operational** (each channel: enabled toggle on AND credentials
present) → **SMS loop guard** (drop messages
from the SMS forward destination via `PhoneNumberUtils.areSamePhoneNumber`; SMS channel only) →
normalize the body with `TextNormalizer.normalizeForMatching` (NFD + strip combining marks +
lowercase) → compile each unchanged message-regex source and match any (`runCatching` per pattern;
invalid patterns silently skip) → normalize the raw sender the same way → match against literal or
full-string RegEx sender rules exactly as stored (`PhoneNumberUtils.areSamePhoneNumber` remains for
literal phone rules; invalid sender regexes silently skip) → evaluate both results: both true
forwards, exactly one true logs `FILTER REJECTED` with the full raw sender/message and failed
component, both false exits silently → apply
optional `ForwardTemplate` (`%s`/`%t`/`%m` tokens) → `goAsync()` keeps the receiver alive →
**fan out the same body to every operational channel** (`WhatsAppCloudChannel`, `TelegramChannel`,
`SmsChannel`). A shared `AtomicInteger remaining` counts pending channel callbacks; each
channel's `onComplete(success)` decrements it, and when it reaches zero the receiver records
**exactly one** forward stat (if any channel succeeded) via `ForwardStatsStore.recordForward`,
increments the unseen launcher-badge count once, and calls `pending.finish()`. The original
(accented, cased) body is what gets forwarded — normalization is only for matching.

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
The app does not choose a subscription ID, so multi-SIM devices use Android's configured default
SMS subscription.

**Master switch**: `MasterSwitchTileService` (Quick Settings tile) and the Status screen's Compose
`Switch` both write the same `master_enabled` pref. `StatusViewModel` registers an
`OnSharedPreferenceChangeListener`, so flipping the tile reactively updates the on-screen switch
(no `onResume` re-sync needed). The service manifest entry and runtime tile state both use the
branded, alpha-only `ic_stat_sms_forwarder` system glyph.

**Runtime permissions**: readiness rows use distinct `HealthAction` values for `RECEIVE_SMS`,
`SEND_SMS`, and `POST_NOTIFICATIONS`. `StatusScreen` launches exactly one `RequestPermission`
contract for the tapped row. A denied or suppressed result shows an indefinite snackbar with an
**App settings** action, covering permanently denied permissions instead of failing silently.
`SEND_SMS` is requested only while the SMS channel is enabled. `POST_NOTIFICATIONS` is a
readiness requirement for launcher-badge compatibility even when a particular launcher backend
does not use it.

**SMS authorization constraints**: Android may group `RECEIVE_SMS` and `SEND_SMS` under one
user-facing SMS category, but the app must check and request them independently. A user-fixed
denial can return immediately without displaying a dialog; the snackbar's package-specific
**App settings** action is the recovery path. Device-owner/work-profile/OEM policy can make the
permission unavailable, and the app must not attempt to bypass it. Google Play separately treats
SMS permissions as restricted: Play distribution requires a permissions declaration and approval
for an eligible core use such as device automation. The GitHub-distributed APK is outside that
review flow but still uses Android's normal runtime authorization.

**Remote SMS rule commands**: the optional control feature lives in the Filters screen and is
independent of the master switch/channel readiness. `RemoteSmsRulesConfig` stores
`remoteSmsRulesEnabled` in normal prefs and the canonical 43-character unpadded Base64URL HMAC key under
`SecureStore.KEY_REMOTE_SMS_HMAC`. The key field is write-only; the trailing X marks it for removal,
turns the draft switch off, and persists only on Filters Save. Valid provisioning must supply the
complete `remoteSmsRules { enabled, hmacKey }` pair; application disables the feature, writes the
secret, then restores the requested enabled state with normal snapshot/rollback behavior.

Commands are exactly `TOKEN:BASE64URL_VALUE:BASE64URL_HMAC`, where TOKEN is `MCSMSSL`
(literal sender), `MCSMSSR` (sender full-string RegEx), or `MCSMSMR` (message RegEx). Base64URL is
canonical and unpadded; the UTF-8 value and 256-bit HMAC tag are encoded separately. The shared
256-bit key uses the same 43-character encoding. HMAC-SHA256 covers the exact
`TOKEN:BASE64URL_VALUE` text. There is deliberately
no sender restriction, version, timestamp, sequence, command ID, or replay protection. Any message
beginning with a reserved token is consumed before normal filters, even when the separator or
remaining syntax is malformed, and is never logged/forwarded verbatim. Every reserved
message received while control is operational gets a generic acknowledgment through all
operational channels, including malformed, invalid-HMAC, and duplicate commands; this accepted
behavior permits unauthenticated acknowledgment traffic/cost. Acknowledgments ignore the master
switch, never increment stats, and contain no rule/key data. Successful commands use the existing
mode-aware/phone-aware sender merge or exact message-RegEx merge. `tools/New-RemoteRuleSms.ps1`
generates keys or commands, prints by default, and supports `-Copy` and `-OutputPath` in both modes.
`FilterRuleMutationCoordinator` serializes remote additions, provisioning merges, and manual
Filters saves. Filters drafts preserve additions made after the screen opened, and HMAC-key
rotation/removal uses checked disable-first commits with rollback rather than asynchronous writes.
Legacy v1.0.13 hexadecimal keys/tags are rejected and are intentionally not migrated.

**Build information**: the Status screen ends with a low-emphasis outlined About card. It reads
`BuildConfig.VERSION_NAME`, the generated UTC build epoch, and the source revision through
`BuildMetadata`. Release Actions captures one timestamp immediately before the Gradle
test/build invocation; `GITHUB_SHA` supplies the release source revision.

**Battery exemption**: the Status readiness checklist treats
`PowerManager.isIgnoringBatteryOptimizations(packageName)` as required. Its action calls the
dedicated `requestBatteryOptimizationExemption` helper, which directly launches Android's
package-specific `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` confirmation. The helper has a
scoped, documented `BatteryLife` suppression because immediate forwarding is core task-automation
behavior. It catches `ActivityNotFoundException` and returns `false`, allowing the Compose screen
to show a snackbar; do not reintroduce `resolveActivity`, which triggers package-visibility lint.

**Boot**: `BootReceiver` makes the framework load the package on `BOOT_COMPLETED` so the manifest
SMS receiver is warm before the first message. It also reapplies the persisted unseen-forward
badge in case the launcher discarded it during reboot.

**Launcher badge**: `ForwardStatsStore.recordForward` updates `fwd_unseen_count` at the same
exactly-once point as the lifetime stat, then `LauncherBadge` applies that count through
ShortcutBadger's vendor-specific providers/broadcasts. `MainActivity` uses `singleTop` and clears
the unseen count only for a fresh `ACTION_MAIN` + `CATEGORY_LAUNCHER` invocation, handling both
`onCreate` and `onNewIntent`; restored state and Recents do not clear it. The Status reset clears
both lifetime stats and the unseen badge. Badge application is best effort and must never affect
forwarding. A positive count that cannot be applied logs `BADGE UNAVAILABLE` once until the next
launcher open. Microsoft Launcher has no confirmed standalone badge API; rely only on
ShortcutBadger's normal launcher selection and accept that it may be unsupported. The app itself
does not create forwarding-result or persistent count notifications. Do not add manufacturer
detection around ShortcutBadger; rely on its launcher selection and accept a no-op when unsupported.

**Persistence**: non-secret state uses a single `SharedPreferences` file named `mc_sms_fwd_wa`;
the WhatsApp token, Telegram token, and remote-SMS HMAC key use the separate encrypted store
described below. There is no database. Lists
(sender values, sender RegEx flags, message regexes) are parallel/newline-delimited strings. Logs use a
`timestamp\x1Fmessage` format with auto-pruning (35 days / 2000 entries); `LogUtils.addToLog`
collapses CR/LF/`\x1F` runs in the message to a space so multi-line bodies can't corrupt the
line-oriented format. WhatsApp credentials live under keys defined in `WhatsAppConfig`:
`waPhoneNumberId`, `waRecipient`, `waEnabled` (default true). The WhatsApp API template is fixed in
code (see the WhatsApp channel above); the separate shared forwarding template uses
`forwardTemplate`. Telegram: `tgEnabled` (default false), `tgChatId` (`TelegramConfig`). SMS:
`smsEnabled` (default false) and
`forwardTo` — the destination number (`SmsConfig`). **Secrets (the WhatsApp access token
`waAccessToken`, Telegram bot token `tgBotToken`, and remote HMAC key `remoteSmsHmacKey`) are NOT in
this file** — `SecureStore` encrypts them with AES/GCM using a key held by Android Keystore, then
stores the ciphertext in the private `mc_sms_fwd_secure` preferences file. Configs read secrets by calling
`SecureStore.read(context, …)`, so `WhatsAppConfig.load`/`TelegramConfig.load` take a `Context`
(not a `SharedPreferences`).
Lifetime forward-stat keys remain in the normal preferences file. The transient
`fwd_unseen_count` lives separately in `mc_sms_fwd_badge`, which is excluded from cloud backup and
device transfer so a badge is never restored onto another device.

**Encrypted provisioning**: the Channels top-app-bar overflow menu can import a `.mcsmsconfig`
bundle from Android's local/cloud document picker, Google Code Scanner QR scan, or pasted encrypted
import code. All routes use the same authenticated parser. `tools/New-ProvisioningBundle.ps1` stays
in one file, prompts securely by default, refuses repository-local inputs/outputs, optionally
generates a QR locally through pinned `qrcode` 1.5.4, and encrypts a versioned JSON payload using
PBKDF2-HMAC-SHA256 plus AES-256-GCM. It can carry the master switch, WhatsApp, Telegram, SMS,
remote SMS command configuration, allowed senders, regex rules, and the shared forwarding
template. `ProvisioningBundle` bounds and
validates the envelope before decrypting, writes secrets through `SecureStore.writeAll`, and writes
only supplied scalar fields to the normal preferences. Sender/rule lists are merge-only:
existing entries are never deleted or reordered, sender duplicates are mode-aware (plus
phone-equivalence for literal numbers), and message-regex duplicates use exact equality.
`{ "value": "...", "regex": true|false }` objects carry sender rules; implicit string entries are
rejected. Reapplying a bundle is idempotent. Imports never log or retain
secrets, and manual edits remain available afterward. Applying a bundle first disables the master
switch and included channels/control features, commits the public fields, commits encrypted
secrets, and only then restores the requested enabled states; rollback restores the prior snapshot,
while an unrecoverable partial write stays disabled. The `mc_sms_fwd_secure` preferences file is
excluded from Android backup and device transfer because its Keystore key cannot be transferred.

**Screens** are Compose, each backed by an `AndroidViewModel`. Manual form edits mutate in-memory
draft `StateFlow`s and are persisted only when the user taps the screen's explicit **Save** button
(no debounced auto-save); encrypted bundle import is a separate explicit action that persists after
passphrase confirmation. On the Filters screen the allowed senders and message-format rules are each
rendered as editable `OutlinedTextField` rows with a per-row delete button; sender rows also have a
RegEx filter chip and inline invalid-pattern state (order is not significant). Blank rows are dropped on
save and ignored by the live pipeline. Channel **Send test** actions use the currently displayed
draft values without saving them.

## Conventions

- Utility stores, matchers, formatters, channels, and provisioning code in `util/` are Kotlin
  `object` singletons that take `SharedPreferences` or `Context` directly; there is no dependency
  injection. `WhatsAppConfig`, `TelegramConfig`, and `SmsConfig` are immutable data classes loaded
  by their companion objects.
- **Edge-to-edge** is enabled once in `MainActivity.onCreate` via `enableEdgeToEdge()`; Compose
  `Scaffold` + window-inset padding handle the rest per screen. Screens use compact, pinned
  `TopAppBar`s; this utility app deliberately avoids large collapsing title bars.
- **Regex matching** uses `TextNormalizer.normalizeForMatching` to transform the message body only
  (NFD + strip combining marks + lowercase). Regex source is unchanged, so patterns must be written
  lowercase and accent-free. Invalid regexes are silently treated as non-matches.
- **Sender matching** normalizes only the incoming raw sender. Literal and RegEx sender rules remain
  exactly as entered, so textual rules must likewise be lowercase and accent-free; sender regexes
  use full-string matching. Literal phone rules retain `PhoneNumberUtils.areSamePhoneNumber`.
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
  `lastTestMessage`); the sender otherwise defaults to the first phone-like literal rule, then the
  first literal rule. If you add or change a channel or the matching logic, update `runTest` so the
  two cannot drift.
- **Filter-rejection diagnostics** implement an XOR rule: when exactly one of sender matching and
  message-rule matching succeeds, `SmsReceiver` writes one `FILTER REJECTED` entry naming the failed
  component and containing the full raw originating address and message. When neither matches it
  stays silent. The Activity screen has a dedicated **Filter rejected** filter and amber rendering.
  This log type intentionally retains sensitive content and is included in Share output.

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
  temporary directory outside the repository. Strongly blur sensitive regions before copying
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
