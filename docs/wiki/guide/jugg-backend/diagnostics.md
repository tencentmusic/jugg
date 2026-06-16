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

## Issue Log Upload

When a user submits an issue, the plugin uploads a zip file to `/report_issue` by multipart form. The package contains Jugg logs and related context that can help identify compile, deploy, remote build, or device-side failures.

Store the original zip and return 200 when the upload succeeds. Keep retention and privacy requirements in mind because logs may include local paths and build context.

## Related Pages

- [Log Files](../../reference/log-files.md)
- [Compile Troubleshooting](../../troubleshooting/compile.md)
- [Deploy Troubleshooting](../../troubleshooting/deploy.md)
