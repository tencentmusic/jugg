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
| Run the app after changing code or resources | [Run an app](./run.md) | Compile, deploy, launch, and assess the result in one Jugg Run |
| Rebuild when no files have changed | [Fall back to Gradle compilation](./downgrade-gradle.md) | Handle no-change fallback, use the fallback button, or respond to a dependency-change dialog |
| Export the current incremental result as an APK | [Export an incremental APK](./export-incremental-apk.md) | Export the compiled incremental APK from the fallback confirmation dialog |
| Restart the current app without recompiling | [Restart the app](./restart-app.md) | Verify startup logic, caches, singletons, or static / companion changes |
| Clear app data and reinstall | [Clear app data](./clean-data.md) | Create a clean installation state or rebuild device deployment state |
| Run on multiple devices | [Select multiple devices](./multi-device.md) | Compile once and deploy to each device selected in Android Studio |
| Update a home-screen widget or notification RemoteViews | [Android RemoteViews](./android-remoteviews.md) | Write incremental changes into APK content so the system can read them |
| Recover from repeated deployment failures on one device | [Compatibility deployment](./compat-device.md) | Use the compatibility hot-fix path for a specific device |
| Start a debugging session | [Debug](./debug.md) | Let Jugg compile and deploy, then attach the Android Studio Java debugger |
| Run `src/androidTest` tests | [Android Test](./android-test.md) | Run instrumentation tests from the gutter, a Run Configuration, or the CLI |
| Use Jugg from a terminal or an agent | [CLI](./cli.md) | Use `jugg compile`, `deploy`, `instrument`, UI tools, and log commands |
| Configure MCP | [MCP](./mcp.md) | Understand the public MCP features, port, response model, and why the CLI is preferred in most cases |
| Export layouts, locate elements, or tap a device | [UI inspection](./ui-inspection.md) | Provide UI hierarchy, property queries, and touch controls to an agent or script |
| Use a cloud development machine or remote build host | [Remote Gradle](./remote-gradle.md) | Keep the IDE and deployment local while Gradle builds run remotely |
| Extend compilation stages | [Custom compiler](./custom-compiler.md) | Add project-specific generation, transformation, or validation logic |
| Adjust More Options settings | [Advanced options](./advanced-options.md) | Understand runtime strategies, tool entry points, and internal diagnostics settings |
| Upload issue logs | [Report an issue](./report-issue.md) | Upload logs and obtain an Issue ID when compilation, deployment, or runtime results are unexpected |
| Self-host a backend for configuration, updates, and diagnostics | [Jugg backend](./jugg-backend/) | Centrally distribute project configuration, plugin upgrades, hot updates, and issue logs |

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

- [Run an app](./run.md)
- [Fall back to Gradle compilation](./downgrade-gradle.md)
- [Export an incremental APK](./export-incremental-apk.md)
- [Restart the app](./restart-app.md)
- [Clear app data](./clean-data.md)
- [Select multiple devices](./multi-device.md)
- [Android RemoteViews](./android-remoteviews.md)
- [Compatibility deployment](./compat-device.md)
- [Concepts](../concepts/)
- [How Jugg works](../concepts/how-jugg-works.md)
- [Gradle fallback and baseline rebuilding](../concepts/gradle-fallback-baseline.md)
- [Jugg capabilities](../capabilities/)
- [Advanced options](./advanced-options.md)
- [Report an issue](./report-issue.md)
- [Compilation failed](../troubleshooting/compile-failed.md)
- [Changes did not take effect](../troubleshooting/changes-not-applied.md)
- [The app cannot install, launch, or enter Debug](../troubleshooting/app-cannot-run.md)
- [Log files](../reference/log-files.md)
