---
title: MCP tools
description: Summarizes Jugg MCP service information, public tools, return structures, asynchronous behavior, and error codes.
status: active
tags:
  - reference
  - mcp
---

# MCP tools

This page is the reference entry point for Jugg MCP tool names, input and output conventions, and public availability. Use the capability pages when choosing a workflow; use this page when confirming an exact tool contract.

Authoritative runtime source: MCP `tools/list`.

## Service information

| Item | Value |
|---|---|
| Port range | `12320..12329` |
| HTTP path | `/jugg-mcp` |
| Protocol | JSON-RPC `2.0` |
| Supported request header | `MCP-Protocol-Version`, supporting `2025-06-18` and `2025-11-25` |

## Return structure

The `structuredContent` returned by `tools/call` always contains the following fields:

```json
{
  "status": "OK|ERROR",
  "message": "string",
  "data": {},
  "artifacts": [],
  "errorCode": "string|null"
}
```

Compilation tools may return `isFinal=false` and a `jobId`. In that case, continue calling `get-compile-status` until the task reaches a final state.

## Public tools

There are currently 18 registered public MCP tools.

| Tool | Main parameters | Purpose |
|---|---|---|
| `version` | None | Returns the Jugg plugin version. |
| `list-projects` | None | Lists projects initialized in the current IDE. |
| `restart` | `projectDir`, `waitAppReadyAfterSuccess` | Restarts the target app. |
| `compile` | `projectDir` | Compiles without deployment. |
| `deploy` | `projectDir`, `alwaysRestartApp`, `waitAppReadyAfterSuccess` | Compiles and deploys. |
| `clean-reinstall` | `projectDir`, `waitAppReadyAfterSuccess` | Clears app data and reinstalls the APK. |
| `gradle-build` | `projectDir`, `waitAppReadyAfterSuccess` | Forces a Gradle build, followed by the installation and launch flow. |
| `instrument` | `projectDir`, `sourcePath`, `class`, `method`, `runner`, `extras` | Runs tests from an androidTest source file anchor. |
| `get-compile-status` | `projectDir`, `jobId`, `waitTimeoutMs` | Queries the status of an asynchronous compilation task. |
| `ssh-info` | `projectDir`, `reason`, `requestedBy` | Requests remote SSH troubleshooting information. |
| `devices` | `projectDir` | Lists devices and marks the selected device. |
| `layout-dump` | `projectDir`, `rootLayout`, `includeGone`, `allWindows` | Exports the UI hierarchy as HTML. |
| `view-locate` | `projectDir`, `target` | Finds the location of a UI element. |
| `view-inspect` | `projectDir`, `target`, `expressions` | Reads read-only View properties through reflection, including getters, Kotlin properties, and public fields. |
| `activity-stack` | `projectDir` | Reads the Activity stack. |
| `tap` | `projectDir`, coordinate/percentage/element selector | Performs a tap, long-press, or swipe. |
| `status` | `projectDir`, `refreshChanges` | Queries deployment status and a summary of uncompiled files. |
| `wait-logs` | `projectDir`, `marker`, `tags`, `timeoutMs` | Waits for an app log marker, crash, or timeout. |

`version` and `list-projects` do not require `projectDir`. All other tools require an absolute project path.

## Asynchronous behavior of compilation tools

`deploy`, `gradle-build`, and `instrument` may first return a running state:

```json
{
  "data": {
    "isFinal": false,
    "jobId": "..."
  }
}
```

The client should call `get-compile-status` with:

```json
{
  "projectDir": "/path/to/project",
  "jobId": "...",
  "waitTimeoutMs": 5000
}
```

Continue until `data.status` is `success`, `failed`, `canceled`, or `unknown`. The final state returns `isCompileSuccess` and `isDeploySuccess`. Failures may also include `detail`, `detailLength`, and `detailTruncated`.

## UI tool behavior

| Tool | Key boundaries |
|---|---|
| `layout-dump` | Outputs an HTML artifact. The internal JSON is not part of the public contract. |
| `view-locate` | Coordinates and dimensions use dp. When multiple elements match, the first result is not a safe click target. |
| `view-inspect` | Allows only read-only expressions. An explicit `foo()` uses the getter/query allowlist. A name without parentheses reads a public field first, then resolves `getXxx()` / `isXxx()`. |
| `tap` | Mode priority is coordinate > percent > element. It does not act when multiple elements match. |
| `activity-stack` | Confirms the foreground Activity and page stability. |

ViewHierarchy tools wait for the app to be online before running. A screen that is off or locked returns `DEVICE_NOT_INTERACTIVE`. If the target app is not in the foreground, they return `APP_NOT_FOREGROUND`.

## Common error codes

| Error code | Meaning |
|---|---|
| `INVALID_JSON_RPC` | Invalid JSON-RPC format. |
| `METHOD_NOT_SUPPORTED` | Unsupported method. |
| `TOOL_NOT_FOUND` | Tool is not registered. |
| `INVALID_PARAMS` | Invalid parameters. |
| `INVALID_REGEX` | Invalid log marker regular expression. |
| `PROJECT_NOT_INITIALIZED` | The project has not completed Jugg initialization. The error message includes the requested path and the currently initialized projects. |
| `NO_DEPLOY_BASELINE` | No deployment or full build baseline. |
| `NO_DEVICE` | No available device. |
| `DEVICE_NOT_INTERACTIVE` | The device screen is off or the device is not interactive. |
| `APP_NOT_FOREGROUND` | The target app is not in the foreground. |
| `INTERNAL_ERROR` | Internal error. |

## Non-public actions

Actions that exist in the code but are not registered cannot be called by external MCP clients. These include screenshot, record, start activity, start app, emulator, and layout verify actions. Use `tools/list` and the public tools table on this page to determine whether a tool is public.

## Related pages

- [CLI commands](./cli-commands.md)
- [MCP guide](../guide/mcp.md)
- [UI inspection](../guide/ui-inspection.md)
