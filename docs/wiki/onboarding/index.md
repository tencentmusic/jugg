---
title: Get started
description: Understand Jugg's bypass incremental build mechanics in 30 seconds, and complete Android Studio plugin installation and first run.
status: active
tags:
  - onboarding
  - quickstart
---

# Get started

Jugg is an Android Studio plugin designed for fast incremental compilation and instant hot reload, dramatically cutting down the time spent waiting for full Gradle builds during everyday development. It does not require modifying project code or `build.gradle` scripts, nor does it require any SDK integration. Simply select the Jugg Run Configuration and click Run in Android Studio—small code and resource changes typically become visible on your connected test device within 3 seconds.

The Jugg Run Configuration and the native App Run Configuration coexist independently. Whenever you need standard Gradle builds, simply switch back to the native App configuration in the run selector—the two flows are completely decoupled.

## Development efficiency comparison

| Dimension | Native Android Studio (Gradle Run) | Jugg Bypass Incremental Build |
|---|---|---|
| **Feedback latency** | Typically 1–3 minutes (longer for large codebases) | **Typically 1–3 seconds** |
| **Build mechanism** | Executes full Gradle task graph | Bypasses Gradle, reuses baseline build outputs |
| **Intrusiveness** | Depends on Gradle build scripts | **Zero intrusiveness**: no Gradle edits, no SDK |
| **Safety fallback** | None | **Automatic Gradle fallback** when changes exceed incremental scope |

## Quick start in 2 steps

Getting started with Jugg takes just two steps:

1. **[Install Jugg plugin](./installation.md)**: Download the `.zip` archive, install it from disk in Android Studio, and confirm that `jugg:app` is generated in the run configuration dropdown.
2. **[First run and establish baseline](./first-run.md)**: Select the Jugg configuration and click Run once to capture Gradle build outputs and establish an incremental baseline.

> [!TIP]
> If you have installed the Jugg CLI, you can also trigger incremental compilation and deployment directly from your terminal:
> ```bash
> jugg deploy
> ```

## Capabilities and packaging navigation

Explore how Jugg works in different scenarios:

| Topic | Description | Page |
|---|---|---|
| **Everyday development** | Running and hot-reloading after changing Java/Kotlin/XML code | [Run the app](../guide/run.md) |
| **Supported packaging** | Support for AabResGuard, Dynamic Features, release obfuscation, etc. | [Supported packaging methods](../troubleshooting/supported-packaging.md) |
| **Incremental compilation internals** | Class impact analysis and D8 incremental dex pipelines | [Incremental compilation overview](../concepts/incremental-compile/) |
| **Fallback boundaries & limits** | Scenarios that trigger automatic fallback to full Gradle | [Limits](../reference/limits.md) |
| **Remote build acceleration** | Offloading compilation workloads to dedicated remote Linux machines | [Remote Gradle](../guide/remote-gradle.md) |

## When something goes wrong

Quickly diagnose issues by symptom without reading through all docs:

- **Compilation errors** (source, resource, or generated code errors): See [Compilation failed](../troubleshooting/compile-failed.md).
- **Changes not applied** (run succeeded but device shows old code): See [Changes not applied](../troubleshooting/changes-not-applied.md).
- **App cannot run** (installation, launch, or debugger connection failed): See [App cannot run](../troubleshooting/app-cannot-run.md).
- **Runtime crashes** (app crashes immediately after deployment): See [Runtime crash](../troubleshooting/runtime-crash.md).
- **Diagnostic reports**: Use [Report an issue](../guide/report-issue.md) to export diagnostics and attach an Issue ID.

---

## Next steps

Now that you understand the core workflow, install the plugin:

👉 **[Go to Installation](./installation.md)**
