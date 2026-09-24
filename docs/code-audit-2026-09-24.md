# Engineering Audit - 2026-09-24

Status: discussion draft. No commit, push, CI run, service deployment, or real message send was performed during this audit. Existing uncommitted JDK 25 and Status UI changes were preserved. The only production change made by this audit is the credential-redaction fix described below, shared by both release variants.

## Executive Recommendation

Keep the Android application and Compose UI, but strengthen the ownership of forwarding, settings, and diagnostics. The highest-value substantial change is a platform-independent forwarding engine backed by a single configuration repository. Add durable delivery as an explicit product option, not as an automatic retry of every ambiguous send.

Do not limit proposals to small changes. Equally, do not confuse a larger framework with stronger guarantees: a queue cannot recover an SMS broadcast that Android has not delivered, a coroutine timeout cannot stop arbitrary regex backtracking, and a database cannot provide exactly-once delivery across a carrier or remote HTTP API.

Preserve the same-functionality minified release. A feature-reduced edition is a separate decision, especially if removing QR provisioning, launcher badges, or durable delivery.

## Android OTP Compatibility Constraint

The three-hour OTP delay is documented. The important setting is the Android target SDK, not the Java version.

| Setting | Current value | Effect on OTP delivery |
| --- | --- | --- |
| Device OS | Determined by the installed device | Android 17 adds platform restrictions. |
| `targetSdk` | 36, Android 16 | Keep unchanged while compatibility is investigated. Targeting 37 enables the standard-OTP restriction on Android 17. |
| `compileSdk` | 37, Android 17 SDK | Compile-time APIs; does not itself opt into target-37 behavior. |
| Java/Kotlin bytecode | 17 | Class-file compatibility; unrelated to OTP broadcast policy. |
| Gradle runtime JDK | 25 in the pending changes | Build tooling; unrelated to OTP broadcast policy. |

For most non-exempt apps targeting API 37 or higher, Android 17 withholds ordinary OTP-containing SMS broadcasts and filters SMS-provider queries for three hours. Android 17 also protects WebOTP messages regardless of target SDK. SMS Retriever hash-based protection already existed before Android 17. Keeping target 36 therefore does not guarantee immediate access to every OTP format.

The current manifest implements an alternate receiver, not a complete default SMS application. A legitimate default-SMS-handler edition is a substantial product option: it needs role eligibility, explicit user selection, and the responsibilities of an SMS application. Companion-device exemptions likewise require genuine eligibility. Do not assume that declaring a permission or adding a service grants an exemption.

SMS Retriever is intended for messages associated with the receiving app; SMS User Consent involves user-mediated access. Neither is a drop-in replacement for unattended forwarding of arbitrary third-party codes. Do not recommend disabling system protections or delaying OS security updates.

