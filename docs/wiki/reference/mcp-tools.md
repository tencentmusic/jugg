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

There are currently 20 registered public MCP tools.

| Tool | Main parameters | Purpose |
|---|---|---|
| `settings` | `action`, `key?`, text `value?`, boolean `confirm?` | Global typed settings; guarded edits require explicit confirmation. |
| `version` | None | Returns the Jugg plugin version. |
| `list-projects` | None | Lists projects initialized in the current IDE. |
| `restart` | `projectDir`, `waitAppReadyAfterSuccess` | Restarts the target app; when no launch or HOME Activity exists it only runs `am force-stop`. |
| `compile` | `projectDir` | Compiles without deployment. |
| `deploy` | `projectDir`, `alwaysRestartApp`, `waitAppReadyAfterSuccess` | Compiles and deploys. |
| `clean-reinstall` | `projectDir`, `waitAppReadyAfterSuccess` | Clears app data and reinstalls the APK. |
| `gradle-build` | `projectDir`, `waitAppReadyAfterSuccess` | Forces a Gradle build, then continues with installation/startup. |
| `instrument` | `projectDir`, `sourcePath`, `class`, `method`, `runner`, `extras` | Runs tests from an androidTest source file anchor. |
| `get-compile-status` | `projectDir`, `jobId`, `waitTimeoutMs` | Queries the status of an asynchronous compilation task. |
| `ssh-info` | `projectDir`, `reason`, `requestedBy` | Requests remote SSH troubleshooting information. |
| `report-prepare` | `projectDir`, compatibility parameter `serial` | Prepares a diagnostic bundle, ignores the serial, and collects logs from all target devices. |
| `report-upload` | `projectDir`, `reportId`, `sha256` | Uploads a confirmed diagnostic bundle after verifying it has not changed. |
| `devices` | `projectDir` | Lists devices and marks the selected device. |
| `layout-dump` | `projectDir`, `mode`, `rootLayout`, `includeGone`, `allWindows` | Exports interaction/ui sparse JSON. |
| `view-locate` | `projectDir`, `target` | Finds the location of a UI element. |
| `view-inspect` | `projectDir`, `target`, `expressions` | Reads read-only View properties through reflection, including getters, Kotlin properties, and public fields. |
| `activity-stack` | `projectDir` | Reads the Activity stack. |
| `tap` | `projectDir`, coordinate/percentage/element selector | Performs a tap, long-press, or swipe. |
| `status` | `projectDir`, `refreshChanges` | Queries deployment status and a summary of uncompiled files. |
| `wait-logs` | `projectDir`, `marker`, `tags`, `timeoutMs` | Waits for an app log marker, crash, or timeout. |

`version`, `list-projects`, and `settings` do not require `projectDir`. Project tools require an absolute project path. `settings` returns `CONFIRMATION_REQUIRED` with the Android Studio prompt until `confirm=true` is explicitly supplied.

## Multiple-device behavior

- `compile`, `status`, and `devices` do not require a unique device.
- `deploy`, `clean-reinstall`, and `instrument` target all selected devices when no serial is provided.
- `restart` restarts all selected devices when no serial is provided.
- Single-device UI, Activity stack, and log-wait tools return `MULTIPLE_DEVICE` when several target devices are available instead of producing HTTP 500.
- `report-prepare` accepts the serial for compatibility but ignores it and collects error logs from all target devices on a best-effort basis.

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

When `deploy` succeeds without compiling source files, the message only states that there were no source changes. It does not mean that installation, recovery, or another deployment action was skipped. Details of the latest deployment containing changed files are available only within the current Runtime session.

## UI tool behavior

