---
title: Custom compilers
description: Explains the Jugg custom compiler SPI, stage insertion points, and extension capabilities available to users.
status: active
tags:
  - capability
  - compile
  - custom
---

# Custom compilers

Jugg supports extending its built-in incremental compilation flow through a custom compiler SPI. Custom compilers can insert logic before or after asset, resource, source, minify, DEX, and other stages to handle project-specific generation, transformation, or validation. This page describes integration capabilities and common insertion points. For loading and stage boundaries, see [Custom compiler internals](../../concepts/incremental-compile/custom-compiler.md).

## Supported integration methods

| Scenario | Current support | User-visible result |
|---|---|---|
| Local custom compiler JAR | Supported | Local extension logic enters the Jugg compilation flow |
| Remote custom compiler JAR | Supported | The remote extension participates in compilation after download and validation |
| Pre-stage processing | Supported | Can consume or rewrite input before a built-in stage |
| Post-stage processing | Supported | Can process artifacts after a built-in stage |
| Extension execution fails | Supported | If extension compilation logic throws an exception, the current task fails with a visible message |

## Integration

A custom compiler is provided as a JAR. The extension implements `ICompilerCreator`, which creates an `ICompiler` for the current compilation context. Jugg loads implementations from the JAR through SPI.

The project backend only needs to declare the JAR filename, path, and MD5 in the `customCompilers` configuration. The path can be an absolute local path, a path relative to the project directory, or an HTTP/HTTPS address.

> [!NOTE]
> Custom compilers run inside the Jugg compilation flow. They are suitable for extending the incremental flow, not for replacing the complete Gradle task graph.

## Trigger and result

```text
Custom compiler configuration is available
  -> Load and validate the extension JAR
  -> Insert it into the configured compilation stage
  -> Participate in the current incremental compilation
  -> Pass successful artifacts to later stages
```

Choose an insertion point based on the user goal, not on when the JAR is loaded.

## Common insertion points

| Goal | Recommended range |
|---|---|
| Process the current input and artifacts first or last | `atFirst` / `atLast` |
| Process assets and native libraries before or after their stage | `beforeAsset` / `afterAsset` |
| Process resources before or after their stage | `beforeRes` / `afterRes` |
| Process Java/Kotlin before or after their stage | `beforeSource` / `afterSource` |
| Process minification before or after its stage | `beforeMinify` / `afterMinify` |
| Process DEX before or after its stage | `beforeDex` / `afterDex` |

## Related pages

- [Custom compiler guide](../../guide/custom-compiler.md)
- [Jugg backend project configuration](../../guide/jugg-backend/project-config.md)
- [Source compilation](./source-compile.md)
- [Custom compiler internals](../../concepts/incremental-compile/custom-compiler.md)
