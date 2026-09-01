---
title: Jugg backend project configuration distribution
description: Distribute project-specific Jugg configuration from a backend and choose configuration suitable for centralized maintenance.
status: active
tags:
  - guide
  - backend
  - configuration
---

# Jugg backend project configuration distribution

Project configuration distribution keeps team defaults in one backend. During an update check, the plugin sends the current project name. The backend can return that project's `customConfigJson`, which the plugin then applies to the local project.

## What to distribute

| Configuration | Purpose |
|---|---|
| `servers` | Provide a list of available backend addresses so the plugin can switch servers later |
| `buildFileRules` | Mark build-file rules that should participate in change detection |
| `dontFilterIgnoredFileRules` | Continue change detection for specified rules in ignored files |
| `moduleCustomConfigs` | Add classpaths, synchronization paths, or ignore-filtering behavior for specified modules |
| `customCompilers` | Distribute custom compiler JARs to a project |
| `embeddedApksSearchRules` | Configure search rules for embedded APKs |

The legacy `buildFileList` field is not recommended for new use. New backends should maintain `buildFileRules` instead.

## Module configuration

`moduleCustomConfigs` is intended for cases where only certain modules require additional rules:

| Field | Description |
|---|---|
| `moduleStdPath` | Normalized module path |
| `customClasspath` | Path to synchronize and add to the classpath |
| `customSyncFilePath` | Path that only needs synchronization |
| `isDoNotIgnored` | Keep the module in changed modules even when it matches an ignore rule |

Use this configuration only for modules that genuinely need additional artifacts or synchronization rules. Avoid placing every module in backend configuration.

## Custom compiler configuration

The backend can return `customCompilers` in project configuration so the plugin downloads the team's custom compiler:

| Field | Description |
|---|---|
| `jarFileName` | Downloaded JAR filename |
| `path` | Local path or HTTP download URL |
| `md5` | File verification value |

When the self-hosted backend hosts custom compilers, it usually also implements `/download_custom_compiler` and points `path` to that download interface.

## Configuration maintenance

- Maintain configuration by project name instead of mixing every team's configuration in one response.
- Return empty arrays or `null` by default, and distribute configuration only to projects that need special handling.
- After configuration changes, ask users to check for updates again or restart the IDE so the configuration is applied to the current project.
- Do not include passwords, private keys, or other sensitive information in project configuration responses.
- Version custom compiler JARs and verify their md5 values to support rollback.

## Related pages

- [Self-hosting checklist](./self-hosting.md)
- [Custom compiler](../custom-compiler.md)
- [Configuration](../../reference/configuration.md)
