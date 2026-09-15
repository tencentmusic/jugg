---
title: Custom APK install script
description: Explains how Jugg invokes a project script during ordinary app installation and reinstallation for system apps, vendor tasks, and other non-standard installation flows.
status: active
tags:
  - capability
  - deploy
  - install
---

# Custom APK install script

System apps, vendor projects, and customized devices may not support installation through the default Android Studio installer. Jugg can execute a project-provided script whenever an ordinary app APK must be installed, while preserving the deployment cache and overlay checkpoint required by later incremental deployments.

## Enabling the script

Open the Jugg Run Configuration and select `Enable custom APK install script`. A single-line-height script field appears below the switch. When the switch is not selected, only the switch is shown and the existing installation behavior remains unchanged.

The script runs from the local project root. Jugg uses Bash on macOS/Linux and `cmd.exe` on Windows. Even when remote compilation is enabled, the script runs on the local IDE host connected to the Android device.

For example, invoke a project script:

```bash
./scripts/install-system-app.sh
```

You can also invoke a Gradle task defined by the project:

```bash
./gradlew :app:installToSystem
```

Jugg does not inject device, applicationId, or APK path variables. The script inherits the Android Studio process environment together with the Gradle JDK and Android SDK environment resolved by Jugg. The current Android SDK's `platform-tools` directory is added to `PATH`, so the script can invoke `adb` directly. Bash does not load user shell startup files such as `.zshrc` or `.bashrc`; other project-specific variables must come from the Android Studio launch environment or the script itself.

## Scope

The custom script takes over install/reinstall for ordinary app APKs, including:

- Installation after a Gradle compilation.
- Reinstallation triggered by Clean Reinstall or recovery.
- Installation after APK updates such as Manifest or native library changes.
- Embedded APK installation.

The script runs once for each ordinary app applicationId on each device. Base and split APKs with the same applicationId are handled as one group.

Pure class or overlay incremental deployment does not run the script. Android test APKs continue to use the default Android Studio installer so that a system-app script does not accidentally process test packages.

## Success and failure

The script must install the APKs supplied by the current Jugg run and finish with exit code `0`. If the script triggers a reboot, it should wait for device startup and PackageManager scanning before it exits. Jugg then handles a short ADB transport recovery window, confirms that the target package is installed, and verifies that the APK on the device has the same checksum as the input APK. Jugg writes the new deployment cache and overlay checkpoint only after every check succeeds.

The current deployment fails when:

- The script exits with a non-zero code.
- The user cancels the Run and the script process is terminated.
- The device does not reconnect after the script finishes.
- The target applicationId is not installed.
- The script installs a different APK and the checksum does not match.

When script execution or installation verification fails, Jugg does not rerun the script through deploy retry or Gradle fallback. After script installation succeeds, failures in other deployment steps still follow the existing retry and fallback rules. For example, if a subsequent Android test APK installation fails with `INSTALL_FAILED_INVALID_APK`, the uninstall retry runs the ordinary app installation script again. The default installer's own short ADB transport recovery remains available. The project is responsible for installation logic, side effects, and error handling when the script runs more than once.

## System app boundary

Jugg only invokes the script and verifies the installation result. It does not provide built-in behavior for:

- `adb root`, `adb remount`, or writing the system partition.
- Selecting `/system/app` or `/system/priv-app`.
- Platform signing, shared UID, or privileged-permission allowlists.
- Rebooting, restarting zygote, or vendor flashing flows.

A successful script alone does not prove that the app became a system app. Continue to use `dumpsys package <applicationId>` to inspect `codePath`, `SYSTEM`, `PRIVILEGED`, and permission grant state.

## Related pages

- [Clean Reinstall](./clean-reinstall.md)
- [APK update and installation](../../concepts/apk-update-and-install.md)
- [Multiple APKs](./multi-apk.md)
- [Multiple devices](./multi-device.md)
- [Deployment history and cache](./deploy-history-cache.md)
- [App cannot be installed, deployed, started, or debugged](../../troubleshooting/app-cannot-run.md)
