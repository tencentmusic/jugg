---
title: Run an app
description: Start from Jugg Run in Android Studio to select a configuration and devices, run the app, and interpret the result.
status: active
tags:
  - guide
  - run
---

# Run an app

During everyday development, click Jugg Run after changing code or resources. You do not need to run compilation and deployment separately. Jugg saves files, evaluates the changes, updates the device, and launches the app.

## When to click Run directly

Jugg Run is suitable when:

- You changed common files such as Java / Kotlin method bodies, layouts, drawables, values, or assets.
- You want to verify the app on a device immediately after making a change.
- You are unsure whether the change can be handled incrementally and want Jugg to decide.
- You want Jugg to fall back to Gradle automatically when necessary instead of switching workflows manually.

Prepare for a Gradle comparison or an additional action when:

- You just switched branches, pulled many changes, or modified the Gradle plugin or dependency configuration.
- You need to clear app data and reinstall.
- You are verifying a release build, annotation processing, bytecode instrumentation, or the complete Gradle pipeline.

You can still start with Jugg Run in these cases, but be ready to accept a Gradle fallback or use Clear app data or Fall back to Gradle compilation directly.

## How to run from Android Studio

1. Confirm that the selected Run Configuration is a Jugg configuration, not a native App configuration.
2. Select the target devices. A normal Run supports multiple devices; Debug supports only one.
3. Save your changes, or wait for Jugg to save them automatically.
4. Click Run.
5. Check the result in the Run tool window.

If you click Debug, the first part uses the same run flow: Jugg compiles and deploys first, then restarts the app in debug mode and lets the native Android Studio debugger attach.

## What happens after you click Run

```text
Jugg Run
  -> Save files and refresh file state
  -> Check devices, installation state, and file changes
  -> Try incremental compilation first
  -> Prompt for or fall back to Gradle when necessary
  -> Deploy automatically after compilation succeeds
  -> Choose Hot Reload, Hot Fix, installation, or restart based on the changes
  -> Print the result of this run
```

Compilation and device updates are internal stages. In everyday use, check the final result in the Run tool window before deciding whether to restart, clear app data, or fall back to Gradle.

## Interpret the result

| Result | Meaning | Next step |
|---|---|---|
| Jugg Hot Reload succeeded | The change has taken effect without restarting the app in most cases | Verify it on the current screen |
| Jugg Hot Fix succeeded | The change was delivered and will take effect after the app restarts | Wait for the app to relaunch, then verify it |
| Gradle compilation and installation succeeded | This run used a full Gradle build and installation | Continue using Jugg Run for subsequent small changes |
| Clean Reinstall succeeded | App data was cleared, the APK was reinstalled, and Jugg deployment state was restored | Navigate back to the screen you need to verify |
| Compilation succeeded but deployment failed | The code compiled, but device deployment or launch failed | Check the device connection, compatibility mode, and deployment logs |
| No file changes detected | Jugg did not find any changes it can process | Confirm that files are saved; Sync or compare with Gradle if necessary |

## When to use another entry point

| What you want to do | Recommended entry point |
|---|---|
| Clear app data and reinstall | [Clear app data](./clean-data.md) |
| Run one full Gradle build | [Fall back to Gradle compilation](./downgrade-gradle.md) / `jugg gradle-build` |
| Enter a breakpoint immediately after a change | Debug |
| Run `src/androidTest` | Test gutter icon or an Android Test Run Configuration |
| Trigger a run from an agent or script | `jugg deploy` or the Jugg CLI Skill |
| Restart an app that did not restart after you changed startup initialization logic | [Restart the app](./restart-app.md) |

> [!NOTE]
> Hot Reload does not re-execute all previously initialized logic. After changing startup logic, singleton caches, static / companion members, or Kotlin top-level declarations, restart the app even if the run reports Hot Reload.

## Where to look first

| Symptom | First check |
|---|---|
| No Jugg output after Run | Confirm that the Run Configuration is a Jugg configuration |
| Compilation failed | See [Compilation failed](../troubleshooting/compile-failed.md) |
| The run succeeded but the change did not take effect | See [Changes did not take effect](../troubleshooting/changes-not-applied.md) |
| The app cannot install, launch, or enter Debug | See [The app cannot install, launch, or enter Debug](../troubleshooting/app-cannot-run.md) |
| The app crashes after deployment | See [The app crashes after deployment](../troubleshooting/runtime-crash.md) |

If these recovery actions do not resolve the problem, use [Report an issue](./report-issue.md) to upload the diagnostic data.

## Related pages

- [Fall back to Gradle compilation](./downgrade-gradle.md)
- [Restart the app](./restart-app.md)
- [Clear app data](./clean-data.md)
- [Select multiple devices](./multi-device.md)
- [Android RemoteViews](./android-remoteviews.md)
- [Compatibility deployment](./compat-device.md)
- [Advanced options](./advanced-options.md)
- [Debug](./debug.md)
- [Android Test](./android-test.md)
- [How Jugg works](../concepts/how-jugg-works.md)
- [Deployment self-healing](../concepts/deploy-self-healing.md)
- [Gradle fallback and baseline rebuilding](../concepts/gradle-fallback-baseline.md)
