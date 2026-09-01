---
title: Jugg CLI
description: Explains how Agents and terminal users access plugin capabilities through Jugg CLI.
status: active
tags:
  - capability
  - tools
  - cli
---

# Jugg CLI

Jugg CLI is the command-line entry point through which Agents and terminal users access Jugg plugin capabilities. It wraps MCP tools in stable subcommands and handles project resolution, port discovery, asynchronous compilation polling, and human- or script-oriented output formats.

This page explains which tasks the CLI supports and when it is appropriate. For exact commands, arguments, and aliases, see [CLI command reference](../../reference/cli-commands.md).

## Supported tasks

| User task | Current support | Input and output boundary |
|---|---|---|
| Compile, deploy, reinstall, or restart | Supported | Uses `compile`, `deploy`, `gradle-build`, `clean-reinstall`, and `restart` |
| Run androidTest | Supported | Uses `instrument`; `--source-path` is required |
| Inspect runtime status and device information | Supported | Uses `status`, `devices`, `activity-stack`, and `wait-logs` |
| Inspect and interact with the UI | Supported | Uses `layout-dump`, `view-locate`, `view-inspect`, and `tap` |
| Request remote diagnostic information | Supported | Uses `ssh-info` and requires explicit user consent |

## Invocation

```text
python3 {SKILL_DIR}/scripts/jugg.py [global arguments] <subcommand> [subcommand arguments]
python3 {SKILL_DIR}/scripts/jugg.py help <subcommand>
```

> [!TIP]
> Prefer `plain` or `json` for Agent use. `rich` refreshes terminal lines and is unsuitable as stable model context.

## How the CLI finds the plugin

The CLI reads the port cache first. If it misses, it scans the Jugg MCP port range `12320..12329`. By default, the project directory uses longest-prefix matching between the current working directory and projects initialized in the IDE. When `--project-dir` is supplied, the CLI uses that path directly.

```text
CLI
  -> Discover a local MCP port
  -> Resolve projectDir
  -> Call the corresponding MCP tool
  -> Poll get-compile-status when needed
  -> Output plain / rich / json results
```

## Subcommand groups

- **[Build and deployment](./cli-build-deploy.md)**: `compile`, `deploy`, `gradle-build`, `clean-reinstall`, `restart`
- **[Android Test](./cli-android-test.md)**: `instrument`
- **[Runtime and devices](./cli-runtime-device.md)**: `status`, `devices`, `activity-stack`, `wait-logs`
- **[UI automation](./ui-automation.md)**: `layout-dump`, `view-locate`, `view-inspect`, `tap`
- **[Remote diagnosis](./remote-diagnosis.md)**: `ssh-info`

For exact arguments and output fields, see [CLI command reference](../../reference/cli-commands.md) and [MCP tool reference](../../reference/mcp-tools.md).

## Related pages

- [CLI guide](../../guide/cli.md)
- [MCP and CLI internals](../../concepts/mcp-and-cli.md)
