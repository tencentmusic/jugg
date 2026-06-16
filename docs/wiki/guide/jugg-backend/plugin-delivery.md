---
title: Jugg Backend Plugin Delivery
description: Full plugin package upgrades, hot updates, rollout decisions, and custom compiler downloads.
status: active
tags:
  - guide
  - backend
  - update
---

# Jugg Backend Plugin Delivery

The backend can deliver full plugin packages and optional jar-level hot updates. Full packages are the normal release path. Hot updates are useful when a team wants to ship plugin jar changes without immediately replacing the whole plugin zip.

## Full Package Upgrade

```text
Plugin checks for updates
  -> Backend compares the current version with the latest package
  -> Backend returns isNeedUpgrade and downloadUrl
  -> Jugg shows an IDE notification
  -> User downloads and installs the plugin package
```

If your team does not distribute plugin packages from the backend, always return `isNeedUpgrade=false`.

## Hot Update

Hot update uses `/check_hot_update` and `/download_hot_update`.

| Field | Meaning |
|---|---|
| `isNeedUpdate` | Whether a hot update is available |
| `targetVersion` | Target version |
| `updateInfo` | Notification shown after update |
| `jarFileInfos` | Jar files to download and verify |
| `isNeedReinstall` | Whether the plugin should be reinstalled after the update |

Each jar entry contains a unique file name, URL, and md5. The plugin downloads missing files and verifies md5 before using them.

## Rollout Strategy

The backend can decide whether to return `isNeedUpdate=true` based on user allowlists, publish age, known bad versions, or a manual check action. The rollout decision stays server-side; the plugin still follows the same download and verification flow.

## Related Pages

- [Self-hosting Checklist](./self-hosting.md)
- [Project Configuration](./project-config.md)
- [Custom Compiler](../custom-compiler.md)
