---
title: Clear app data
description: Use Clean Reinstall to clear app data and reinstall, and understand how it differs from clearing data manually in system settings.
status: active
tags:
  - guide
  - clean-data
  - reinstall
---

# Clear app data

Use Jugg's Clean Reinstall when you need a clean app state. It clears app data, reinstalls the APK, and rebuilds Jugg's deployment state.

## When to use it

- Verify first launch, first login, database migration, or permission dialogs.
- Rebuild the state after app data was modified incorrectly by hand.
- Resolve a mismatch between the installation state on the device and Jugg's records.
- Confirm that old content is gone after deleting a class, resource, or Manifest node.

If you only need a full Gradle build without clearing data, use [Fall back to Gradle compilation](./downgrade-gradle.md).

## Where to trigger it

A common entry point is the Gradle fallback confirmation dialog:

```text
Confirm fallback
  -> Clean And Reinstall
```

From the CLI, run:

```bash
jugg clean-reinstall
```

After it succeeds, navigate back to the screen you need to verify. Clearing data removes login state, local databases, and caches as expected.

## Avoid clearing data manually

Clearing app data directly from system settings also removes Jugg's deployment records stored in the app data area. Jugg attempts to recover them on the next run, but when the goal is specifically to test with cleared data, Clean Reinstall is more reliable.

## Related pages

- [Run an app](./run.md)
- [Fall back to Gradle compilation](./downgrade-gradle.md)
- [Deployment state and recovery](../concepts/deploy-state-recover.md)
- [Clean Reinstall](../capabilities/deploy/clean-reinstall.md)
- [The app cannot install, launch, or enter Debug](../troubleshooting/app-cannot-run.md)
