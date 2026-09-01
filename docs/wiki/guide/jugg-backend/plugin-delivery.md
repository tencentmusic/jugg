---
title: Jugg backend plugin distribution and hot updates
description: Support full plugin package upgrades, hot updates, staged rollouts, and custom compiler downloads from a Jugg backend.
status: active
tags:
  - guide
  - backend
  - update
---

# Jugg backend plugin distribution and hot updates

A Jugg backend can provide two kinds of updates: full plugin package upgrades and hot updates. Full packages suit normal releases, while hot updates distribute plugin JAR changes without immediately replacing the entire package.

## Full plugin package upgrades

Full upgrades are driven by the `/check_update` response:

```text
The plugin checks for updates
  -> The backend compares the current and latest versions
  -> The backend returns isNeedUpgrade and downloadUrl
  -> The plugin displays an upgrade notification in the IDE
  -> The user opens the download page or downloads and installs the plugin package manually
```

The backend usually needs:

- A directory for plugin ZIP files.
- An accessible download page or direct download URL.
- A rule for selecting the latest version.
- Optional release notes.

If the team does not want the backend to manage plugin packages, always return `isNeedUpgrade=false`.

## Hot updates

Hot updates use `/check_hot_update` and `/download_hot_update`. The backend returns the target version, update notes, whether reinstallation is required, and a set of JAR file records.

| Field | Description |
|---|---|
| `isNeedUpdate` | Whether a hot update is required |
| `targetVersion` | Target version |
| `updateInfo` | Notification displayed to the user after the update |
| `jarFileInfos` | List of JARs to download and verify |
| `isNeedReinstall` | Whether to reinstall the plugin after the update |

Each JAR record contains a unique filename, download URL, and md5. The plugin downloads missing files and verifies their md5 values. A file is not used after verification fails.

## Staged rollout strategies

The backend decides which users receive a hot update. Common strategies include:

- Roll out to specified users.
- Expand the audience in stages based on release time.
- Force an update for versions with known issues.
- Return an available update immediately when a user checks manually.

The rollout strategy affects only whether the backend returns `isNeedUpdate=true`; it does not change the plugin-side download or verification flow.

## Custom compiler downloads

Custom compilers can be distributed as part of project configuration. The backend only needs to ensure that `customCompilers.path` points to a downloadable JAR and provides the matching md5.

When using `/download_custom_compiler`, allow access only to files under the project configuration directory and reject file keys containing path traversal.

## Related pages

- [Self-hosting checklist](./self-hosting.md)
- [Project configuration distribution](./project-config.md)
- [Custom compiler](../custom-compiler.md)
