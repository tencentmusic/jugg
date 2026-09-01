---
title: Jugg CLI and Agent Skills
description: Summarizes tool capabilities related to Agent Skills, Jugg CLI, MCP, and UI automation.
status: active
tags:
  - capability
  - tools
  - overview
---

# Jugg CLI and Agent Skills

This section covers the automation entry points used by Agents to drive Jugg in Android projects. The pages are organized by the Agent Skill workflow, Jugg CLI task domains, and direct MCP access by Agents.

## Pages

| Page | When to use it | Underlying capability |
|---|---|---|
| [Agent Skills](./agent-skills.md) | Understand the Agent workflow for editing, building, deploying, verifying, and iterating | The `jugg-android-dev-loop` skill and reference documents |
| [Jugg CLI](./cli.md) | Decide when an Agent or terminal should drive Jugg through the command-line entry point | CLI wrappers around public Jugg MCP tools |
| [Build and deployment](./cli-build-deploy.md) | Compile, deploy, reinstall, restart, or fall back to Gradle | `compile`, `deploy`, `gradle-build`, `clean-reinstall`, `restart` |
| [Android Test](./cli-android-test.md) | Run androidTest from a test source file, class, or method anchor | `instrument` |
| [Runtime and devices](./cli-runtime-device.md) | Inspect status, connected devices, the Activity stack, and runtime logs | `status`, `devices`, `activity-stack`, `wait-logs` |
| [UI automation](./ui-automation.md) | Inspect, locate, tap, and read runtime UI state | `layout-dump`, `view-locate`, `view-inspect`, `tap` |
| [UI layout evidence](./layout-verify.md) | Build layout evidence from UI dumps and view inspection without relying on an unregistered batch verification tool | Public UI evidence flow |
| [Remote diagnosis](./remote-diagnosis.md) | Request SSH diagnostic information for remote build or device problems | `ssh-info` and the Agent escalation flow |
| [MCP for Agents](./mcp.md) | Let an Agent call Jugg capabilities inside the IDE plugin directly through an MCP client | Jugg MCP endpoint and registered actions |

For exact MCP tool names, arguments, and output fields, see [MCP tools](../../reference/mcp-tools.md) in the reference section.

## Related pages

- [CLI guide](../../guide/cli.md)
- [MCP guide](../../guide/mcp.md)
- [MCP and CLI internals](../../concepts/mcp-and-cli.md)
