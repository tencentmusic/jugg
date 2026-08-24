---
title: Kotlin Compose
description: Explains Jugg's support and prerequisites for incremental Kotlin Compose source compilation.
status: active
tags:
  - capability
  - compile
  - kotlin
  - compose
---

# Kotlin Compose

Jugg supports incremental compilation of common Kotlin Compose sources. It detects Compose imports in Kotlin source and attempts to use the project's own Kotlin compiler classpath and Compose compiler plugin for the current compilation.

## Compose compilation support

| Scenario | Current support | Behavior |
|---|---|---|
| Change a Kotlin file that imports `androidx.compose.*` | Supported | Enables Compose compilation arguments and runs Kotlin compilation |
| Android Compose compiler plugin | Supported | Finds the `androidx.compose` plugin in Kotlin extensions or the plugin classpath |
| KMM / JetBrains Compose plugin | Supported | Finds the `org.jetbrains.compose` plugin in the Kotlin plugin classpath |
| Kotlin 2.x Compose compiler plugin | Recognized | Finds the `kotlin-compose-compiler` plugin |
| Compose plugin cannot be found | Continues in degraded mode | Prints a warning; compilation output may be incomplete |

## How Compose compilation takes effect

```text
Kotlin source changes
  -> Scan imports and find androidx.compose.*
  -> Select the project's Kotlin compiler classpath
  -> Find the Compose plugin in kotlinExtensions / kotlinPlugins
  -> Add Compose plugin arguments
  -> Kotlin compilation outputs classes
  -> Java / DEX / minification continue
```

Jugg prioritizes the project's Kotlin compiler. When the project compiler cannot be used, the Compose plugin may not be enabled correctly. In that case, the log prompts you to enable the project Kotlin compiler or fall back first.

## Boundaries

- Changes limited to Compose UI Kotlin source are generally suitable for incremental compilation.
- Changes to the Compose compiler plugin, Kotlin version, Gradle plugin, or compiler arguments trigger Gradle fallback and rebuild the compilation baseline.
- If a runtime failure appears only after Jugg incremental compilation of Compose code, use a Gradle build first to verify that the plugin baseline is consistent.

## Related pages

- [Source compilation](./source-compile.md)
- [Annotation processors](./annotation-processors.md)
- [Gradle fallback](./gradle-fallback.md)
