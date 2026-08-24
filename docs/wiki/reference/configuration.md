---
title: Configuration
description: Summarizes the meaning of Jugg run configuration, global settings, project directories, and remote configuration.
status: active
tags:
  - reference
  - configuration
---

# Configuration

This page is a quick reference for configuration meanings and storage locations. It covers Android Studio Run Configuration, IDE-wide settings, one-time CLI/MCP parameters, project-level directories, and user-level directories. It does not explain where to click each button. See [Advanced options](../guide/advanced-options.md) for infrequently used actions under More Options.

Users normally need to change configuration only through the Jugg UI or CLI parameters. Manually editing cache files is not recommended.

## Run Configuration options

| Option | Meaning |
|---|---|
| Always restart app after deployment | Forces an app restart after deployment. When disabled, HOT RELOAD is allowed if the requirements are met. |
| Confirm fallback when no file changes | Requests confirmation before falling back to Gradle when no file changes are detected. |
| Auto fallback to Gradle when deploy error | Automatically attempts a Gradle fallback after a deployment error. |
| Embedded to APK | Runtime option that embeds specific artifacts into the APK. |
| Android Test / enableAndroidTest | Initializes the current baseline for an androidTest target. |

UI labels may vary slightly between Android Studio versions, but the current Run Configuration display defines their meaning.

## Global behavior switches

| Setting | Default | Description |
|---|---|---|
| Compile on save | Off | Automatically triggers compilation after a save. |
| Deploy on save | Off | Automatically deploys after a save. |
| Check checksum when file changes | On | Verifies checksums when files change to reduce unnecessary incremental work. |
| Compatible deployment mode | On | Enables a compatibility deployment strategy for HarmonyOS, HyperOS, low-API devices, and similar environments. |
| Direct overlay deploy | On | Enables the fast overlay deployment path that does not require the app process to be running. |
| Use project Kotlin compiler | On | Prefers the project's Kotlin compiler. |
| Backup classpath | Off by default | Saves a classpath backup. Not available on Windows. |
| Ignore wont compile modules | Off | Ignores modules that will not participate in compilation. |
| Const-ref tasks | On | Enables constant-reference scanning, analysis, and impact queries. |

These settings are persisted in the IDE properties with keys that use the `jugg.*` prefix. Directly editing internal IDE properties is risky; use the Jugg UI instead.

## CLI / MCP parameters

CLI and MCP call parameters affect only the current call. They do not permanently change IDE configuration.

| Parameter | Entry point | Description |
|---|---|---|
| `projectDir` | MCP / CLI | Absolute path to the target project. |
| `--project-dir` | CLI | Overrides automatic project matching. |
| `alwaysRestartApp` / `--always-restart-app` | `deploy` | Controls whether the current deployment forces an app restart. |
| `waitAppReadyAfterSuccess` | MCP | Whether to wait for the app to become ready after successful compilation or deployment. The CLI does not currently expose this parameter. |
| `refreshChanges` / `--refresh-changes` | `status` | Whether to refresh git-tracked changed files before querying status. |
| `--if-compiling` | CLI | Waits or interrupts when a compilation is already running. |

## Project-level directories

All paths are relative to the project root by default.

| Path | Purpose |
|---|---|
| `build/jugg/log/` | Main Jugg logs. |
| `build/jugg/build/staging/` | Staging output for the current incremental compilation. |
| `build/jugg/database/project_infos.db/` | IDE / Gradle project information snapshots. |
| `build/jugg/database/compile_context.db/` | Classpath, module information, and full build information. |
| `build/jugg/database/deploy_history.db/` | Deployment history and recovery information. |
| `build/jugg/classpath/` | Classpath, APK, library backup, and embedded APK caches. |
| `build/jugg/config/custom_compilers/` | Custom compiler configuration directory. |
| `build/jugg/mcp_fetch/` | Artifact cache for MCP tools. |
| `.gradle/jugg/readProjectInfo.gradle.kts` | Script injected by Jugg for Gradle to read the project model. |
| `.gradle/jugg/jugg-runtime.jar` | Gradle-side runtime JAR. |

## User-level directories

| Path | Purpose |
|---|---|
| `~/.jugg/const_ref/` | Cross-project constant-reference cache. |
| `~/.jugg/library_test_build_records/` | Library androidTest build history. |
| `~/.cache/jugg/port` | CLI MCP port cache. |
| `~/.cache/jugg/` | CLI cache root, which can be overridden with `JUGG_CACHE_DIR`. |

## Remote configuration

Jugg remote configuration includes the server URL, expiration time, remote compilation diff directory, and SSH troubleshooting information. Remote compilation output and diffs are stored by default in:

```text
build/jugg/tmp/diff/
```

If remote compilation results and local state are inconsistent, first check the full log described in [Log files](./log-files.md) and the artifacts in `tmp/diff`.

## Related pages

- [CLI commands](./cli-commands.md)
- [MCP tools](./mcp-tools.md)
- [Log files](./log-files.md)
- [Limits](./limits.md)
