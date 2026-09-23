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

Backend diagnostic configuration covers usage events and the optional automatic failure-log upload switch. It does not affect local compilation or deployment results. When reporting fails, the plugin records a log and continues the current flow.

When a backend service is available, manually submitted issue logs are uploaded to that server's `/report_issue`. Automatic failure-log uploads go only to the available backend. A self-hosted backend can use project configuration to enable automatic uploads and exclude known errors.

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

## Automatic failure-log uploads

Set `autoUploadFailureLogs=true` in project configuration to enable automatic uploads after final failures. `autoUploadFailureLogsExcludeRegex` is an optional exclusion regex. It performs a contains match against the current Run's final error summary and suppresses the upload when it matches. An empty field applies no filtering, while an invalid regex skips that upload.

Automatic uploads cover final compilation failures and final failures after deployment has actually started. Cancellation, skipped deployment, no device before deployment starts, and a successful final result after a Gradle fallback do not trigger an upload. The bundle contains the two most recent Jugg logs, environment information, a project summary, redacted project snapshots, the hook debug log when present, and the manifest; it does not collect adb logcat. Each Run uploads at most once. An explicitly configured Custom Server receives original logs and failure error text; an automatically selected backend still receives redacted content.

## Destinations for manual and automatic reports

With an available backend, manual reports go to `/report_issue` under that server's root address, so the backend must implement this endpoint. Without an available backend, they use `https://jugg.sickworm.com/report_issue`. The backend may use HTTP or HTTPS; HTTP provides no TLS transport protection. Automatic failure-log uploads request only the available backend's `/report_issue`; without one, they are skipped and never fall back to the public service.

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
