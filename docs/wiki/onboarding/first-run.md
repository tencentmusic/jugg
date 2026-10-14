---
title: First run
description: Establish a Gradle baseline, verify the first Run result, and understand subsequent incremental runs.
status: active
tags:
  - onboarding
  - run
---

# First run

After installing the Jugg plugin and waiting for project Sync to complete, you can trigger your first run. The core goal of the first run is to **build a full APK and establish the incremental compilation baseline and deployment state**. This initial run takes about the same time as a standard Gradle build.

## 1. Pre-run checklist

Before clicking Run, quickly confirm the following:

1. **Run Configuration**: Selected `jugg:<moduleName>` in the top toolbar (rather than the native App configuration).
2. **Target Device**: Connected a physical device or emulator running Android 8.0 (API 26) or later.
3. **Sync Status**: Android Studio has no Gradle Sync or background indexing tasks in progress.

Once confirmed, click the green **Run** button in the Android Studio toolbar. If you selected the wrong device or configuration, stop the run at any time by clicking the red Stop button.

## 2. First-run mechanics: Establishing the Gradle baseline

On the first run, Jugg initializes automatically via the following pipeline:

```text
Click Jugg Run
  -> Check local environment and device connection status
  -> Detect missing incremental baseline, automatically fall back to full Gradle build
  -> Install and launch the target application
  -> In the background, collect class relations, resource maps, and R files for future incremental runs
  -> Baseline ready: supported subsequent code and resource changes can run incrementally
```

> [!NOTE]
> The first Run needs a full Gradle build when no usable baseline exists. Later Runs can build incrementally while that baseline remains valid; build configuration or dependency changes, unsupported processing, or an explicit fallback can require another full build.

## 3. Verification: Console log characteristics

Upon successful completion of the first run, the **Run** tool window at the bottom of Android Studio displays Jugg's progress:

```text
Gradle BUILD_AND_INSTALL SUCCESSFUL in <elapsed>s.
App launched.
```

These lines confirm that the Gradle build, installation, and launch succeeded. Baseline preparation continues in the background; they do not alone prove that incremental compilation is ready. See [Run an app](../guide/run.md) for subsequent Run results.

## 4. Everyday development: 3-second hot reload and execution paths

After the baseline is established, whenever you make small changes to Java, Kotlin, XML layouts, or Assets, continue clicking **Jugg Run** (or press `Ctrl + R` / `Shift + F10`):

| Modification Scenario | Jugg Decision Path | What to expect |
|---|---|---|
| **Modify a method body** | Incrementally compiles changed classes; uses Hot Reload when supported | Can avoid an app restart; device compatibility or other changes can require one |
| **Modify XML layouts / drawables** | Compiles and deploys changed resources | Refreshes Activities where supported, or restarts the app |
| **Add a method / modify a class signature / add an Activity** | Uses Hot Fix and restarts the app when supported; falls back to Gradle when required | Process state is reset after restart; Activity declarations may also require Manifest processing |
| **Modify `build.gradle` / dependencies** | Checks whether a full Gradle build is required and refreshes the baseline | Be ready for a full build; total time depends on the project |

## 5. When to fall back to Gradle manually

In the following scenarios, manually refreshing the baseline with a native Gradle build is recommended:

| Scenario | Recommended Action |
|---|---|
| **Manually cleaned `build/` directory** | Incremental artifacts are missing; run a native App Run to rebuild the baseline |
| **Switched Git branch or upgraded dependencies** | Major dependency or Manifest changes; perform a full build once |
| **Validating unsupported annotation processors** | Verify generated code correctness with a native Gradle build first |

---

## Next steps

Your baseline is successfully established! Continue exploring Jugg's powerful developer capabilities:

- 📖 **[Everyday run & hot reload guide](../guide/run.md)**: Master hot reload modes and multi-device deployment
- ⚡ **[Jugg CLI practical guide](../guide/cli.md)**: Experience 3-second builds in terminal or automation scripts
- 🛠️ **[Supported packaging methods](../troubleshooting/supported-packaging.md)**: Explore support for obfuscation, AabResGuard, and Dynamic Features
