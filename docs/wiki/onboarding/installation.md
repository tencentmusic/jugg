---
title: Installation
description: Download and install the Jugg Android Studio plugin, environment compatibility requirements, and Jugg Run Configuration verification.
status: active
tags:
  - onboarding
  - installation
---

# Installation

Jugg is distributed as an Android Studio plugin and requires no modifications to existing project code or `build.gradle` scripts. After installing and restarting the IDE, Jugg automatically creates a Jugg Run Configuration for each Android App module once Gradle Sync completes.

## 1. Environment requirements & compatibility matrix

Before installing, ensure your development environment meets the following requirements:

| Item | Minimum Requirement | Supported / Recommended Versions |
|---|---|---|
| **Operating System** | macOS / Linux / Windows | macOS (Apple Silicon / Intel), Linux, Windows 10/11 |
| **Android Studio** | Android Studio Chipmunk (2021.2.1)+ | Hedgehog, Iguana, Jellyfish, Koala, Ladybug, Meerkat, Narwhal |
| **Gradle / AGP** | AGP 7.0+ | AGP 7.x, 8.x |
| **Java Development Kit** | JDK 11+ | JDK 17 / JDK 21 |
| **Target Test Device** | Android 8.0 (API 26)+ | Physical devices or emulators with USB debugging enabled |

## 2. Download the plugin package

Download the plugin package (`.zip` format) from GitHub Releases:

- **[Latest stable release](https://github.com/tencentmusic/jugg/releases/latest)**: Recommended for everyday team development.
- **[Latest Canary build](https://github.com/tencentmusic/jugg/releases/download/canary-nightly/jugg-canary-nightly.zip)**: Built automatically from the branch that triggers the Canary workflow, containing the latest features.

> [!NOTE]
> If your organization uses an internal distribution portal, follow your team's versioning and staged rollout policy.

## 3. Install from disk in Android Studio

1. Open Android Studio.
2. Open Settings:
   - macOS: `Android Studio -> Settings...` (or `Preferences...`, shortcut `Cmd + ,`)
   - Windows / Linux: `File -> Settings...` (shortcut `Ctrl + Alt + S`)
3. In the left navigation tree, select **Plugins**.
4. Click the gear icon ⚙️ at the top and select **Install Plugin from Disk...**.
5. Select the downloaded `jugg-*.zip` plugin package.
6. Click **OK**, then click **Restart IDE** when prompted to restart Android Studio.

## 4. Confirm the run configuration

After Android Studio restarts and the background Gradle Sync finishes, open the run configuration dropdown in the top toolbar. You should see an entry like:

```text
jugg:app
```

Here, `app` corresponds to your primary Android Application module. If your project contains multiple App modules, Jugg automatically creates a `jugg:<moduleName>` configuration for each runnable module.

If no Jugg configuration appears, check the following points:

| Symptom | Diagnostic and Resolution |
|---|---|
| **Gradle Sync in progress** | Check bottom-right status bar; wait until Sync finishes completely |
| **Native App Configuration** | Ensure a runnable native App Run Configuration exists in the project |
| **IDE Restart** | Ensure Android Studio was fully restarted after installing the zip |
| **Reload Project** | Try `File -> Invalidate Caches / Restart` to reload the project |

## 5. Optional: Adjust compile command and parameters

Most projects automatically infer the correct Gradle build task and APK output path after Sync. If you need to fine-tune settings for custom build variants, select **Edit Configurations...** from the run configuration dropdown:

| Parameter | Default & Purpose |
|---|---|
| **Compile command** | Gradle command used to build the initial baseline APK (e.g. `:app:assembleDebug`) |
| **Output APK name** | Destination APK artifact path, matching the output of the compile command |

---

## Next steps

Once the plugin is installed and the run configuration is verified, proceed to your first run to establish the build baseline:

👉 **[Proceed to First run and establish baseline](./first-run.md)**
