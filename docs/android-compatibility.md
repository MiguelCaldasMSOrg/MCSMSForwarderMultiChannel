# Android Compatibility And Retirement Guide

## Support Contract

One application source tree produces the regular `release` and R8-minified `minifiedRelease` APKs. Both support Android 11/API 30 and newer, have the same features, application ID, version, and signing identity. There are no OS-specific product flavors. `compileSdk` remains 37, `targetSdk` remains 36, Gradle runs on JDK 26, and Java/Kotlin bytecode targets 17.

Lowering the minimum SDK does not opt newer phones out of current APIs or weaken encryption. Runtime checks retain the platform phone-number comparison and SMS service lookup on Android 12+, dynamic colors on Android 12+, and notification permission prompts on Android 13+. Android 11 uses the existing static light/dark palettes. Launcher badge support remains launcher-dependent.

The previous published v1.0.17 APKs still require API 33; see [the unchanged-APK installation evidence](android-installation-test-2026-09-30.md). Source changes do not modify previously published assets.

## Compatibility Inventory

| Support code | Reason | Retirement threshold |
| --- | --- | --- |
| [PhoneNumberCompat](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/PhoneNumberCompat.kt) and the `libphonenumber` catalog dependency | Android 11 lacks the platform `areSamePhoneNumber` method. The fallback follows the platform's exact/national/qualified-short matching policy, never loose last-seven-digit matching. Shared by sender filters, rule merges, and both live/dry-run loop guards. | Minimum API 31 |
| [SmsChannel.defaultSmsManager](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/SmsChannel.kt) | Uses `SmsManager.getDefault()` only on Android 11; keeps Android's configured default subscription. | Minimum API 31 |
| [Theme](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/ui/theme/Theme.kt) SDK check | Dynamic color APIs require Android 12. The brand palettes already existed and are also used when dynamic color is disabled. | Minimum API 31 |
| [Legacy backup rules](../app/src/main/res/xml/backup_rules.xml) and manifest `fullBackupContent` | Android 11 does not consume `dataExtractionRules`. Both formats exclude encrypted secret preferences and transient badge state, while preserving ordinary settings/statistics backup. | Minimum API 31 |
| [Concurrency](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/Concurrency.kt) explicit deadline scheduler | Originally added because `CompletableFuture.orTimeout` requires API 31; it now also owns the receiver's cross-version lifetime deadline. Cancelled tasks are removed and completion capacity is reserved separately. | Do not remove the shared scheduler/bounds merely because minimum API becomes 31; the receiver still requires them |
| [StatusViewModel](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/ui/status/StatusViewModel.kt) notification availability branch and `OPEN_NOTIFICATION_SETTINGS` action in [StatusScreen](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/ui/status/StatusScreen.kt) | Android 11/12 have no `POST_NOTIFICATIONS` prompt. Check notification availability and open app notification settings, with app-settings fallback. Android 13+ retains its explicit permission request. | Minimum API 33 |
| `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` in [SmsChannel](../app/src/main/java/com/miguelcaldas/mcsmsforwardermultichannel/util/SmsChannel.kt) | Preserves private SMS-result broadcasts on Android 11/12 using AndroidX's compatibility implementation. | Native equivalent available at API 33; keeping AndroidX remains valid |

