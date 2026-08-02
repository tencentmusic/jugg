---
title: Jugg Backend Diagnostics
description: Event reporting and issue log upload capabilities supported by the Jugg backend surface.
status: active
tags:
  - guide
  - backend
  - diagnostics
---

# Jugg Backend Diagnostics

Diagnostics help a team understand Jugg usage and investigate user-submitted issues. Reporting failures should not block local compile or deploy.

## Event Reporting

The plugin posts event JSON to `/report_event`.

| Field | Meaning |
|---|---|
| `version` | Jugg plugin version |
| `ide_version` | Android Studio or IntelliJ version |
| `username` | User identifier |
| `project_id` | Project identifier |
| `session_id` | Compile or deploy session identifier |
| `action` | Action name |
| `is_success` | Whether the action succeeded |
| `cost_time` | Duration |
| `detail` | Additional details |

The backend can return an event ID or a simple success response.

Every report event is also appended to the local `~/.jugg/action.db` `jugg_event` table, regardless of backend availability or request outcome. This database is local history, not an automatic retry queue.

## Issue Log Upload

When a user submits an issue, the plugin uploads a whitelist-generated and redacted zip by multipart form to `https://jugg.sickworm.com/report_issue`. The destination is fixed and hidden from the dialog, and the plugin does not try fallback servers. All candidates are selected by default; Jugg log files are listed first and cannot be deselected.

Store the zip and return a 2xx response. The response may contain a JSON `reportId`; otherwise the plugin uses its locally generated report ID. The manifest lists the actual archive entries and their sensitivity.

## Related Pages

- [Log Files](../../reference/log-files.md)
- [Compile Troubleshooting](../../troubleshooting/compile.md)
- [Deploy Troubleshooting](../../troubleshooting/deploy.md)
