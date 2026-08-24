---
title: Compilation failed
description: Resolve Jugg Java, Kotlin, resource, Manifest, annotation-generated source code, and release compilation failures.
status: active
tags:
  - troubleshooting
  - compile
---

# Compilation failed

When compilation fails, first fix the first explicit source or resource error in the Run window. Treat the failure as an incremental compilation difference only when Gradle succeeds but Jugg still fails.

## Q: Java or Kotlin reports a missing symbol

Common messages include `unresolved reference` and `cannot find symbol`.

1. Run Gradle Sync once.
2. If compilation still fails, run a full Gradle build to re-establish the classpath and generated source baseline.
3. If Gradle fails, the problem is usually not caused by Jugg.
4. If Gradle succeeds but the Jugg failure remains reproducible, [report the issue](../guide/report-issue.md) to the maintainers.

## Q: Annotation-related or generated source files fail to compile

Jugg supports Compose, `@Parcelize`, ViewBinding, and DataBinding. For other annotation processors, see [Annotation processor support](../capabilities/compile/annotation-processors.md). For annotation processors that are not yet supported:

* Incremental compilation can continue when a change does not affect existing generated code.
* Generated code is not updated when you add or modify an annotation, or change annotation arguments. Run [Fallback to Gradle compilation](../guide/downgrade-gradle.md) once.


## Q: Gradle succeeds, but Jugg still fails

1. First run Android Studio Sync once and check whether the problem is resolved.
2. If the problem remains reproducible, [report the issue](../guide/report-issue.md) to the maintainers.

## Related pages

- [Changes did not take effect](./changes-not-applied.md)
- [Compilation stages](../guide/compile.md)
- [Fallback to Gradle compilation](../guide/downgrade-gradle.md)
- [Gradle fallback](../capabilities/compile/gradle-fallback.md)
