---
title: CLI commands
description: Summarizes the global options, public subcommands, and commonly used options of the jugg CLI.
status: active
tags:
  - reference
  - cli
---

# CLI commands

This page is a quick reference for `jugg` CLI commands and options. It does not explain when to use the CLI. See [Jugg CLI](../capabilities/tools/cli.md) for its scope and usage recommendations, and [CLI guide](../guide/cli.md) for step-by-step instructions.

## Command syntax

```bash
jugg [--console=plain|rich|json] [--project-dir <path>] [--if-compiling wait|interrupt] <subcommand> [options]
jugg help <subcommand>
```

## Global options

| Option | Description |
|---|---|
| `--console=plain` | Plain-text output without a spinner. This is the default when the Python script is run directly. |
| `--console=rich` | Spinner output for interactive terminals. This is the default for the shell wrapper. |
| `--console=json` | Outputs MCP `structuredContent` JSON for scripts and Agents. |
| `--project-dir <path>` | Specifies the MCP `projectDir` directly and skips automatic matching against the current directory. |
| `--if-compiling wait` | Waits for an existing compilation to finish before triggering a compile-related command. This is the default. |
| `--if-compiling interrupt` | Triggers a new task without waiting for the old task and uses the server-side interruption semantics. |

Camel-case global options such as `--projectDir` and `--ifCompiling` are normalized to kebab-case.

## Public subcommands

| Subcommand | Purpose |
|---|---|
| `version` | Displays the CLI version and plugin version. |
| `compile` | Runs Jugg compilation without deployment. |
| `deploy` | Compiles and deploys. |
| `gradle-build` | Forces a Gradle build, followed by the installation and launch flow. |
| `clean-reinstall` | Clears app data and reinstalls the APK. |
| `restart` | Restarts the target app. |
| `instrument` | Runs tests from an androidTest source file anchor. |
| `status` | Shows device, fallback, uncompiled file, and androidTest baseline status. |
| `layout-dump` | Exports the UI hierarchy as HTML. |
| `view-locate` | Finds an element by text, resource id, or content-desc. |
| `view-inspect` | Reads read-only View properties through reflection, including getters, Kotlin properties, and public fields. |
| `tap` | Performs a tap, long-press, or swipe. |
| `devices` | Lists connected devices. |
| `activity-stack` | Shows the Activity stack. |
| `ssh-info` | Requests remote SSH troubleshooting information. |
| `wait-logs` | Waits for an app log marker, crash, or timeout. |

## Compilation and deployment

```bash
jugg compile
jugg deploy --always-restart-app false
jugg gradle-build
jugg clean-reinstall
jugg restart
```

| Command | Common options | Description |
|---|---|---|
| `compile` | None | Compiles without deployment. |
| `deploy` | `--always-restart-app <true|false>` | `false` allows HOT RELOAD when the requirements are met. |
| `gradle-build` | None | Forces a Gradle build and outputs a log summary if it fails. |
| `clean-reinstall` | None | Recovers from inconsistencies between local history and the installed state on the device. |
| `restart` | None | Restarts the app only. |

> [!IMPORTANT]
> To determine the final state of `deploy` or `gradle-build`, check both `isCompileSuccess` and `isDeploySuccess`. A successful compilation does not mean that deployment succeeded.

## Android Test

```bash
jugg instrument --source-path app/src/androidTest/java/example/FooTest.kt
jugg instrument --source-path app/src/androidTest/java/example/FooTest.kt --class example.FooTest --method testLogin
```

| Option | Description |
|---|---|
| `--source-path` / `--sourcePath` | Required. Used to resolve the module and Test APK. |
| `--class` | Test class. May be omitted for a file that contains a single class. |
| `--method` | Test method. The class must already be uniquely identified. |
| `--runner` | Instrumentation runner override. |
| `--extras` | Semicolon-separated `k=v;k2=v2` arguments. |

Legacy entry points and aliases such as `--package`, `--testsRegex`, `--regex`, `--clazz`, `--instrumentationRunner`, and `-e` are not supported.

## Status and devices

```bash
jugg status --refresh-changes true
jugg devices
jugg activity-stack
```

| Command | Common options | Description |
|---|---|---|
| `status` | `--refresh-changes <true|false>` | Whether to refresh git-tracked changed files first. |
| `devices` | None | Returns the device list and marks the selected device. |
| `activity-stack` | None | Returns the top Activity and Activity stack. |

## UI tools

```bash
jugg layout-dump --include-gone --all-windows
jugg view-locate --resource-id login_button
jugg view-inspect --text 登录 getText() isEnabled()
jugg view-inspect --resource-id bubble_container layoutParams.leftMargin getLayoutParams().getMarginStart()
jugg tap --resource-id login_button
jugg tap --x-percent 50 --y-percent 80
jugg tap --action swipe --x 500 --y 1600 --end-x 500 --end-y 300 --duration 300
```

| Command | Common options |
|---|---|
| `layout-dump` | `--root-layout`, `--include-gone`, `--all-windows` |
| `view-locate` | `--text`, `--resource-id`, `--content-desc` |
| `view-inspect` | `--text`, `--resource-id`, `--content-desc`, `--class-name`, read-only expressions |
| `tap` | `--action`, coordinate options, percentage options, element selector, `--duration` |

The `tap` mode priority is coordinate > percent > element. `swipe` supports only coordinate or percentage mode, not element mode.

## Logs and remote troubleshooting

```bash
jugg wait-logs --marker "LoginSuccess" --tags Activity,Repository --timeout-ms 30000
jugg ssh-info --reason "Need to inspect remote Gradle build output"
```

| Command | Options | Description |
|---|---|---|
| `wait-logs` | `--marker`, `--tags`, `--timeout-ms` | Waits for a log marker, crash, or timeout. |
| `ssh-info` | `--reason` | Remote troubleshooting entry point that requires explicit user consent. |

## Related pages

- [MCP tools](./mcp-tools.md)
- [Log files](./log-files.md)
- [CLI guide](../guide/cli.md)
