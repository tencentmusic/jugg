---
title: Get started
description: Install Jugg, complete the first run, and find the next guide with the fewest possible steps.
status: active
tags:
  - onboarding
---

# Get started

Jugg is an Android incremental build plugin for Android Studio. Its main purpose is to reduce full Gradle builds during everyday debugging. It does not require changes to your project code or Gradle configuration. You still select a run configuration and click Run in Android Studio; Jugg decides whether to use incremental compilation and hot deployment or fall back to Gradle for that run.

## Complete the first run

Start with these two steps:

1. [Installation](./installation.md): Install the plugin and confirm that `jugg:module-name` appears in the run configuration selector.
2. [First run](./first-run.md): Run the app with Jugg once so the plugin can establish the Gradle baseline and deployment state.

## Learn more

| Topic | Page |
|---|---|
| Running the app after everyday changes | [Run the app](../guide/run.md) |
| When to fall back to Gradle | [Limits](../reference/limits.md) |
| Connecting an available remote build machine | [Remote build machine setup](./agent-setup.md) |
| How remote Gradle builds work | [Remote Gradle](../guide/remote-gradle.md) |

## Find a page quickly

Press `Command+K` to open Search, then enter a feature name, symptom, or log keyword.

## When something goes wrong

Choose an entry by symptom instead of reading the documentation from the beginning:

- **Compilation reports source, resource, or generated-source errors**: See [Compilation failed](../troubleshooting/compile-failed.md).
- **The run succeeds, but the app still shows old code or resources**: See [Changes not applied](../troubleshooting/changes-not-applied.md).
- **The app cannot be installed, launched, or debugged**: See [App cannot run](../troubleshooting/app-cannot-run.md).
- **The app crashes after deployment**: See [Runtime crash](../troubleshooting/runtime-crash.md).
- **You are not sure what information to provide**: Use [Report an issue](../guide/report-issue.md) to upload logs and include the Issue ID.
