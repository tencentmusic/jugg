---
title: Changes did not take effect
description: Resolve undetected file changes, stale code or resources, dependency updates that did not take effect, and initialization logic that did not run again.
status: active
tags:
  - troubleshooting
  - runtime
  - changes
---

# Changes did not take effect

If Jugg reports a successful run but the UI or code still behaves as before, first determine whether the change was not detected, was deployed but requires a restart, or must be updated by a full Gradle build.

## Q: What should I do when Jugg reports `no file changes` after I modified a file?

This usually means that the IDE has not yet notified Jugg of the file change, or Jugg's project structure has not been refreshed.

1. Cancel the current run and run again.
2. If the message still appears, run Gradle Sync once, then run again.
3. If the change still does not take effect, run [Fallback to Gradle compilation](../guide/downgrade-gradle.md) once.
4. If the problem persists, [report the issue](../guide/report-issue.md).

## Q: Compilation and deployment succeed, but the code still uses the old logic

After you modify startup logic, `static` declarations, `companion object` declarations, or Kotlin top-level declarations, the initialization logic for values already initialized in the existing process will not run again if the current deployment uses Hot Reload.

1. Use [Restart the app](../guide/restart-app.md) to restart the target process.
2. If the change still does not take effect after a restart, run [Fallback to Gradle compilation](../guide/downgrade-gradle.md) once for comparison.

## Q: What should I do when style changes do not take effect?

Run [Fallback to Gradle compilation](../guide/downgrade-gradle.md) once to refresh the result with a full Gradle build.

## Generated code was not updated after an annotation change

Jugg runs incremental generation only for annotation processors with a dedicated adapter. An APT, KAPT, or KSP processor outside the supported list does not run automatically during Jugg incremental compilation, so its generated source may remain stale.

1. Check [Annotation processors](../capabilities/compile/annotation-processors.md) to confirm whether the processor is supported.
2. If it is not supported, run [Fallback to Gradle compilation](../guide/downgrade-gradle.md) once to generate the complete result.
3. If Gradle works but a supported processor still reproducibly remains stale with Jugg, [report the issue](../guide/report-issue.md).

## Related pages

- [Restart the app](../guide/restart-app.md)
- [Fallback to Gradle compilation](../guide/downgrade-gradle.md)
- [DataBinding/ViewBinding](../capabilities/compile/databinding-viewbinding.md)
- [Resource compilation](../capabilities/compile/resource-compile.md)
- [Annotation processors](../capabilities/compile/annotation-processors.md)
