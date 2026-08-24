---
title: Clean Reinstall
description: Explains when Jugg reinstalls an app and how Clean Reinstall rebuilds the device deployment baseline.
status: active
tags:
  - capability
  - deploy
  - install
---

# Clean Reinstall

Clean Reinstall re-establishes a trustworthy baseline for the app on the device. Jugg installs the current APK and, when needed, clears app data, resets deployment history, and resets the overlay checkpoint so that later incremental deployments do not build on mismatched state.

## When reinstallation occurs

| Scenario | Current support | Deployment strategy |
|---|---|---|
| First deployment after a Gradle build | Supported | Installs the current APK directly and writes the deployment cache |
| The target app is not installed on the device | Supported | Uses install instead of incremental deployment |
| The Clean Reinstall option is enabled | Supported | Clears app data before installing the APK |
| Overlay or cache state does not match | Supported | Reinstalls after recovery fails |
| APK installation reports a recoverable exception | Limited retry supported | Uninstalls the current applicationId and reinstalls when needed |
| Multi-APK app | Supported | Groups base, split, or test APKs by applicationId for installation |

> [!NOTE]
> Clean Reinstall resets the incremental deployment baseline. It is not a regular hot-update path, but it gives the next incremental deployment a trustworthy APK, history, and overlay state.

## How it takes effect

```text
Installation is required
  -> Stop the target app
  -> Assemble JuggDeployData for installation
  -> Install APKs grouped by applicationId
  -> Write the Android Studio deployment cache
  -> Update Jugg deployment history and overlay ID
  -> Reset staging / deployed file state
```

Jugg stops the app before installation so users do not see the app get stopped immediately after a successful install. After installation succeeds, Jugg records the current APK and overlay ID as the new deployment checkpoint. A reinstall triggered by recovery also clears old deployed data, resource APKs, and staging state.

## Relationship to incremental deployment

Clean Reinstall commonly appears at these boundaries:

- After a successful Gradle compilation, the complete APK must establish a new baseline.
- A dry deploy or Direct Overlay state check fails, so device state is unsuitable for continued hot updates.
- The app was externally installed, uninstalled, had its data cleared, or was used with another project, making the historical checkpoint untrustworthy.

After a successful reinstall, later changes still attempt incremental deployment first.

## Related pages

- [Clear data](../../guide/clean-data.md)
- [Deployment state and recovery](../../concepts/deploy-state-recover.md)
- [Recover and Retry](./recover-and-retry.md)
- [Deployment history and cache](./deploy-history-cache.md)
- [Multiple APKs](./multi-apk.md)
- [Gradle fallback](../compile/gradle-fallback.md)
