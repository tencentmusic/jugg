---
title: How it works
description: Understand Jugg's incremental compilation, deployment, state recovery, and runtime mechanisms through the complete flow of a Run.
status: active
tags:
  - concept
---

# How it works

This section explains how Jugg shortens the edit-run-verify cycle in everyday Android development, and how compilation artifacts, device state, and source code remain aligned across consecutive incremental Runs.

If you are completing a specific operation, start with the [guides](../guide/). To check which scenarios a capability supports, see [core capabilities](../capabilities/). When a failure occurs, begin with [troubleshooting](../troubleshooting/).

## Build a complete model from one Run

Jugg uses the latest full Gradle build as a trusted starting point. On later Runs, it first determines whether the current project and device can continue from that baseline, compiles the current changes and affected code, and then selects a deployment path according to artifact type. Only after deployment succeeds does the current result become the starting point for the next incremental Run.

To learn Jugg for the first time, read [How Jugg works](./how-jugg-works.md), then choose a topic below according to the question you are trying to answer.

## Choose a page by question

| What do you want to understand? | Suggested reading |
|---|---|
| How a Run makes decisions, compiles, deploys, and commits state | [How Jugg works](./how-jugg-works.md), [Compilation orchestration](./compile-pipeline.md), [Incremental deployment](./deploy-strategy.md), [Gradle fallback and baseline rebuild](./gradle-fallback-baseline.md) |
| Why changing one file can still compile other files | [Incremental compilation](./incremental-compile/), [Project context](./project-model.md), [Deployment data and impact analysis](./deploy-data-and-impact.md) |
| How classes and resources reach the device, and why the app sometimes restarts or the APK is updated | [Incremental deployment](./deploy-strategy.md), [Classes and overlays in Apply Changes](./apply-changes.md), [APK update and installation](./apk-update-and-install.md) |
| Why deployment can continue when the device is not ready, and how inconsistent state is recovered | [Direct Overlay deployment](./direct-overlay.md), [Deployment state and recovery](./deploy-state-recover.md), [Deployment self-healing](./deploy-self-healing.md), [Compatibility deployment](./compat-deploy.md) |
| How code is replaced and continues running in the app process | [In-app Jugg Runtime](./jugg-runtime.md), [Jugg JVMTI Agent](./jugg-jvmti-agent.md) |
| How testing, UI evidence, and version compatibility enter the main flow | [Android Test flow](./android-test-flow.md), [Layout dump and UI evidence](./layout-dump-and-ui-evidence.md), [Android Studio version compatibility](./compatibility-layer.md) |

## Recommended reading path

To understand Jugg systematically, read these pages in order:

1. [How Jugg works](./how-jugg-works.md): Build a complete model of one Run.
2. [Incremental compilation](./incremental-compile/): Understand how different inputs produce local artifacts.
3. [Incremental deployment](./deploy-strategy.md): Understand how artifacts take effect on the device through Apply Changes, APK update, or compatibility deployment.
4. [Deployment self-healing](./deploy-self-healing.md): Understand how existing incremental artifacts continue through retry, strategy switching, and reinstallation.
5. [Gradle fallback and baseline rebuild](./gradle-fallback-baseline.md): Understand when the current build baseline must be refreshed.

Use the [reference](../reference/) when you need configuration, command, or state definitions.
