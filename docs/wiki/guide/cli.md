---
title: CLI
description: Install and use the Jugg CLI, choose an output mode, run common commands, integrate with agents, and troubleshoot failures.
status: active
tags:
  - guide
  - cli
---

# CLI

The Jugg CLI lets terminals, scripts, and agents call features provided by the Jugg plugin. It works through the Jugg plugin service in an open Android Studio project, so open the target project and finish Jugg initialization first.

## Installation

Installing from the IDE is recommended:

1. Press Shift twice to open Search Everywhere.
2. Search for `Install Jugg Skills`.
3. Select the agents, CLI, and hooks to install in the dialog.
4. Click Install.

You can also open More Options in the Jugg panel, then select Tools -> Install Jugg Skills.

Typical installation options include:

| Option | Purpose |
|---|---|
| Currently installed agents | Install the `jugg-android-dev-loop` skill for the corresponding agents |
| Install CLI to `$PATH` | Install the `jugg` command for direct use by people |
| Install agent hooks | Remind an agent to use Jugg when it changes Android source code without verification |

## Output modes

The CLI supports three output modes:

```bash
jugg --console=rich status
jugg --console=plain status
jugg --console=json status
```

| Mode | Intended user | Characteristics |
|---|---|---|
| `rich` | Human-operated terminal | Includes spinners and interactive output |
| `plain` | Agent | Stable output without spinners polluting context |
| `json` | Script | Keeps stdout as structured JSON |

> [!IMPORTANT]
> Use `--console=plain` or `--console=json` for agents. Do not make an agent consume rich spinner output directly.

## Common commands

```bash
jugg help
jugg help deploy
jugg status
jugg compile
jugg deploy --always-restart-app false
jugg gradle-build
jugg clean-reinstall
jugg restart
```

Command groups:

| Category | Commands | Purpose |
|---|---|---|
| Version | `version` | Show CLI and plugin versions |
| Build and deployment | `compile` | Compile without deploying |
| Build and deployment | `deploy` | Compile and deploy |
| Build and deployment | `gradle-build` | Force a Gradle build |
| Build and deployment | `clean-reinstall` | Clear app data and reinstall |
| Testing | `instrument` | Compile, deploy, and run androidTest |
| Runtime | `restart`, `wait-logs`, `activity-stack` | Restart, wait for logs, and inspect the Activity stack |
| UI | `layout-dump`, `view-locate`, `view-inspect`, `tap` | Export layouts, locate elements, read properties, and perform touch actions |
| Diagnostics | `status`, `devices`, `ssh-info` | Inspect status and devices, and request remote SSH information |

## project-dir

When invoked inside a project directory, the CLI matches the current path to an open Jugg project. When invoked elsewhere, pass the project path explicitly:

```bash
jugg --project-dir /path/to/project status
```

Flags support both kebab-case and camelCase, so `--project-dir` and `--projectDir` are equivalent.

## Waiting behavior for compilation commands

`compile`, `deploy`, `gradle-build`, and `instrument` can take a long time. The CLI polls automatically until the task reaches a terminal state.

If a compilation task is already running, choose how to handle it:

```bash
jugg --if-compiling wait compile
jugg --if-compiling interrupt compile
```

| Strategy | Behavior |
|---|---|
| `wait` | Default; wait for the existing task to finish |
| `interrupt` | Start a new task immediately and let the server interrupt the old task |

## Recommendations for agents

- By default, have agents run `jugg compile` for compilation verification without deploying to a device automatically.
- When device verification is required, explicitly ask the agent to use `jugg deploy`, `jugg instrument`, or UI tools.
- For Android source changes, hooks can remind the agent to load the Jugg skill and run verification.
- Use `--console=json` when the result needs to be parsed.
- When determining whether `deploy` succeeded, check both compilation and deployment results instead of compilation success alone.

## Common problems

| Symptom | Action |
|---|---|
| CLI cannot find the project | Confirm that Android Studio has the target project open, or pass `--project-dir` |
| Port connection fails | Confirm that the Jugg plugin is initialized; multiple IDE instances increment through the port range automatically |
| A command waits indefinitely | Use `jugg status` to inspect `isCompiling`; use `--if-compiling interrupt` if necessary |
| `instrument` reports that AndroidTest is disabled | Enable Android Test in the Jugg Run Configuration and establish a full-build baseline first |
| Output is noisy | Use `--console=plain` or `--console=json` for agents |

## Related pages

- [Jugg CLI](../capabilities/tools/cli.md)
- [CLI commands](../reference/cli-commands.md)
- [Agent Skills](../capabilities/tools/agent-skills.md)
- [MCP and CLI](../concepts/mcp-and-cli.md)
- [Agent or CLI execution failed](../troubleshooting/agent-command-failed.md)
