---
title: Guide
description: Learn how to run Jugg from Android Studio, fall back to Gradle, restart or clear app data, debug, test, use the CLI, and configure remote Gradle builds.
status: active
tags:
  - guide
---

# Guide

These pages are for everyday Jugg users who have already installed the plugin and need to know what to click after changing code, how to respond to dialogs, when to restart or clear app data, and where to look first when something goes wrong.

## Choose a page by task

| What you want to do | Recommended page | When to use it |
|---|---|---|
| Run the app after changing code or resources | [Run an app](/guide/run) | Compile, deploy, launch, and assess the result in one Jugg Run |
| Rebuild when no files have changed | [Fall back to Gradle compilation](/guide/downgrade-gradle) | Handle no-change fallback, use the fallback button, respond to a dependency-change dialog, or confirm a large incremental compile |
| Export the current incremental result as an APK | [Export an incremental APK](/guide/export-incremental-apk) | Export the compiled incremental APK from the fallback confirmation dialog |
| Restart the current app without recompiling | [Restart the app](/guide/restart-app) | Verify startup logic, caches, singletons, or static / companion changes |
| Clear app data and reinstall | [Clear app data](/guide/clean-data) | Create a clean installation state or rebuild device deployment state |
| Run on multiple devices | [Select multiple devices](/guide/multi-device) | Compile once and deploy to each device selected in Android Studio |
| Update a home-screen widget or notification RemoteViews | [Android RemoteViews](/guide/android-remoteviews) | Write incremental changes into APK content so the system can read them |
| Recover from repeated deployment failures on one device | [Compatibility deployment](/guide/compat-device) | Use the compatibility hot-fix path for a specific device |
| Start a debugging session | [Debug](/guide/debug) | Let Jugg compile and deploy, then attach the Android Studio Java debugger |
| Run `src/androidTest` tests | [Android Test](/guide/android-test) | Run instrumentation tests from the gutter, a Run Configuration, or the CLI |
| Use Jugg from a terminal or an agent | [CLI](/guide/cli) | Use `jugg compile`, `deploy`, `instrument`, UI tools, and log commands |
| Configure MCP | [MCP](/guide/mcp) | Understand the public MCP features, port, response model, and why the CLI is preferred in most cases |
| Export layouts, locate elements, or tap a device | [UI inspection](/guide/ui-inspection) | Provide UI hierarchy, property queries, and touch controls to an agent or script |
| Use a cloud development machine or remote build host | [Remote Gradle](/guide/remote-gradle) | Keep the IDE and deployment local while Gradle builds run remotely |
| Extend compilation stages | [Custom compiler](/guide/custom-compiler) | Add project-specific generation, transformation, or validation logic |
| Adjust More Options settings | [Advanced options](/guide/advanced-options) | Understand runtime strategies, tool entry points, and internal diagnostics settings |
| Upload issue logs | [Report an issue](/guide/report-issue) | Upload logs and obtain an Issue ID when compilation, deployment, or runtime results are unexpected |
| Self-host a backend for configuration, updates, and event reporting | [Jugg backend](/guide/jugg-backend/) | Centrally distribute project configuration, plugin upgrades, hot updates, and usage events |

## A typical development loop

```text
Change code or resources
  -> Select a device and Jugg Run Configuration in Android Studio
  -> Click Run or Debug
  -> Jugg saves files and chooses incremental compilation or Gradle
  -> Jugg deploys automatically after compilation succeeds
  -> The result uses Hot Reload, restarts the app, installs an APK, or reports a failure
```

Most application code, resource, and layout changes can be handled by clicking Run. Jugg performs compilation and device updates in the background. Focus on three outcomes: whether the run succeeded, whether the app restarted, and whether Jugg fell back to Gradle.

## Recommended workflow

- After initial setup, switching branches, pulling many changes, or modifying Gradle configuration, accept one Gradle build to establish a trusted baseline.
- For small Java/Kotlin, resource, layout, or asset changes, use Jugg Run first.
- When you know a full build is required, use [Fall back to Gradle compilation](./downgrade-gradle.md).
- When you need to clear app data, use [Clear app data](./clean-data.md) instead of clearing it manually in system settings.
- After changing app startup logic, static initialization, singleton caches, or object initialization, restart the app if the run used Hot Reload.
- When an incremental result is unexpected, compare it with one Gradle build before submitting Jugg logs.
- For agents, configure the Jugg CLI Skill first. Use MCP only when direct MCP client integration is required.
- To submit a problem, use [Report an issue](./report-issue.md) to upload logs, then send the Issue ID to the maintainer.

## Related pages

- [Run an app](/guide/run)
- [Fall back to Gradle compilation](/guide/downgrade-gradle)
- [Export an incremental APK](/guide/export-incremental-apk)
- [Restart the app](/guide/restart-app)
- [Clear app data](/guide/clean-data)
- [Select multiple devices](/guide/multi-device)
- [Android RemoteViews](/guide/android-remoteviews)
- [Compatibility deployment](/guide/compat-device)
- [Concepts](/concepts/)
- [How Jugg works](/concepts/how-jugg-works)
- [Gradle fallback and baseline rebuilding](/concepts/gradle-fallback-baseline)
- [Jugg capabilities](/capabilities/)
- [Advanced options](/guide/advanced-options)
- [Report an issue](/guide/report-issue)
- [Compilation failed](/troubleshooting/compile-failed)
- [Changes did not take effect](/troubleshooting/changes-not-applied)
- [The app cannot install, launch, or enter Debug](/troubleshooting/app-cannot-run)
- [Log files](/reference/log-files)
