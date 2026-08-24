---
title: Jugg backend diagnostics reporting
description: Support Jugg event reporting and issue log uploads from a self-hosted backend.
status: active
tags:
  - guide
  - backend
  - diagnostics
---

# Jugg backend diagnostics reporting

Diagnostics reporting helps a team understand Jugg usage and collect logs when users report problems. It does not affect local compilation or deployment results. When reporting fails, the plugin normally records a log and continues the current flow.

## Event reporting

The plugin sends event JSON to `/report_event`. The backend can store these fields for metrics and diagnostics:

| Field | Description |
|---|---|
| `version` | Jugg plugin version |
| `ide_version` | Android Studio / IntelliJ version |
| `username` | User identifier |
| `project_id` | Project identifier, usually derived from the Git repository or project name |
| `session_id` | Identifier for the current compilation and deployment session |
| `action` | Action name, such as update check, compilation, or deployment |
| `is_success` | Whether the action succeeded |
| `cost_time` | Elapsed time |
| `detail` | Additional information |

A self-hosted backend can return only an event ID or a simple success message. The important requirement is that event-reporting failures must not affect local development.

Whether or not the server exists or the request succeeds, the plugin writes the same event to the `jugg_event` table in `~/.jugg/action.db`. The local database only retains event history; it is not an automatic compensation queue for remote failures.

## Issue log uploads

When a user reports an issue, the plugin packages allowlisted and redacted diagnostic files into a ZIP and uploads it as multipart data to the complete HTTPS endpoint confirmed by the user. The client does not append a path or try a fallback server.

The log bundle usually helps answer:

- Whether the run used incremental compilation, Gradle fallback, or Clean Reinstall.
- Whether deployment failed during installation, hot update, restart, or device communication.
- Whether remote compilation or a custom compiler produced logs.
- Which important logcat excerpts appeared on the user's device.

The backend should store the ZIP and return 2xx. The response can include a JSON `reportId`; without that field, the plugin uses its locally generated report ID.

## Storage recommendations

- Organize log files by date, project, or report ID.
- Retain upload time, user, project, plugin version, and client IP.
- Set a reasonable retention period so local paths and runtime logs are not stored indefinitely.
- If the team has privacy or compliance requirements, define which local paths and build information the log bundle may contain before release.
- Return a clear error when an upload fails so the user can resubmit it or package logs manually.

## Related pages

- [Log files](../../reference/log-files.md)
- [Compilation failed](../../troubleshooting/compile-failed.md)
- [The app cannot install, launch, or enter Debug](../../troubleshooting/app-cannot-run.md)
