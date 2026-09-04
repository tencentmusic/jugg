---
title: Jugg backend diagnostics reporting
description: Support Jugg usage-event reporting from a self-hosted backend, and understand how it differs from issue log uploads.
status: active
tags:
  - guide
  - backend
  - diagnostics
---

# Jugg backend diagnostics reporting

Backend diagnostics reporting covers usage events only. It does not affect local compilation or deployment results. When reporting fails, the plugin normally records a log and continues the current flow.

Log bundles submitted through [Report an issue](../report-issue.md) do not go through a self-hosted backend and do not use a Custom Server.

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

## Issue logs do not use the backend

The plugin uploads issue logs to the fixed issue-reporting service at `https://jugg.sickworm.com/report_issue`. A self-hosted backend does not need to implement `/report_issue`; implementing that interface also does not change the path users take when they report a problem.

For the user-facing flow, diagnostic-bundle contents, and Report ID, see [Report an issue](../report-issue.md).

## Storage recommendations

- Organize event records by date, project, or action.
- Retain the report time, user, project, plugin version, action name, and result.
- Set a reasonable retention period for event records.
- If the team has privacy or compliance requirements, define which user identifiers and project information event fields may contain before release.
- Return a clear error when reporting fails, but do not let that failure affect local compilation or deployment.

## Related pages

- [Report an issue](../report-issue.md)
- [Log files](../../reference/log-files.md)
- [Compilation failed](../../troubleshooting/compile-failed.md)
- [The app cannot install, launch, or enter Debug](../../troubleshooting/app-cannot-run.md)
