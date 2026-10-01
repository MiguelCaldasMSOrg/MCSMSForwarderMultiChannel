# Unchanged APK Installation Test - 2026-09-30

## Scope

Test the current published, unchanged APKs on Android 11 and Android 12 to identify the package installer's rejection reason. No application source, SDK requirement, APK contents, or signing information was changed. No APK was rebuilt or re-signed for this test.

## Artifacts

Source: [published release v1.0.17](https://github.com/MiguelCaldasMSOrg/MCSMSForwarderMultiChannel/releases/tag/v1.0.17).

Both APKs passed SHA-256 comparison against the published checksum assets and `apksigner verify`. Embedded manifests identify application `com.miguelcaldas.mcsmsforwardermultichannel`, version name `1.0.17`, version code `17`, and minimum SDK `33` (Android 13).

| APK | SHA-256 |
| --- | --- |
| Standard | `768e75ad1d07109e9de0d807ae85d0d63f296875547827f71afd769955594c9d` |
| Minified | `994f216f04d110c390496a11ad3478a4e8ae53d03118ae8ab45944073c859506` |

Hashes were checked again after the install attempts and remained unchanged.

## Environments

Android Emulator 37.1.11.0 on Windows with WHPX acceleration. Both AVDs use the Pixel 5 hardware profile and official Google APIs x86-64 system images, with a fresh first boot and no saved snapshot.

| AVD | Android | API | Image Revision | ADB Serial |
| --- | --- | --- | --- | --- |
| `MC_SMS_Android11_InstallCheck` | 11 | 30 | 16 | `emulator-5580` |
| `MC_SMS_Android12_InstallCheck` | 12 | 31 | 14 | `emulator-5582` |

The test verified the AVD identities, actual OS versions, and `sys.boot_completed=1`. The application package was absent before and after the attempts. Existing AVDs were not modified.

## Results

Each attempt used ordinary `adb -s <serial> install <apk>` with no compatibility override.

| Android | APK | Install Exit Code | Result |
| --- | --- | --- | --- |
| 11 / API 30 | Standard | 1 | `INSTALL_FAILED_OLDER_SDK` |
| 11 / API 30 | Minified | 1 | `INSTALL_FAILED_OLDER_SDK` |
| 12 / API 31 | Standard | 1 | `INSTALL_FAILED_OLDER_SDK` |
| 12 / API 31 | Minified | 1 | `INSTALL_FAILED_OLDER_SDK` |

Android 11 diagnostic, excluding the randomly named installer staging path:

```text
INSTALL_FAILED_OLDER_SDK: Failed parse during installPackageLI:
(at Binary XML file line #7): Requires newer sdk version #33 (current version is #30)
```

Android 12 diagnostic:

```text
INSTALL_FAILED_OLDER_SDK: Requires newer sdk version #33 (current version is #31)
```

The external test harness completed with exit code 0 because it confirmed all four expected installation rejections. This is a successful reproduction of incompatibility, not a successful installation or runtime compatibility result.

## Graphical Installer Follow-Up

The same unchanged standard release APK was copied into each emulator's Downloads folder and opened through Files. The user completed the final file click on Android 11; Android 12 was opened through UI automation. Screenshots and UI hierarchy captures confirmed that both dialogs belong to `com.google.android.packageinstaller`.

Both Android versions display this exact message, with an **OK** button:

> There was a problem parsing the package.

| Android | Screenshot |
| --- | --- |
| 11 / API 30 | [screenshots/android11-install-error.png](screenshots/android11-install-error.png) |
| 12 / API 31 | [screenshots/android12-install-error.png](screenshots/android12-install-error.png) |

The screenshots were visually reviewed and copied without modification. The standard APK's SHA-256 was checked again and remained unchanged. No credentials were present, and the visible dialogs were left open after capture.

This is a generic graphical error: it does not identify the required Android version. The separate ADB tests above establish the minimum-SDK rejection for these unchanged APKs. Samsung's installer wording may differ.

## Conclusion And Limits

The current release cannot install on Android 11 or Android 12 because it explicitly requires Android 13 or newer. Both release variants have the same restriction.

This reproduces the platform-level cause on those OS versions. It does not establish the Android version of the unavailable Samsung or reproduce Samsung One UI, device policy, or ARM-specific behavior. If that phone already runs Android 13 or newer, a different installation cause must be investigated.

The application could not launch, so no forwarding, permission, encryption, or other functional behavior was tested. No real credentials or SMS sends were used. The two new AVDs are retained for future testing.