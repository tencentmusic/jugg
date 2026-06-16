---
title: Jugg Backend Project Configuration
description: Project-specific configuration that a Jugg backend can return to the plugin.
status: active
tags:
  - guide
  - backend
  - configuration
---

# Jugg Backend Project Configuration

Project configuration lets a team centralize Jugg defaults. During update checks, the plugin sends the current project name and can apply the returned `customConfigJson`.

## Supported Configuration

| Field | Purpose |
|---|---|
| `servers` | Backend server candidates for later failover |
| `buildFileRules` | Build-file patterns that should participate in change detection |
| `dontFilterIgnoredFileRules` | Rules that still need change detection even when files are ignored |
| `moduleCustomConfigs` | Module-specific classpath, sync path, and ignore-filter settings |
| `customCompilers` | Custom compiler jars for the project |
| `embeddedApksSearchRules` | Search rules for embedded APKs |

Prefer `buildFileRules` for new backends. `buildFileList` is a legacy field.

## Module Configuration

| Field | Meaning |
|---|---|
| `moduleStdPath` | Normalized module path |
| `customClasspath` | Paths to sync and add to classpath |
| `customSyncFilePath` | Paths to sync only |
| `isDoNotIgnored` | Keep the module in change detection even when it matches ignore rules |

Use module configuration only for modules that need extra generated outputs or sync rules.

## Custom Compiler Configuration

| Field | Meaning |
|---|---|
| `jarFileName` | Downloaded jar file name |
| `path` | Local path or HTTP download URL |
| `md5` | File checksum |

If the backend hosts the jar, point `path` to a download endpoint such as `/download_custom_compiler`.

## Related Pages

- [Self-hosting Checklist](./self-hosting.md)
- [Custom Compiler](../custom-compiler.md)
- [Configuration](../../reference/configuration.md)
