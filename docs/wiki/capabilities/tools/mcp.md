---
title: MCP for Agents
description: Explains how Agents call Jugg plugin capabilities through the local JSON-RPC MCP interface.
status: active
tags:
  - capability
  - tools
  - mcp
---

# MCP for Agents

Jugg MCP is a local JSON-RPC interface exposed by the IDE plugin to Agents. Agents can use it to list projects, trigger compilation and deployment, query status, read the UI hierarchy, locate Views, perform input, and wait for log results.

## Input and output boundaries

| User task | Current support | Input and output boundary |
|---|---|---|
| Discover the plugin and projects | Supported | `version` and `list-projects` do not require `projectDir` |
| Trigger compilation / deployment / reinstallation / restart | Supported | Requires `projectDir`; long tasks may return a `jobId`, which the client then polls to completion through `get-compile-status` |
| Run androidTest | Supported | `instrument` anchors the androidTest source file and target test APK with `sourcePath` |
| Inspect devices, Activities, status, and logs | Supported | `devices`, `activity-stack`, `status`, `wait-logs` |
| Inspect and interact with the UI | Supported | `layout-dump`, `view-locate`, `view-inspect`, `tap` |
| Figma or batch layout verification action | Not currently public | An action class does not make an MCP tool callable; tools absent from `tools/list` cannot be invoked |

## Protocol and response

The MCP service listens on the local port range `12320..12329` at `/jugg-mcp` and uses JSON-RPC `2.0`. Business results are returned uniformly in `structuredContent`:

```json
{
  "status": "OK|ERROR",
  "message": "string",
  "data": {},
  "artifacts": [],
  "errorCode": "string|null"
}
```

Protocol errors and business failures must be evaluated separately. HTTP/JSON-RPC success means only that the request was processed. To determine whether the tool succeeded, continue by reading `structuredContent.status` and the business fields.

## How tools execute

```text
MCP request
  -> Validate the schema and projectDir initialization
  -> Find the registered tool action
  -> Execute compilation, deployment, device, ViewHierarchy, or log capabilities
  -> Return structuredContent
```

Except for `version` and `list-projects`, public tools require the project to have been initialized by Jugg in the IDE. Runtime tools wait for the app to come online before execution. UI tools also verify that the device is interactive and the target app is in the foreground.

## Asynchronous compilation model

Long tasks such as `deploy`, `gradle-build`, and `instrument` may return `data.status=running` and a `jobId`. The client should call:

```text
get-compile-status(projectDir, jobId, waitTimeoutMs)
```

The terminal result includes compilation and deployment fields such as `isCompileSuccess`, `isDeploySuccess`, `detail`, and log paths. The CLI wraps this polling. A client using MCP directly must complete it itself.

## Related pages

- [MCP guide](../../guide/mcp.md)
- [MCP and CLI internals](../../concepts/mcp-and-cli.md)
- [Jugg CLI](./cli.md)
- [UI automation](./ui-automation.md)
- [UI layout evidence](./layout-verify.md)
- [MCP tool reference](../../reference/mcp-tools.md)
