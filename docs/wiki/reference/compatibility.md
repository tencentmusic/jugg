---
title: Compatibility
description: Summarizes Jugg support for IDEs, AGP, Gradle, Kotlin, Android devices, and product capabilities.
status: active
tags:
  - reference
  - compatibility
---

# Compatibility

Use this page to quickly confirm whether your current development environment and target capability are within Jugg's supported range. See the corresponding capability page for specific trigger conditions, outcomes, and limitations.

## Environment compatibility

| Item | Supported range |
|---|---|
| Android Studio | Bumblebee through Quail |
| IntelliJ IDEA | 2021.1.3 through 2025.1 Beta |
| AGP | 3.4 through 9.1 |
| Gradle | 5.4.1 through 9.2.1 |
| Kotlin | 1.3 through 2.2 |
| target API | 21 through 36 |
| Android devices | Android 8 through 16 |

- The current Android Studio compatibility implementations cover Chipmunk through Quail. Bumblebee uses the implementation for older versions.
- Verified AGP versions include 3.4.2, 3.5.4, 4.1.3, 4.2.0, 7.2.2, 8.3.0, 8.7.0, 8.13.0, and 9.1.2.
- Gradle compatibility has been verified with 5.4.1, 6.8, 7.3.3, and 9.2.1. Gradle 8.x is also supported.
- Android 8 through 10 use compatibility deployment. Android 11 and later support standard incremental deployment.

> [!NOTE]
> The version ranges represent the compatibility boundaries covered by Jugg. Intermediate versions that are not listed as verified usually work directly. If you encounter a compatibility problem, submit a report through the plugin's Report issues action.

## Capability support

| Area | Supported capabilities |
|---|---|
| Compilation | [Source compilation](../capabilities/compile/source-compile.md), [recompilation](../capabilities/compile/recompile-propagation.md), [resource compilation](../capabilities/compile/resource-compile.md), [AndroidManifest compilation](../capabilities/compile/manifest.md), [so updates](../capabilities/compile/so-update.md), [DataBinding/ViewBinding](../capabilities/compile/databinding-viewbinding.md), [Kotlin Compose](../capabilities/compile/kotlin-compose.md), [KMP and Compose Multiplatform](../capabilities/compile/kmp-compose-multiplatform.md), [annotation processors](../capabilities/compile/annotation-processors.md), [custom compilers](../capabilities/compile/custom-compiler.md), [incremental compilation of dependencies](../capabilities/compile/dependency-incremental.md), [AabResGuard](../capabilities/compile/aab-resguard.md) |
| Deployment | [Multiple APKs](../capabilities/deploy/multi-apk.md), [multiple devices](../capabilities/deploy/multi-device.md) |
| Android Test | [Application Android Test](../capabilities/test/application-android-test.md), [Library Android Test](../capabilities/test/library-android-test.md), [Test Results UI](../capabilities/test/test-results-ui.md), [Logcat attribution](../capabilities/test/logcat-attribution.md) |
| Agents, CLI, and MCP | [Agent Skills](../capabilities/tools/agent-skills.md), [Jugg CLI](../capabilities/tools/cli.md), [build and deployment](../capabilities/tools/cli-build-deploy.md), [Android Test CLI](../capabilities/tools/cli-android-test.md), [runtime and devices](../capabilities/tools/cli-runtime-device.md), [UI automation](../capabilities/tools/ui-automation.md), [UI layout evidence](../capabilities/tools/layout-verify.md), [remote diagnostics](../capabilities/tools/remote-diagnosis.md), [MCP](../capabilities/tools/mcp.md) |

- [Release compilation](../capabilities/compile/release-compile.md) is experimental.
- Changes to Gradle scripts, dependencies, variants, source sets, or complex plugin configuration require a Gradle build.
- Android Test is disabled by default and prompts you to enable it the first time you use it.
- Agent, CLI, and MCP features must be [installed manually](../guide/cli) before use.

## Related pages

- [Jugg capabilities](../capabilities/)
- [Limits](./limits.md)
- [Android Studio version compatibility](../concepts/compatibility-layer.md)