| Tool | Key boundaries |
|---|---|
| `layout-dump` | Outputs a JSON artifact. mode=interaction (default) prunes a summary; mode=ui preserves the captured tree. Invalid mode returns INVALID_PARAMS. |
| `view-locate` | Coordinates and dimensions use dp. When multiple elements match, the first result is not a safe click target. |
| `view-inspect` | Allows only read-only expressions. An explicit `foo()` uses the getter/query allowlist. A name without parentheses reads a public field first, then resolves `getXxx()` / `isXxx()`. |
| `tap` | Mode priority is coordinate > percent > element. It does not act when multiple elements match. |
| `activity-stack` | Confirms the foreground Activity and page stability. |

ViewHierarchy tools wait for the app to be online before running. A screen that is off or locked returns `DEVICE_NOT_INTERACTIVE`. If the target app is not in the foreground, they return `APP_NOT_FOREGROUND`.

The file contains mode, units, windows, deviceInfo, truncated, and optional warnings/rootLayout. Bounds/padding normally use integer dp; without valid density, px is retained and declared in units. Empty values and defaults visibility=visible, alpha=1, clickable=false, enabled=true, zero padding, and empty children are omitted. Missing textColor can also mean uncollected or default values. The summary counts actual output nodes; pruning does not set budget truncated.

Both modes share field meanings, with public virtual IDs shortened to `_vid_<hash>`; legacy `_vir_id_` selectors still work. interaction first removes invisible/gone nodes and their entire subtrees without promoting children, even with `--include-gone`, omits `padding` and `textColor`, and retains className. ui retains hidden nodes and visual fields within the requested collection scope; GONE nodes still require `--include-gone`.

Real nodes start on new lines, while attributes and coordinates stay compact; synthetic `jugg_details` nodes stay inline. When children include at least one real node, the opening `[` and closing `]}` each start on a new line.

interaction keeps the first node of an offscreen branch and replaces its descendants with a synthetic child containing only `jugg_details`. Placeholders are excluded from node and clickable counts, and folding does not set `truncated`. Use `--mode ui --root-layout <id>` to expand the branch; see [Layout dump and UI evidence](../concepts/layout-dump-and-ui-evidence.md#interaction-summaries-and-ui-hierarchies-use-different-node-scopes) for the rules and retention conditions.

`data.file` always points to the complete artifact. `contentBytes` is its UTF-8 byte size, including node separators. At or below 16 KiB (16,384 bytes), both modes also include the complete JSON object in `structuredContent.data.layout`. Larger results omit this field rather than returning a partial tree. Text content contains only the summary, so the full tree is not sent twice. CLI plain/json output preserves Unicode. The 16 KiB threshold applies to the file, not the total response size or a token limit. Clients must expose structuredContent to the Agent to use the inline object directly.

If part of the UI hierarchy cannot be extracted, `layout-dump` preserves valid JSON and marks its summary with `incomplete hierarchy`. Optional `data.warnings` and JSON node diagnostics give the cause. Location, inspection, and element input still return results for valid matches, along with diagnostics. If there is no match and the current window's hierarchy is incomplete, they return `UI_HIERARCHY_INCOMPLETE`. Budget limits continue to use `truncated` separately. For recovery steps, see [UI automation](../capabilities/tools/ui-automation.md#when-compose-content-is-missing).

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
| `MULTIPLE_DEVICE` | A single-device operation found several target devices and requires an explicit serial. |
| `DEVICE_NOT_INTERACTIVE` | The device screen is off or the device is not interactive. |
| `APP_NOT_FOREGROUND` | The target app is not in the foreground. |
| `UI_HIERARCHY_INCOMPLETE` | The current window's UI hierarchy is incomplete; the target element cannot be assumed to be absent. |
| `INTERNAL_ERROR` | Internal error. |

## Non-public actions

Actions that exist in the code but are not registered cannot be called by external MCP clients. These include screenshot, record, start activity, start app, emulator, and layout verify actions. Use `tools/list` and the public tools table on this page to determine whether a tool is public.

## Related pages

- [CLI commands](./cli-commands.md)
- [MCP guide](../guide/mcp.md)
- [UI inspection](../guide/ui-inspection.md)
