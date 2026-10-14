---
title: Supported packaging methods
description: "In-depth guide to Jugg's packaging compatibility: Debug incremental builds, Minify obfuscation, AabResGuard, Dynamic Features, and official Release boundaries."
status: active
tags:
  - troubleshooting
  - faq
  - packaging
  - apk
---

# Supported packaging methods

In large-scale Android engineering, teams commonly maintain diverse packaging pipelines—including fast local Debug builds, internal QA channel APKs, Minified Release variants, split APKs / App Bundles (AAB), and system platform-signed builds.

Jugg's primary objective is to **dramatically reduce the build time spent on full Gradle runs during everyday local development and debugging cycles**. It deploys code and resource changes to devices in seconds and can export incremental Debug APKs for offline verification; however, **it cannot and should not replace full Gradle builds for official app store publishing, Google Play releases, or official CI signing pipelines**.

## 1. Android packaging compatibility matrix

The following matrix summarizes Jugg's behavior and support status across standard Android packaging workflows and build toolchains:

| Packaging Method / Toolchain | Jugg Support Status | Execution Mechanism & User-Visible Result | Dedicated Guide |
|---|---|---|---|
| **Standard Debug APK** (`assembleDebug`) | **Fully Supported** (Core) | Bypasses Gradle after baseline is established; code and resource changes take effect in 1–3 seconds | [Run the app](../guide/run.md) |
| **Export Incremental APK** | **Fully Supported** | Directly overwrites compiled incremental classes and resources into the APK container for offline QA | [Export an incremental APK](../guide/export-incremental-apk.md) |
| **Release Minified Build** (R8 / ProGuard) | **Supported for Debug** (Not for Store) | Incrementally compiles on an existing Release build via `_jugg_fix` and obfuscation mapping for rapid bug reproduction | [Release compilation](../concepts/incremental-compile/release-compile.md) |
| **Resource Obfuscation** (AabResGuard / AndResGuard) | **Fully Supported** | Inherits and reuses original resource IDs and obfuscated symbol dictionaries during incremental packaging | [Resource incremental compilation](../concepts/incremental-compile/resource.md) |
| **Dynamic Feature Modules** (App Bundle / AAB) | **Fully Supported** | Automatically detects split APKs and performs targeted incremental push to connected devices | [Capabilities overview](../capabilities/index.md) |
| **System / Platform-Signed Apps** (`priv-app`) | **Supported** (Via Scripts) | Supports custom APK install scripts for remount/push and custom APK sign scripts for platform certificates | [Compatibility deployment](../guide/compat-device.md) |
| **CI / CD Pipeline Incremental Builds** | **Supported** | Headless CLI mode (`cmd_line` two-step build) produces incremental debug APKs without opening the IDE | See details below |
| **Official Store Release APK / AAB** | ❌ **Not Supported** | Must use native Gradle / Android Studio full build and official signing pipeline | - |

## 2. Why can't Jugg produce official Release packages for app stores?

Developers frequently ask: *“Since Jugg compiles so quickly, can we use it to build our production release APKs?”*

The answer is unambiguously: **No**. The key architectural reasons include:

1. **Build Integrity and Dead-Code Elimination (R8/ProGuard Tree Shaking)**: Jugg compiles only modified files and their direct dependencies. Production release packages require R8/ProGuard to perform whole-program dead-code elimination, aggressive method inlining, and global optimizations.
2. **Signature Contracts and Security Verification**: Modern app stores (Google Play, OEM stores) strictly enforce V2/V3 signing blocks, zip alignment, and package integrity. Incrementally patched APK containers are designed for speed in development, not for tamper-resistant production signing.
3. **Deterministic Builds**: Production releases demand byte-level reproducible builds from a clean checkout. Multi-iteration incremental patching does not satisfy release-grade determinism.

> [!IMPORTANT]
> Production distribution and channel packaging must continue using standard Gradle `assembleRelease` or `bundleRelease`. Jugg never modifies your Gradle scripts; switching back to the native App Run Configuration instantly restores Android Studio's original build flow.

## 3. CI/CD pipeline: Generating incremental Debug APKs

For automated pipelines that need to produce quick incremental testing APKs on remote build machines, use the standalone `cmd_line` toolchain in two steps:

```text
Step 1: cmd=buildGradleBase
  -> Run full Gradle build, saving initial base APK, classpath, and Jugg baseline

Step 2: cmd=buildIncrementalApk
  -> Restore compilation state from the baseline directory
  -> Incrementally compile the changedFiles explicitly supplied by the CI pipeline
  -> Overwrite incremental classes and resources back into destination APK
```

> [!NOTE]
> The CI pipeline must provide the `changedFiles` diff explicitly. Each baseline directory can only be consumed once; copy independent baseline directories if building multiple concurrent branches.

---

## Next steps

- 📖 **[Export an Incremental APK Guide](../guide/export-incremental-apk.md)**: Export testing packages directly from Android Studio
- ⚙️ **[Release Incremental Compilation Internals](../concepts/incremental-compile/release-compile.md)**: Efficiently debug release-specific obfuscation bugs
- 🛡️ **[Jugg Limitations and Fallback Rules](../reference/limits.md)**: Review boundary conditions that trigger Gradle fallback
