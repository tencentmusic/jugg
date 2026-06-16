---
title: Jugg Backend Self-hosting Checklist
description: Minimum and optional backend endpoints for a self-hosted Jugg backend.
status: active
tags:
  - guide
  - backend
---

# Jugg Backend Self-hosting Checklist

Start with the smallest useful backend, then add update delivery, diagnostics, or remote machine provisioning only when your team needs them.

## Minimum Endpoints

| Endpoint | Method | Minimum behavior |
|---|---|---|
| `/check_update` | `GET` | Return latest version metadata, optional notification, and optional project configuration |
| `/report_event` | `POST` | Accept event JSON and return 2xx |
| `/report_issue` | `POST multipart` | Accept a zipped log file and return 200 |
| `/check_hot_update` | `GET` | Return an empty hot update result if hot update is not used |

If you only need project configuration, return `isNeedUpgrade=false` from `/check_update` and put the configuration in `customConfigJson`.

## `/check_update` Response

| Field | Meaning |
|---|---|
| `latestVersion` | Latest full plugin version known by the backend |
| `isNeedUpgrade` | Whether the IDE should show a full-upgrade notification |
| `downloadUrl` | Download page or direct download URL |
| `templateList` | Legacy field; return an empty array |
| `notification` | Optional IDE notification |
| `customConfigJson` | Optional project configuration applied by the plugin |

## Optional Endpoints

| Capability | Endpoints | Use when |
|---|---|---|
| Full package download | `/download_page`, `/download` | You publish Jugg plugin packages from an internal server |
| Hot update | `/check_hot_update`, `/download_hot_update` | You want jar-level plugin updates |
| Hot update status | `/check_hot_update_status` | Operators need to inspect rollout state |
| Custom compiler download | `/download_custom_compiler` | Project configuration references hosted compiler jars |
| Remote server apply | `/remote_apply` and related flow endpoints | Your team has a remote build machine platform |

## Preflight Checks

- The backend domain is reachable from developer machines.
- Download URLs for plugin packages, hot update jars, and custom compiler jars are directly accessible.
- Returned md5 values match the served files.
- `/report_issue` accepts sufficiently large zip uploads.
- Unused capabilities return empty results instead of 500 errors.

## Related Pages

- [Jugg Backend](./index.md)
- [Project Configuration](./project-config.md)
- [Plugin Delivery](./plugin-delivery.md)
- [Diagnostics](./diagnostics.md)
