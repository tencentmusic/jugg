---
title: Release compilation
description: Explains how Jugg preserves mapping consistency and compensates for inline and removed members in Release or minified builds.
status: active
tags:
  - capability
  - compile
  - release
  - minify
---

# Release compilation

Jugg supports incremental compilation in Release or minified scenarios. It uses `mapping.txt` and optional `usage.txt` from the latest build to keep incremental class/DEX output consistent with the obfuscation result in the installed APK. This page covers the support scope and risk boundaries. For mapping alignment, inline handling, and `_jugg_fix` compensation, see [Incremental Release compilation](../../concepts/incremental-compile/release-compile.md).

> [!WARNING]
> Release compilation is experimental and has not yet been validated across a large number of real-world projects. Changes may fail to take effect or cause runtime crashes. If you encounter a problem, provide a reproducible demo and submit an issue.

## Supported capabilities

| Scenario | Current support | User-visible result |
|---|---|---|
| Incremental obfuscated class/DEX | Supported | Incremental artifacts attempt to align with obfuscated names in the installed APK |
| Impact from methods inlined by R8 | Compensation supported | Affected old inline callers enter compensation decisions |
| Members removed by R8/ProGuard | Partial compensation supported | Produces compatibility artifacts for Release scenarios |
| Mapping is missing | Re-obfuscation is skipped | Uses the regular DEX path and cannot guarantee alignment with an obfuscated APK |

## Trigger and result

```text
Release / minified artifacts change
  -> Attempt to align them with the mapping baseline of the current APK
  -> Compensate for inline or removed-member impact
  -> Hand artifacts to deployment
```

Jugg remaps names only from the currently available mapping and does not rerun complete R8 processing to verify keep rules or optimization results. If the mapping, keep rules, or R8 behavior differs from the current APK, compilation may succeed while changes fail to take effect or cause a runtime crash.

## Boundaries

- When `mapping.txt` is missing, Jugg does not re-obfuscate output, which therefore cannot be deployed reliably to an obfuscated APK.
- `usage.txt` is used mainly for compatibility stubs for removed methods. Removed fields currently serve more often as impact-analysis signals.
- If Release incremental deployment causes `NoClassDefFoundError`, `NoSuchMethodError`, `IllegalAccessError`, annotation lookup failures, or similar errors, preserve the logs, provide a reproducible demo, and submit an issue.

## Related pages

- [Recompilation](./recompile-propagation.md)
- [AabResGuard](./aab-resguard.md)
- [Compilation failed](../../troubleshooting/compile-failed.md)
- [App crashes after deployment](../../troubleshooting/runtime-crash.md)
- [Incremental Release compilation](../../concepts/incremental-compile/release-compile.md)