The phone policy was checked against [Android 12's implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-12.0.0_r1/telephony/java/android/telephony/PhoneNumberUtils.java). Bundled numbering metadata can differ from a device's platform metadata; representative cross-version parity checks are part of release testing. This is not a promise of identical metadata across every OS/OEM image.

## Removing Android 11

When every supported device runs Android 12 or newer:

1. Raise the catalog minimum to 31 and remove API 30 from the release matrix.
2. Replace `PhoneNumberCompat.areSame` calls with the platform comparison, then remove the adapter, `libphonenumber` dependency/version, and Android-11-only tests. Do not remove general sender/loop-guard tests.
3. Replace `defaultSmsManager` with the native service lookup and remove its deprecation suppression.
4. Remove only the SDK guard around dynamic colors. Keep the static palettes because disabling dynamic color still uses them.
5. Remove `backup_rules.xml` and `android:fullBackupContent`; retain `dataExtractionRules` and both secret/badge exclusions.
6. Keep the receiver lifetime scheduler, reserved completion capacity, bounded workers, and callback isolation. Native `orTimeout` can replace only the channel-specific scheduling portion, with the same regression tests.
7. Keep notification-settings and private-receiver compatibility while Android 12/12L remain supported.

## Removing Android 12

When every supported device runs Android 13 or newer:

1. Complete the Android 11 retirement steps, raise the minimum to 33, and remove API 31/32 from the release matrix.
2. Remove the pre-33 notification-availability/settings branch and the now-unused health action. Keep permission-denial recovery and readiness checks.
3. Optionally replace the AndroidX receiver registration with the native non-exported registration. Never make SMS result broadcasts public.
4. Retain the test driver and general release regression tests; they are not legacy support code.

Neither retirement requires changing the application ID, signing key, preference formats, cryptographic algorithms, or remote command protocol.

## Test And Release Policy

The full API 30-37 matrix, for both regular and minified release APKs, runs only on new validated version-tag pushes in [publish-release.yml](../.github/workflows/publish-release.yml). Ordinary pushes, pull requests, schedules, and manual preflight do not start that matrix. Publication depends on all matrix jobs succeeding. Failed/skipped/zero-test instrumentation output cannot be reported as a successful run. API 37 uses the official `android-37.0` image/package naming; the test driver still asserts device API 37.

The build job captures one timestamp and source revision, stages the signed application APKs and matching tests, and publishes those same application files only after testing. Test APKs are private short-lived workflow artifacts, not GitHub release assets. Each minified release retains its exact mapping and checksum assets.

Two test layers avoid changing production R8 behavior:

- `app/src/androidTest`: internal platform/configuration tests against the unminified release, using real Android Keystore, provisioning, phone APIs, default SMS subscription lookup, private PendingIntent callbacks, deadline completion, and backup resource exclusions.
- `device-tests`: self-instrumenting UI Automator driver in a separate process, used against BOTH release APKs. It covers navigation/import-route availability, dirty draft discard/save persistence across process restart, phone-number matching through the app's dry-run UI, and channel saves with rotated secret drafts and untouched-token preservation. Navigation helpers wait for idle before reacquiring nodes. The driver deliberately has no dependency on application classes or access to application preferences.

An in-process runner against an optimized APK proved unsuitable: shared dependencies removed by R8 were deduplicated from the runner APK. The separate UI driver solves that boundary without broad keep rules or a less-optimized shipping APK.

Device tests refuse physical hardware and clear the test application on their disposable emulator. Fixtures are synthetic. They do not use the protected credential file, real provider credentials, or real carrier sends.

Focused local development checks remain allowed. Run JVM tests with the default test configuration; select the regular-release instrumentation configuration in a separate invocation:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lint :app:assembleDebug :app:assembleRelease :app:assembleMinifiedRelease
.\gradlew.bat :app:assembleRelease :app:assembleReleaseAndroidTest -PandroidTestBuildType=release
.\gradlew.bat :device-tests:assembleDebug
```

These commands build/check code; they do not start the full device matrix. `androidTestBuildType=minifiedRelease` is intentionally rejected; use the isolated driver for optimized APKs.

## Verification Limits

The automated matrix is regression coverage, not certification of every hardware/service behavior. It does not validate live WhatsApp/Telegram delivery, real modem/carrier delivery, Samsung-specific background management/badges, camera QR recognition, or an actual cloud-backup restore. Real-device/provider checks need an explicitly authorized release-time exercise. Do not silently add live-send credentials or make paid sends in CI.

Final v1.0.18 local validation passed 103 JVM tests with zero failures/errors/skips, app lint, debug and both release builds, and isolated-driver compilation. Android 11 passed 12 regular-release platform/configuration tests, including exact-ciphertext rollback and log migration, plus all four black-box UI tests against the fully optimized APK. The new channel-save UI test also passed against the regular release. No live provider credentials or sends were used for these tests. The full multi-platform GitHub matrix is triggered by the new release tag, not local development.

The release workflow provides the authoritative final signed-artifact and cross-platform results. Advisory scans during preparation found no known CVEs in the added runtime/test dependencies, including resolved OkHttp 5.5.0, Okio 3.18.1, and Kotlin stdlib 2.2.21; this is a point-in-time check, not a future security guarantee.

Before the subsequent transport/logging hardening, compatibility-only APKs measured 26,478,161 bytes regular and 3,405,790 bytes minified. These are historical measurements, not final v1.0.18 sizes; final signed release assets are authoritative.

## Implementation Review

The compatibility changes are localized and preserve newer-device API paths. Review-driven improvements applied here: one shared phone adapter for all matching/loop-guard paths; cancelled timer removal; timeout callback isolation; a separate black-box driver instead of production keep-rule expansion; and publication gated by the exact-APK matrix.

The user subsequently authorized solutions to the two high-priority findings and the combined medium resource-bound finding for v1.0.18:

| Finding | Implemented solution |
| --- | --- |
| Transactional manual saves | Manual channel saves reuse provisioning's checked disable/write/restore transaction. Untouched tokens are not rewritten; rollback restores exact ciphertext. In-flight forms are disabled, failures keep drafts, and memory-only token drafts survive rotation. |
| Consistent, off-main evaluation | A shared immutable `ForwardingConfiguration` snapshot and `ForwardingEvaluator` serve live and dry-run paths. All configuration mutation paths use the same lock. The receiver calls `goAsync` immediately, evaluates with two workers/32 queued tasks, and has one nine-second finish guard. Already-admitted sends retain their snapshot. |
| Bounded transport and callbacks | Eight workers/32 queued requests per HTTP channel; 128 reserved completion slots with four callback workers. Expired queued work never executes. Shared OkHttp has total/write deadlines, connection reuse, and no automatic retries/redirects. Requests above 1 MiB fail; error bodies above 64 KiB are omitted entirely before redaction. Template expansion is capped at 256 Ki characters. |
| Bounded log work/storage/export | Batched appends move logs to `mc_sms_fwd_log` with migration and whole-entry pruning at 35 days, 2000 entries, or 4 MiB UTF-8 data. Pending work is capped at 128 operations/1 MiB; omissions are summarized. Entries above 512 KiB get a marker. Reads/formatting are off-main; export uses a private file provider and eight cached files. Badge updates are coalesced outside the statistics lock. |

Resource saturation intentionally fails or expires work explicitly instead of retaining unlimited memory or automatically resending. Normal SMS content is retained without truncation; extreme expanded payloads and oversized individual log entries receive explicit errors/omission markers.

Residual limitations: Java backtracking regex cannot safely be forcibly interrupted in-process. The finite evaluator pool and finish guard prevent unbounded worker growth and broadcast lifetime, but pathological expressions can occupy those workers until they return or the process restarts. A transport timeout cannot prove that a remote provider did not accept the request. Real-service/OEM checks listed above remain release-time manual requirements.