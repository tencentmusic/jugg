---
title: Custom APK sign script
description: Explains how Jugg replaces the default signing step of an incrementally rewritten APK with a project script for platform certificates, vendor keys, or server-side signing.
status: active
tags:
  - capability
  - deploy
  - apk
  - sign
---

# Custom APK sign script

When a platform certificate, vendor key, or remote signing service cannot be used as a local Gradle `SigningConfig`, the default Jugg keystore signing produces an APK with the wrong signing identity, and updating an existing system package ends with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. After `Enable custom APK sign script` is selected, Jugg hands the APK to a project script, while file write-back, verification, atomic replacement, and the following installation stay unchanged.

## Scope

| Scenario | Current support | User-visible result |
|---|---|---|
| Manifest change written back to the APK | Supported | The project script signs after zipalign; the APK is replaced and installed only when verification passes |
| Native library change written back to the APK | Supported | Same as above |
| Embedded dex and Embedded APK | Supported | Same as above |
| APK produced by a full Gradle build | Not supported | Gradle keeps signing it, and the script does not run |
| CLI incremental build and manual incremental APK export | Not supported | They keep using the default local signing |
| Invalid local signing configuration | Supported | An enabled script no longer requires a usable local `SigningConfig` |

In one Run, only APKs rewritten by Jugg go through the project script.

## Trigger and result

```text
The current Run generates Manifest, resources.arsc, or native library artifacts
  -> write them into the latest Gradle APK
  -> zipalign
  -> script enabled: <configured command> <absolute path of the APK to sign>
  -> apksigner verify checks the APK produced by the script
  -> only a passing verification replaces the original APK and continues installation
```

Jugg runs the script from the local project root and passes the absolute path of the APK to sign as the last positional argument. Paths containing spaces, parentheses, or Unicode characters still arrive as a single argument. The script must overwrite that APK in place; when the signing service writes the result somewhere else, the script must replace that path before it exits.

```bash
# Configured in the Run Configuration
./scripts/sign-system-apk.sh --server production

# What Jugg actually runs
./scripts/sign-system-apk.sh --server production "/path/to/.app-debug.apk.tmp_aligned"
```

The `android_demo_project` sample provides `scripts/sign-system-apk.sh`. It takes `$1` as the APK path and signs in place with the project's existing default signing material and the Android SDK `apksigner`, so it can be used directly to verify this capability.

## Boundaries

- After the switch is selected, an empty script fails Run Configuration validation. When the switch is not selected, only the switch is shown and the existing signing behavior stays unchanged.
- Exit code `0` only means the script finished. Jugg still runs `apksigner verify`; when verification fails, the original APK stays unchanged and the current Run does not proceed to installation.
- A non-zero exit, cancellation, or failed verification never falls back to local keystore signing for that update, so the APK cannot end up with the wrong signing identity.
- Script output is forwarded to the Run window, and canceling the Run terminates the script process. Jugg does not retry known failures; upload, waiting, download, and their retry policy belong to the project script.
- Deploy retry and recovery install an APK that is already signed, so they do not run the script again.
- A multi-device Run may run the script more than once for the same APK; the project script owns the semantics and side effects of repeated execution.
- The script body is sensitive configuration. Jugg logs and diagnostics only show `(configured)` / `(not_configured)` and never record the script text.
- Remote compilation only changes where artifacts come from: the APK is still rewritten and signed on the local IDE host connected to the device.

## Related pages

- [APK update and installation](../../concepts/apk-update-and-install.md)
- [Custom APK install script](./custom-apk-install-script.md)
- [Multiple APKs](./multi-apk.md)
- [Multiple devices](./multi-device.md)
- [App cannot be installed, deployed, started, or debugged](../../troubleshooting/app-cannot-run.md)
