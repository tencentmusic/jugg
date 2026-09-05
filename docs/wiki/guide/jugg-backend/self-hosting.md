---
title: Jugg backend self-hosting checklist
description: Implement the minimum and optional interfaces for a self-hosted Jugg backend and verify it before launch.
status: active
tags:
  - guide
  - backend
---

# Jugg backend self-hosting checklist

Self-hosting usually serves two goals: centrally distributing Jugg configuration and plugin versions within a team, or collecting usage events and providing hot updates and remote-machine applications. Implement the minimum interfaces first, then add enhanced features gradually.

## Minimum viable interfaces

| Interface | Method | Minimum behavior |
|---|---|---|
| `/check_update` | `GET` | Return the current latest version, whether an upgrade is required, a download entry point, notifications, and project configuration |
| `/report_event` | `POST` | Accept event JSON and return 2xx on success |
| `/check_hot_update` | `GET` | Return an empty update result when hot updates are unused |

If only project configuration distribution is required, `/check_update` can return `isNeedUpgrade=false` and place project configuration in `customConfigJson`. Other interfaces can return success or an empty result.

User-submitted issue logs do not request a self-hosted backend. The plugin uploads the diagnostic bundle to a fixed issue-reporting service. See [Report an issue](../report-issue.md).

## `/check_update` response

| Field | Description |
|---|---|
| `latestVersion` | Latest full plugin version according to the backend |
| `isNeedUpgrade` | Whether to ask the user to download and install a full plugin package |
| `downloadUrl` | Full plugin package download page or URL |
| `templateList` | Legacy field; can currently return an empty array |
| `notification` | Optional notification displayed by the plugin in the IDE |
| `customConfigJson` | Optional project configuration applied by the plugin to the current project |

`customConfigJson` is the most commonly used self-hosted backend feature. It can return different configuration by project name. See [Project configuration distribution](./project-config.md).

## Optional enhanced interfaces

| Feature | Related interfaces | When to add it |
|---|---|---|
| Full plugin package download | `/download_page`, `/download` | Publish plugin packages centrally from the internal backend |
| Hot-update download | `/check_hot_update`, `/download_hot_update` | Distribute JAR-level updates |
| Hot-update status | `/check_hot_update_status` | Operations or staged-rollout diagnostics need to inspect current hot-update state |
| Custom compiler download | `/download_custom_compiler` | Distribute a custom compiler JAR in project configuration |
| Remote-machine application | Interactive interfaces such as `/remote_apply` | The team has an internal cloud development machine application system |

## Predeployment checks

- The backend domain must be reachable from development machines with Jugg installed.
- Download links for plugin packages, hot-update JARs, and custom compiler JARs must support direct downloads.
- When `md5` is returned, it must match the file content.
- When using a database, store at least the event time, user identity, project, version, action, and result.
- For unused features, return empty configuration or an empty update instead of 500.

## Relationship to local features

The backend manages configuration, distribution, and usage events; it does not take over local compilation or deployment, and it does not receive user issue logs. Jugg Run, Debug, Android Test, CLI, and MCP still execute in the local Android Studio or command-line environment.

## Related pages

- [Jugg backend](./index.md)
- [Project configuration distribution](./project-config.md)
- [Plugin distribution and hot updates](./plugin-delivery.md)
- [Diagnostics reporting](./diagnostics.md)
- [Report an issue](../report-issue.md)
