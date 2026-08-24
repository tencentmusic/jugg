---
title: MCP
description: Connect to Jugg MCP, use its public tools and response model, and decide between MCP and CLI.
status: active
tags:
  - guide
  - mcp
---

# MCP

Jugg MCP is a local JSON-RPC service that exposes plugin features to agents. It uses the same underlying features as the CLI. The CLI wraps MCP in a command-line interface and is usually better suited to everyday agent use.

> [!TIP]
> If you only need an agent to compile, deploy, run tests, or operate a device, install the Jugg CLI Skill first. Use this page only when your client must configure an MCP server directly.

## Service information

| Item | Value |
|---|---|
| Port range | `12320..12329` |
| Path | `/jugg-mcp` |
| Protocol | HTTP + JSON-RPC `2.0` |
| Global tools | `version`, `list-projects` |
| Non-global tools | All require `projectDir` |

When multiple Android Studio instances are open, ports increment within the range. A client should discover the port first or use the CLI's port discovery.

## Call model

The business result returned by an MCP tool is in `structuredContent`:

```json
{
  "status": "OK",
  "message": "...",
  "data": {},
  "artifacts": [],
  "errorCode": null
}
```

Protocol success does not mean business success. The client must read `structuredContent.status` and, for compilation and deployment tools, continue reading fields such as `isCompileSuccess` and `isDeploySuccess`.

## Public tools

| Category | Tools |
|---|---|
| Projects and version | `version`, `list-projects` |
| Build and deployment | `compile`, `deploy`, `gradle-build`, `clean-reinstall`, `get-compile-status` |
| Testing | `instrument` |
| Runtime | `restart`, `wait-logs`, `activity-stack` |
| Devices | `devices` |
| UI | `layout-dump`, `view-locate`, `view-inspect`, `tap` |
| Remote diagnostics | `ssh-info` |
| Status | `status` |

`layout-verify` and `figma-layout-verify` are not currently public tools unless they are registered in the public tool list later.

## Asynchronous compilation

`compile`, `deploy`, `gradle-build`, and `instrument` may return a running state:

```text
compile/deploy/gradle-build/instrument
  -> Return jobId
  -> Call get-compile-status(projectDir, jobId)
  -> Continue until success / failed / canceled
```

The CLI includes polling. When using MCP directly, poll `get-compile-status` yourself. You can also pass `waitTimeoutMs` to reduce empty polling.

## Why the CLI is usually preferred

Compared with configuring MCP directly, the CLI offers these advantages:

- The CLI is packaged with the skill, making correct instructions easier for an agent to obtain.
- The CLI can combine commands, scripts, and pipelines, which suits continuous terminal and agent workflows.
- The CLI includes asynchronous polling and heartbeats, reducing agent-side polling overhead.
- CLI output modes support people, agents, and scripts.
- When both CLI and MCP are available, agent instructions should define one preferred entry point to avoid mixing both interfaces in the same task.

## When to use MCP directly

MCP is suitable when:

- Your platform can configure only MCP servers.
- You need to manage every tool through one MCP client.
- You need to read the MCP `tools/list` schema directly.

In other situations, prefer the CLI:

- Normal terminal use.
- Compilation verification by an agent after code changes.
- Long-running tasks that require frequent polling when the client has no effective waiting mechanism.

## Related pages

- [CLI](./cli.md)
- [MCP and CLI](../concepts/mcp-and-cli.md)
- [MCP for agents](../capabilities/tools/mcp.md)
- [MCP tools](../reference/mcp-tools.md)
- [Agent or CLI execution failed](../troubleshooting/agent-command-failed.md)
