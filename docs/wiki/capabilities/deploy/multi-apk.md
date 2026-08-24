---
title: Multiple APKs
description: Explains how Jugg routes deployments across base, split, test, and other APK targets.
status: active
tags:
  - capability
  - deploy
  - multi-apk
---

# Multiple APKs

Jugg assigns artifacts from the same deployment to the correct APK target. For base APKs, split APKs, app-test APKs, library test APKs, and similar scenarios, deployment data carries target APK ownership so that resources, DEX, and overlays are not written to the wrong location.

## APK ownership rules

| Scenario | Current support | Deployment strategy |
|---|---|---|
| Base + split APKs | Supported | Filters deployment items by target APK path |
| App androidTest APK | Supported | Deploys it with the app APK grouped by applicationId |
| Self-targeting library Test APK | Can be added when missing | Loads it lazily when needed and records build history |
| Identically named resources in multiple APKs | Distinguished | Determines overrides by target APK + relative path |
| `resources.arsc` or full resource push | Supported | Prevents resources in the base and test APKs from filtering each other |

> [!IMPORTANT]
> Relative file paths alone are insufficient for multiple APKs. Jugg prioritizes target APK ownership on each deployment item to decide which APK or overlay receives an artifact.

## How it takes effect

```text
Generate JuggDeployData
  -> DeployItem records targetApkPaths
  -> JuggDeployTask groups by applicationId
  -> filterForApks() trims scoped data for the current APKs
  -> JuggDeployer runs install / swap for each APK group
  -> Commit global deployment history after the entire run succeeds
```

`filterForApks()` is used only for per-transport APK routing. Scoped data after filtering must not update global deployment history. The global commit uses the original deployment data after the entire run succeeds.

## Android Test scenarios

When sourcePath points to a library androidTest and the target test APK does not yet exist, Jugg can add the corresponding library Test APK and update overlay IDs after installation succeeds. This prevents the first replay from treating the newly added APK's missing checkpoint as a state mismatch.

## Related pages

- [Deployment history and cache](./deploy-history-cache.md)
- [Recover and Retry](./recover-and-retry.md)
- [Application Android Test](../test/application-android-test.md)
- [Library Android Test](../test/library-android-test.md)
