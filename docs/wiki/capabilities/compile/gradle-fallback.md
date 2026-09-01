---
title: Gradle fallback
description: Explains when Jugg falls back from incremental compilation to Gradle and what users observe after fallback.
status: active
tags:
  - capability
  - compile
  - fallback
---

# Gradle fallback

Jugg prioritizes incremental compilation. When the current build baseline is no longer applicable, or when a failure result explicitly permits fallback, the current Run switches to Gradle. Regular compilation errors and user cancellation do not automatically trigger a full build.

## Conditions that trigger Gradle fallback

| Scenario | Current support | Behavior |
|---|---|---|
| User forces Gradle | Supported | Skips incremental checks directly |
| No files changed | Prompt or automatic fallback supported | Configuration determines whether confirmation is required |
| Too many files or modules | Confirmation can select fallback | Defaults to Gradle; after the countdown, incremental compilation can continue for this run |
| Device is considered invalid | Automatic fallback supported | The current run uses Gradle; installation can complete only after the device recovers |
| Build target switches | Automatic fallback supported | Switching between app and androidTest requires a new APK baseline |
| Build-file or dependency changes | Confirmation can select fallback or incremental dependency handling | The user's choice determines whether incremental handling continues |

## Trigger and result

```text
Start Run
  -> A precheck requires a full build: switch the current Run to Gradle
  -> Incremental compilation reports a regular error: end the current Run without immediate fallback
  -> Deployment fails: attempt recovery first, then switch the entire run to Gradle when automatic fallback conditions are met
  -> Gradle succeeds: continue installation or later execution with the new full-build artifacts
```

After a successful Gradle build, Jugg uses the new APK, classpath, mapping, and resource artifacts as the starting point for later incremental work. The next small change still attempts incremental compilation first.

## Boundaries

- Incremental compilation first performs limited retries for known recoverable input problems. If regular source or resource errors remain after retry, the current run ends and does not use Gradle to hide the original error.
- User cancellation is an explicit stop signal and does not trigger automatic fallback.
- If the app is not running or deployment state requires recovery, Jugg first attempts Recover, compatible deployment, or reinstallation of the existing APK. Gradle fallback cannot repair an offline device or an ADB failure.
- A deployment failure restarts the entire Run with Gradle only when the failure result permits fallback and automatic fallback is enabled. A multi-device run does not switch the build baseline for only one device.

When you need to refresh the complete build baseline intentionally, follow [Downgrade to Gradle compilation](../../guide/downgrade-gradle.md). For fallback conditions and baseline updates, see [Gradle fallback and baseline rebuilding](../../concepts/gradle-fallback-baseline.md).

## Related pages

- [Source compilation](./source-compile.md)
- [Incremental dependency compilation](./dependency-incremental.md)
- [Downgrade to Gradle compilation](../../guide/downgrade-gradle.md)
- [Compilation stages](../../guide/compile.md)
- [Gradle fallback and baseline rebuilding](../../concepts/gradle-fallback-baseline.md)
- [Deployment self-healing](../../concepts/deploy-self-healing.md)
