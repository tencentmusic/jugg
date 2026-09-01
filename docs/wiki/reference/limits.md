---
title: Limits
description: Summarizes the boundaries of Jugg incremental compilation, deployment, Debug, androidTest, and tooling.
status: active
tags:
  - reference
  - limits
---

# Limits

Jugg aims to reduce the number of full Gradle builds during common development cycles, not to replace every capability of the Android Gradle Plugin, Gradle, or Android Studio. This page summarizes the overall boundaries across compilation, deployment, Debug, androidTest, and tooling. See the corresponding capability page to determine whether an individual operation is supported and what outcome it triggers.

> [!IMPORTANT]
> When Jugg behavior differs from the Gradle build result, treat the native Gradle / Android Studio build result as authoritative. A full Gradle build is recommended to re-establish the baseline.

## Overall boundaries

| Area | Jugg prioritizes | Scenarios that require fallback or native verification |
|---|---|---|
| Source changes | Small Java/Kotlin changes | Large cross-module changes and complex compiler plugin behavior |
| Resource changes | Common `res/`, `assets/`, and Manifest changes | Changes to source sets, variants, or complex resource generation logic |
| Deployment | Common development flows such as install, code swap, and full swap | Invalid device state or major APK structure changes |
| release | Preserving obfuscation mapping consistency when possible | Complex R8 optimization, incomplete mapping, or runtime inconsistencies |
| androidTest | Common app androidTest runs | Complex Test APK ownership or test target switching |
| MCP / CLI tools | Assistance with compilation, deployment, logs, and UI verification | They do not replace human judgment or verification on a real device |

## Compilation limits

The following changes are more likely to trigger a Gradle fallback, or warrant an explicit Gradle build. See [Gradle fallback and baseline reconstruction](../concepts/gradle-fallback-baseline.md) for a deeper explanation.

- Changes to Gradle scripts, plugin configuration, version catalogs, or dependency declarations.
- Switching the build variant, build target, run configuration, or androidTest target.
- Changing many Java/Kotlin files or multiple modules at once.
- Configuration changes that affect annotation processing, KSP/KAPT, bytecode instrumentation, or code generation.
- Changes to resource source sets, the sources of Manifest placeholders, or resource generation plugins.
- Runtime exceptions related to obfuscation, reflection, annotations, or type references in release builds.

> [!WARNING]
> When a change depends on side effects of a Gradle task, such as generated source, copied resources, a rewritten Manifest, or a modified classpath, run a Gradle build to refresh the baseline. Jugg does not promise to reproduce arbitrary Gradle task behavior.

## Deployment limits

Jugg deployment depends on consistency between the current device, the installed APK, and local deployment history. The following scenarios may require reinstallation or a full build:

- The device disconnects, restarts, or its state becomes unavailable.
- The user manually uninstalls the app or another installation source replaces it.
- The APK structure changes, including split APK, dynamic feature, or ABI output changes.
- Manifest, resource table, or DEX state is inconsistent with local deployment history.
- Local `build/jugg/database/` state is damaged or missing.

## Resource and Manifest limits

Incremental resource processing depends on the installed APK or the resource table from the most recent deployment.

A Gradle fallback is recommended in the following scenarios:

- The resource directory structure or source set rules change.
- The source of a Manifest placeholder or complex merging rules change.
- Resource dependencies between a dynamic feature and the base APK change.
- Resource obfuscation, resource IDs, or `R.styleable` behave unexpectedly at runtime.

## Debug limits

Jugg Debug connects to the Android Studio Java Debugger after Jugg compilation and deployment succeed. It remains subject to Android Studio, device, and app debuggable state.

Common boundaries:

- Breakpoints do not work when the app is not debuggable.
- If Java debugger attach fails, check Android Studio `idea.log` and the Jugg logs.
- If native Android Studio Debug can hit a breakpoint but Jugg Debug cannot, investigate the attach stage first.

## androidTest limits

Jugg supports common app androidTest runs, but the following cases may require Gradle or manual confirmation:

- Switching from a regular app Run to androidTest, or switching back.
- A library Test APK must be regenerated or its history is unavailable.
- Complex changes to the test target, filters, or instrumentation parameters.
- The app APK and Test APK used by the test do not share the same build baseline.

## MCP / CLI tool limits

MCP and CLI are supporting features, not a complete testing framework or device automation platform.

Keep the following in mind:

- Tool output depends on the current Jugg runtime and log state.
- UI inspection depends on the actual page on the device and the available ViewHierarchy information.
- Log waiting, crash detection, and layout verification can be affected by device performance, page timing, and app implementation.
- Tool results should be one part of the evidence chain, not the sole conclusion.

## When to run Gradle proactively

A direct Gradle build is recommended in the following situations:

- You just switched branches or pulled a large amount of code.
- You upgraded AGP, Kotlin, Gradle, or an important build plugin.
- You changed build scripts, dependencies, source sets, or variant-related configuration.
- A release build produces a runtime exception.
- Dynamic feature, multi-apk, or resource obfuscation behavior is unstable.
- You need to determine whether a problem was introduced by the Jugg incremental path.

## Related pages

- [How Jugg works](../concepts/how-jugg-works.md)
- [Incremental compilation](../concepts/incremental-compile/)
- [Resource compilation](../capabilities/compile/resource-compile.md)
- [Compilation failed](../troubleshooting/compile-failed.md)
- [Changes did not take effect](../troubleshooting/changes-not-applied.md)