Sources: [target-37 OTP behavior](https://developer.android.com/about/versions/17/behavior-changes-17#sms-otp-protection), [all-target Android 17 OTP behavior](https://developer.android.com/about/versions/17/behavior-changes-all#sms-otp-all-apps), [current SDK declarations](../gradle/libs.versions.toml#L6).

## Findings

### 1. High: Error truncation can expose credential prefixes - fixed locally

Both HTTP channels summarized and truncated provider errors before replacing the complete token. When a token crossed the 180/240-character boundary, its prefix survived because the truncated text no longer contained the full string being redacted. This violated the documented no-credential-logging guarantee.

Evidence: synthetic probes invoked the existing compiled summarization/redaction helpers. Both channels returned `token_prefix_survives=true` while `full_token_present=false`. No actual credential was inspected, and no claim is made that an existing user's log contains credentials.

Implemented: redact decoded provider error fields, or fallback response text, before applying the length limit. Added eight regression tests covering long tokens, boundary crossings, URL encoding, JSON escaping, malformed responses, missing error objects, and existing length limits. No new runtime library or service was added.

Sources: [WhatsAppCloudChannel.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/WhatsAppCloudChannel.kt#L110), [TelegramChannel.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/TelegramChannel.kt#L97), [ChannelErrorSummaryTest.kt](../app/src/test/java/com/miguelcaldas/mcsmsforwardermultichannel/util/ChannelErrorSummaryTest.kt#L10).

### 2. High: Manual channel saves do not have provisioning's failure guarantees

`saveWhatsApp` and `saveTelegram` apply public fields and the enabled flag before encrypting the replacement token. They do not use checked commits or rollback. A Keystore failure can leave new public values paired with the old secret, and a persistence failure is not reported as a failed save. The forms navigate away as though the save succeeded.

Recommended redesign: all channel saves, imports, enable toggles, and rule mutations go through one configuration repository. Validate first, perform checked writes off the main thread, publish the new configuration only when complete, and provide explicit failure state to the UI.

Minimal implementation: reuse the existing snapshot/disable-first/rollback approach for manual saves, without adding DataStore, Room, or a DI framework. A future typed DataStore migration can improve schema/observation, but does not automatically make two different stores transactional.

Source: [ChannelsViewModel.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/ui/channels/ChannelsViewModel.kt#L135).

### 3. High: Configuration writers and live readers do not share a consistency boundary

Provisioning uses a mutation lock and disables the master switch before committing fields and secrets. Ordinary forwarding does not use that lock: it checks the master switch, then separately loads channel configurations and rule lists. It can observe a mixture if an import begins after the initial gate. Channel toggles/manual saves also bypass the provisioning lock. This is a static interleaving finding; a device race has not been reproduced.

Recommended redesign: the repository exposes an immutable configuration snapshot with a generation identifier. Every send uses one validated snapshot. Define whether disabling forwarding cancels already admitted work or only blocks future messages; do not accidentally change that behavior during refactoring.

Minimal implementation: coordinate all readers and writers for a short snapshot operation, never hold the lock during network work, and move blocking reads/commits away from UI and receiver entry points.

Sources: [SmsReceiver.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/SmsReceiver.kt#L32), [ProvisioningBundle.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/ProvisioningBundle.kt#L205), [FilterRuleMutation.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/FilterRuleMutation.kt#L4).

### 4. High: Receiver lifetime is not governed by one end-to-end deadline

Regex compilation/matching, preference reads, secret decryption, and remote-command writes execute before `goAsync()` and before the per-channel deadline. A valid but expensive regex can stall the main thread. Slow storage or lock contention can consume the broadcast budget before transport begins.

The completion helper uses `orTimeout(...).whenComplete(...)`. A synthetic JDK 25 probe against the compiled helper showed that blocking one timeout callback delayed another independent deadline. Android runtime confirmation remains outstanding. The production callback can perform stats and launcher-provider work before `pending.finish()`.

Recommended redesign: a receiver adapter hands off immediately to a forwarding coordinator with one monotonic budget, explicit completion ownership, and isolated nonessential side effects. A pure evaluator should be shared with the Filters dry-run. Preserve reserved-command interception, XOR rejection logging, loop-guard behavior, and exactly-once local counting.

Minimal implementation: move evaluation off the main thread; separate deadline scheduling from potentially blocking callbacks; move badge-provider work out of the receiver completion path. Keep stats read/modify/write synchronization intact. A timer is not a reliable way to stop a non-interruptible Java regex.

Sources: [SmsReceiver.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/SmsReceiver.kt#L32), [Concurrency.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/Concurrency.kt#L43), [ForwardStatsStore.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/ForwardStatsStore.kt#L18).

### 5. Medium: Transport resource consumption is not bounded end to end

The cached executors can create unbounded worker threads. Timing out the completion future does not cancel the underlying transport. `HttpURLConnection` has connect/read timeouts, but no configured total-call/write budget, and error bodies are read with unbounded `readText()`. An error response can consume substantial memory before the displayed summary is shortened.

Recommended implementation: evaluate a shared OkHttp client with explicit call/write timeouts, cancellation, bounded response reads, and controlled concurrency. Keep provider serialization separate from transport. Disable automatic retry of non-idempotent sends unless its behavior is explicitly approved. A Retrofit layer is not necessary for two endpoints.

Minimal alternative: retain platform HTTP, cap response bytes and active work, and add explicit connection cancellation. Define an overload policy; silently dropping messages, introducing a queue, or rejecting busy work would change the existing contract. A concurrency cap without such a policy is not a complete fix.

Sources: [Concurrency.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/Concurrency.kt#L25), [HttpJsonClient.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/HttpJsonClient.kt#L27).

### 6. Medium: Log storage, rendering, and sharing scale with the entire log

Every append parses, prunes, and serializes the whole log in the same preferences file as configuration. The queue of log-write tasks is unbounded. The entry count is capped, but message lengths and total bytes are not. `LogViewModel.refresh` launches on the main dispatcher and performs the complete read/sort/format/filter there; the UI lays out one annotated text block.

Sharing places the entire visible log in `Intent.EXTRA_TEXT`. A sufficiently large log can exceed the Binder transaction budget and fail. This is a source-level risk, not a reproduced device crash.

Age pruning occurs only during append. A quiet installation can display/share entries older than the advertised retention period indefinitely until another entry is written. Cloud backup/transfer of public preferences, including logs, is currently allowed and documented; it is not a secret-store backup bug.

Recommended redesign: a dedicated bounded log repository with structured entries, storage separate from configuration, background reads, lazy rendering, and file-based export through a narrowly scoped FileProvider. Use SQLite/Room if adopting the durable outbox; otherwise an append/rotate design may be sufficient.

Minimal implementation: enforce retention on read and during maintenance, cap total storage, move formatting off-main, and share a private temporary file. Preserve the explicitly requested full raw filter-rejection data; any truncation or metadata-only default needs approval. Moving to lazy rows also requires deciding whether cross-entry text selection must be preserved.

Sources: [LogUtils.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/LogUtils.kt#L37), [LogViewModel.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/ui/log/LogViewModel.kt#L99), [LogScreen.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/ui/log/LogScreen.kt#L130), [data_extraction_rules.xml](../app/src/main/res/xml/data_extraction_rules.xml#L2).

### 7. Medium: Secret draft state and save UX are inconsistent

Newly typed WhatsApp/Telegram tokens are held in `rememberSaveable`, making them eligible for Android saved-instance-state serialization. Visual password masking does not prevent that. Stored tokens are also retained in remembered initial configuration objects, despite the narrower intention of a write-only field.

Channel forms have no dirty-state/discard confirmation equivalent to Filters. The SMS loop-guard warning is launched into a snackbar coroutine immediately before navigating back, so it may disappear with the screen. Filters test results are not invalidated when sample inputs or draft rules change. Readiness checks refresh on resume, not on every remote rule addition.

Recommended redesign: ViewModel-owned drafts with explicit Save/Discard/error state, a separate memory-only secret draft, saved-secret presence instead of token-length masks, and results tied to an input/configuration generation. Do not reload forms indiscriminately on every resume; that would overwrite intentional unsaved edits.

Minimal implementation: exclude secret drafts from saved state, add channel dirty-state handling, deliver save results to the destination screen, and clear or label stale dry-run results.

Sources: [ChannelDetailScreen.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/ui/channels/ChannelDetailScreen.kt#L121), [FiltersScreen.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/ui/filters/FiltersScreen.kt#L431), [StatusViewModel.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/ui/status/StatusViewModel.kt#L100).

### 8. Medium: Limits and provider contracts are inconsistent across input routes

Remote rule additions cap the resulting lists at 1,000 entries. Provisioning caps each incoming list but not the merged total; manual editing has no equivalent shared cap. This is an inconsistent resource policy, not evidence of a fixed SharedPreferences size limit. Rule imports also use repeated equivalence scans, which become expensive as lists grow.

Telegram `sendMessage` accepts 1-4096 characters, but the forwarder has no provider-length validation or segmentation policy. A long multipart SMS or expanded template can be rejected. The current payload also uses legacy `disable_web_page_preview`; the current API documents `link_preview_options`.

Recommended implementation: validate limits in the shared domain/repository layer and describe channel capabilities explicitly. Make long-message rejection or Unicode-safe segmentation a deliberate policy, with per-segment outcomes and once-per-input stats. Verify WhatsApp template-specific limits independently; do not assume Telegram's limits apply to it.

Sources: [RemoteSmsRuleCommand.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/RemoteSmsRuleCommand.kt#L84), [ProvisioningBundle.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/ProvisioningBundle.kt#L205), [TelegramChannel.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/TelegramChannel.kt#L82), [Telegram API](https://core.telegram.org/bots/api#sendmessage).

### 9. Medium: SMS result reporting depends on the application process surviving

The sent-result receiver is registered dynamically. Once handoff completes, the originating receiver finishes; a later modem result may be missed if Android kills the process. This does not prove the SMS failed, but weakens the diagnostic trail. Request codes also restart after process recreation, so outstanding intents deserve a lifecycle test; negative request codes alone are not a defect.

Recommended implementation: a manifest-declared, non-exported result receiver targeted by explicit immutable PendingIntents, with durable per-attempt identity if result correlation matters. Verify delivery of system-sent PendingIntents on supported OS versions. Optional subscription selection is useful for multi-SIM installations, but should retain Android's default subscription as the default behavior.

Source: [SmsChannel.kt](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/SmsChannel.kt#L38).

### 10. Low: Provisioning helper path checks are lexical, not physical

The PowerShell helpers normalize paths with `GetFullPath` and compare the repository prefix. That does not resolve junctions/symlinks or identify a different Git worktree. It can undermine the intended outside-repository safeguard when an apparently external path redirects inside a checkout.

Recommended implementation: check physical parents/reparse points and the destination checkout before reading plaintext or creating sensitive output. Keep tests synthetic and outside the protected directory. Also consider failing before writing the encrypted bundle when requested QR generation cannot succeed, so a partial multi-output result is explicit.

Source: [New-ProvisioningBundle.ps1](../tools/New-ProvisioningBundle.ps1#L90). Apply equivalent path handling to the remote-rule helper without changing its deliberate print/clipboard behavior.

## Substantial Architecture Options

| Option | Benefit | Costs and migration risks | Minimal-footprint counterpart |
| --- | --- | --- | --- |
| Pure forwarding engine plus configuration repository | Consistent snapshots; shared live/dry-run rules; failure injection; reliable unit tests | Refactors receiver, forms, imports, and channel boundaries; preserve documented ordering and semantics | Plain Kotlin interfaces and immutable data, manual construction, existing stores; no Hilt or multi-module split required initially |
| Encrypted durable outbox with WorkManager/Room | Survives process death; network-aware rescheduling; explicit pending/failed/unknown states | Added disk writes/dependencies; OTP expiry; migrations; duplicate sends after ambiguous outcomes; Android scheduling is not immediate | Keep direct forwarding and a small bounded diagnostic journal; explicitly no durability guarantee. A platform SQLite/JobScheduler outbox is possible but carries more custom lifecycle code |
| Shared cancellable HTTP transport | Total call budgets, pooling, bounded concurrency, consistent provider errors | New dependency and retry semantics; benchmark on device | Harden current platform HTTP; no separate Retrofit or network service |
| Safe-regex mode | Predictable matching cost for compatible patterns | RE2/J-style engines reject lookbehind/backreferences; automatic conversion would change saved rules | Bounded compiled-pattern cache and validation warnings; these reduce allocation but do not guarantee bounded match time |
| Structured log store and lazy log UI | Lower write amplification and UI stalls; safe large export | Data migration, selection behavior, retention/backup policy | Separate bounded log file, background reads, existing Compose components, FileProvider already available through Core |
| Full default-SMS-handler edition | A legitimate route to supported SMS role behavior where eligible | New inbox/compose/notification/storage responsibilities; replaces the user's default SMS app; significantly broader product | Existing alternate receiver, explicit platform limitations, target 36 pending compatibility decisions |
| Optional cloud relay | Central token custody, provider updates, possible delivery webhooks | Server operations/costs, additional trust boundary for message content, authentication/key rotation, offline handset still cannot upload | Keep direct provider calls and no additional service. Do not add a relay merely to reorganize code |

For an outbox, use `Pending`, `Sending`, `Accepted`, `Failed`, `Unknown`, and `Expired` states rather than one Boolean. Record the original SMS timestamp and expiration policy. Do not automatically retry an `Unknown` send, including SMS modem handoff, without an explicit duplicate-delivery policy. A server relay also cannot make unrelated provider APIs exactly-once without provider support.

## Explicit Product/Security Decisions

These are documented behaviors, not newly discovered implementation bugs:

- Remote commands deliberately lack timestamps, sequence numbers, and replay protection.
- Invalid/unauthenticated reserved commands deliberately trigger acknowledgments through operational channels, potentially incurring network/carrier costs even with ordinary forwarding paused.
- Filter-rejection logs deliberately retain the full raw sender and message, and public logs may be backed up.
- Channel acceptance/handoff is not a guarantee of final delivery.

Worth discussing: a versioned remote-command protocol with replay protection and acknowledgment rate limits; privacy-preserving default diagnostics with explicit temporary full-content mode; delivery expiry/retry choices. Protocol, log-content, and paid-send behavior must not change silently. Any protocol update must also update the PowerShell generator and compatibility tests.

## Dependencies, Build, and Services

Official metadata was checked on 2026-09-24. Published availability is not proof of application compatibility; the following upgrades were not applied during this audit.

| Component | Current | Verified available | Recommendation |
| --- | --- | --- | --- |
| AGP | 9.3.2 | 9.3.3 patch; 9.4.1 in Google Maven | Prioritize the 9.3.3 D8/R8 fixes, or evaluate 9.4.1 as a separate toolchain change. The 9.4 line requires Gradle 9.6.0 or newer |
| Gradle | 9.5.0 | 9.6.0 required by AGP 9.4 | Upgrade with AGP when justified; retain the wrapper checksum |
| AndroidX Core | 1.19.0 | 1.19.1 | Patch candidate after changelog review and device validation |
| Navigation Compose | 2.10.0 | 2.10.2 | Patch candidate with saved-state/back-stack tests |
| Compose BOM | 2026.08.00 | 2026.09.00 | Coordinated UI update, including compact-width/font-scale regression checks |
| Activity / Lifecycle | 1.13.0 / 2.11.0 | Same stable versions in queried metadata | No upgrade solely for version churn |
| Google Code Scanner | 16.1.0 | Same stable version in queried metadata | Preserve in full edition; consider optionality for footprint/service independence |
| Kotlin / Compose compiler | 2.2.10 | Not independently upgraded | Keep aligned with AGP built-in Kotlin; do not bump just the Compose compiler blindly |
| Graph API | v21.0 | Meta documentation lists newer versions through v26.0 | Plan a versioned contract migration and a real authorized send test; v21 retirement date was not verified |

AGP 9.3.3 release notes include R8/D8 correctness, reproducibility, and Windows path fixes. Those are materially more relevant than speculative dependency replacement.

GitHub API verification found current stable releases: checkout v7.0.1, setup-java v6.0.1, gradle/actions v6.3.0, upload-artifact v7.0.1, download-artifact v8.0.1, configure-pages v6.0.0, upload-pages-artifact v5.0.0, deploy-pages v5.0.1. The existing major tags are current; do not downgrade them. Consider immutable SHA pins with an update bot, dependency verification/locking, protected release tags, and limiting signing secrets to the steps that need them.

The workflows run build validation on release tags, not as an ordinary pull-request gate. A secret-free PR validation workflow and minified-device smoke tests would catch defects earlier. Adding those triggers is a proposal only; no CI was invoked or newly enabled here.

The QR dependency brings ML Kit, Firebase component/transport infrastructure, and legacy AndroidX transitives. Dependency presence alone does not establish what telemetry is collected. Review SDK disclosures/merged manifests and verify runtime behavior before changing privacy claims. Avoid replacing the native Keystore storage with deprecated security wrappers merely for abstraction.

Sources: [AGP 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes), [AGP 9.4 release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [Meta versions](https://developers.facebook.com/docs/graph-api/changelog/versions), [release workflow](../.github/workflows/publish-release.yml#L1), [version catalog](../gradle/libs.versions.toml#L1).

## Minimal Size, Memory, and Impact

Measured local artifacts after the redaction fix:

| Variant | Bytes | MiB |
| --- | --- | --- |
| Standard release | 25,897,952 | 24.70 |
| Minified release | 3,087,733 | 2.94 |

The minified variant is approximately 88% smaller and retains the same features. The redaction fix uses the same implementation in both variants; tests add no production dependency. No new service, resident worker, or background scheduler was added.

A smaller APK is not evidence of proportionally lower heap usage. Android release PSS, cold-start allocations, burst workload, and battery use were not measured in this audit. Those measurements are required before claiming memory or battery wins.

Recommended packaging strategy:

1. Keep the current standard compatibility release and the same-functionality minified release.
2. Implement correctness/privacy fixes in shared code, never omit them from a smaller variant.
3. If a feature-reduced edition is wanted, use source-set boundaries so QR scanning and its dependencies can be omitted while file/code provisioning remains. Badge removal is another explicit feature tradeoff, not a hidden optimization.
4. Keep durable delivery optional until its size, wakeups, expiry, and duplicate-send tradeoffs are measured. Do not maintain separate security-sensitive forwarding implementations for full and minimal editions.
5. Avoid a Compose-to-Views rewrite solely for APK size: the current optimized app is already under 3 MiB. Revisit UI technology only with measured startup/heap evidence or a changed product requirement.

## Validation and Coverage

- Baseline JVM suite: 78 tests across 13 suites, zero failures/errors/skips, freshly rerun under the pending JDK 25 configuration.
- Added regression suite: eight tests for provider-error secret handling.
- Final JVM result: 86 tests across 14 suites, zero failures/errors/skips.
- Final local command passed: `:app:testDebugUnitTest :app:lint :app:assembleRelease :app:assembleMinifiedRelease --no-daemon`.
- Known-advisory check: 141 selected release Maven coordinates, including transitives; the advisory tool reported no known CVEs. This does not cover every build-time/npm tool, unknown vulnerabilities, native platform defects, or actual SDK data collection.
- Synthetic compiled-code probes reproduced the pre-fix redaction leak and timeout-callback coupling. The latter remains a proposal, not a repaired or device-verified condition.
- No production credential file, protected directory, real provider endpoint, or live SMS was accessed for this audit.

Coverage priorities: configuration failure injection and concurrent import/send snapshots; end-to-end receiver completion; process-death SMS results; local HTTP server tests for slow reads/writes, large errors and ambiguous acceptance; shared live/dry-run evaluation; UI saved-state/unsaved-draft tests; minified startup/QR reflection; and Android 17 real-device OTP behavior by format and role. Emulator `sms send` alone is not sufficient evidence for all OEM/platform OTP policies.

## Findings Rejected During Verification

- API 37 is supported by the current AGP line; target 36 is lower than compile 37. Neither is a build-version error.
- Decorative icons beside accessible text should not gain duplicate spoken labels merely because `contentDescription` is null.
- Badge state is accessed under synchronization; lack of `volatile` is not itself a visibility bug.
- Negative PendingIntent request codes after integer wrap are not inherently invalid. Clamping them to one would create collisions.
- Separating backup-excluded badge preferences from lifetime stats is intentional. Combining them to obtain one edit would change backup behavior.
- Moving stats writes outside their read/modify/write lock would introduce lost updates.
- The normalizer and log-control regexes are already object-level values; no per-call allocation fix is needed for those constants.
- A 1,000-entry provisioning input limit does not prove a 1,000-entry global storage contract. Establish one shared policy before enforcing it everywhere.

## Suggested Discussion Order

1. Confirm target 36 is retained for now and decide whether a full SMS-handler edition is in scope for Android 17 OTP access.
2. Review the already-implemented redaction repair, then prioritize transactional settings and receiver/deadline isolation.
3. Choose direct best-effort delivery versus an optional durable outbox, including expiry, unknown outcomes, and duplicate-send policy.
4. Choose shared full/minified builds only, or also a visibly feature-reduced edition without QR and possibly badges.
5. Approve any remote-command protocol or log-content policy changes separately.
6. Group dependency upgrades and architectural changes into separately reviewable commits only after discussion.