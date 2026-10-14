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

## Q: How can my Agent investigate a compilation failure?

When compilation ends in failure, `Ask AI` appears at the end of Task Output. Click it to preview the prompt, select `Copy prompt`, and paste it into an Agent that can access the current Android project.

Jugg saves the logs, project context, and existing compilation and deployment artifacts when the failure occurs. Later runs do not overwrite this snapshot. The investigation guide first directs the Agent to understand how Jugg works, then analyze the saved evidence and the corresponding version's source code to provide the cause, key evidence, and actionable recommendations. It identifies specific gaps when the available material is insufficient and provides a copyable draft when an Issue is recommended.

You can also click `Ask AI` in the `Jugg Plugin` group of `Jugg Running Panel`, choose `Compilation failed`, describe the problem, and select `Save Evidence and Generate Prompt`. This entry saves the current evidence at collection time.

Evidence is saved in a separate local directory and retained for 30 days by default. Environment information and collection failure reasons are saved in the evidence manifest, which the Agent reads using the path in the prompt. The prompt explicitly permits the Agent to clone Jugg into a separate investigation directory and fetch when necessary; the investigation reads existing evidence only.

The prompt uses Markdown. It is generated in Chinese when the system language is Chinese, and in English otherwise. The Agent uses the same language for its analysis and Issue content. User descriptions and original logs retain their original text; dialog instructions always use English.

When the Agent recommends an Issue, it provides a prefilled bug report link and complete content organized by field. Open the link, review the title, description, and diagnostic information, sign in to GitHub, select the applicable form options, complete the submission confirmation, and submit. If the link is too long or prefilling fails, copy the full content into the corresponding fields. Local evidence files must be provided separately; the link does not attach or upload them automatically.

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
