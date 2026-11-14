---
title: Does clearing app data or cache affect incremental compilation?
description: Understand how clearing app data or cache affects incremental deployment, how another run recovers it, and when to use Clean Reinstall or export an incremental APK.
status: active
tags:
  - troubleshooting
  - faq
  - clean-data
  - deploy
---

# Does clearing app data or cache affect incremental compilation?

It affects the incremental deployment results on the device. Ordinary incremental deployment artifacts are stored in `code_cache/` in the app's data area. Clearing app data directly from system settings also deletes these artifacts, so the previous incremental changes are lost when you reopen the app.

The effect of clearing cache depends on what is deleted: removing incremental artifacts from `code_cache/` has the same result. Clearing only business caches while keeping the incremental artifacts does not remove incremental changes.

Clearing data on the phone does not delete the incremental artifacts already compiled on your computer. Run Jugg Run again so Jugg can check the device's deployment state, recover it, and redeploy the incremental artifacts. Reopening the app on the phone alone does not trigger this recovery.

To test with cleared data while keeping the current changes, choose an option in the Gradle fallback dialog, `Confirm fallback`:

| Use case | Entry point | Result |
|---|---|---|
| Verify cleared data on the current device | `Clean And Reinstall` | Clears data, reinstalls the APK, and restores the current incremental deployment in one operation |
| Give testers an APK containing the current changes that remain after clearing data | `Export incremental APK` | Writes the compiled incremental results into the APK and signs it again; after installing the exported APK, clearing app data does not remove the changes written into it |

Compile the current changes before exporting. See [Export an incremental APK](../guide/export-incremental-apk.md) for the steps. Preserving incremental changes does not preserve app data: clearing data still removes login state, local databases, and business caches.

## Related pages

- [Clear app data](../guide/clean-data.md)
- [Export an incremental APK](../guide/export-incremental-apk.md)
- [Deployment state and recovery](../concepts/deploy-state-recover.md)
