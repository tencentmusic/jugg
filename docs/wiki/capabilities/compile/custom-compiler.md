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
| Generate Java before source compilation | Supported with a project-provided adapter | Java/Kotlin annotation inputs can run through the project's processor, and generated Java enters the current compilation |
| Extension execution fails | Supported | If extension compilation logic throws an exception, the current task fails with a visible message |

## Integration

A custom compiler is provided as a JAR. The extension implements `ICompilerCreator`, which creates an `ICompiler` for the current compilation context. Jugg loads implementations from the JAR through SPI.

The project backend only needs to declare the JAR filename, path, and MD5 in the `customCompilers` configuration. The path can be an absolute local path, a path relative to the project directory, or an HTTP/HTTPS address.

ARouter `@Route`, `@Autowired`, and `@Interceptor` have built-in incremental handling, so no adapter needs to be provided. The project must retain its processor dependencies: APT/KAPT uses `AROUTER_MODULE_NAME` or `moduleName`, while KSP uses the project's actual `AROUTER_MODULE_NAME` or `MODULE_NAME` argument. Jugg compares relevant annotation values, field types, route identities, and necessary inheritance relationships in the current sources against existing business class files. Only after confirming a change does it collect complete module inputs and run the project's processor; generated Java/Kotlin continues through the current compilation. Equivalent constants, explicit defaults, attribute order, equivalent imports or aliases, ordinary method bodies, comments, and formatting do not trigger regeneration. Consumed indexes are not repeatedly added to ordinary rounds.

For modules containing Kotlin, generation includes the complete Java/Kotlin business sources. New Kotlin types can therefore participate in type resolution in the current round even when they have no ARouter annotation or compiled class file yet. With APT/KAPT, mixed-language modules use KAPT for generation, supporting Kotlin typealias imports; Java-only modules use Java annotation processing. With KSP, Jugg runs the project version of the KSP1 compiler plugin or KSP2 Analysis API engine, passes the current variant's arguments, and includes commonMain/androidMain inputs; only successful outputs from the current round are consumed. Both paths run only the selected ARouter processors; declaring both kinds of ARouter processor fails explicitly. Generated artifacts enter subsequent source compilation only after generation succeeds.

After ARouter generation succeeds and the generated class set passes validation, Jugg syncs the current round's generated sources to the current variant's generated source directory for IDE browsing and navigation. The sync overwrites only current outputs and preserves files from other processors. If the sync fails, Jugg logs a warn and continues compilation with its saved generated sources; some generated sources shown in the IDE may remain outdated. Successful source synchronization does not imply successful subsequent compilation or deployment.

When only a route constant changes, existing constant-reference analysis first schedules its users for recompilation. Even if a user's source text is unchanged, Jugg reads the current constant value and regenerates only when it changes the annotation result. ARouter and Kuikly share source reads and syntax analysis within the same round.

Normal Java and Kotlin compilation maintain business class files. Jugg uses declaration identities and source ownership to associate multiple classes in one file, nested classes, and moved declarations; new declarations are compared against empty old inputs. All actual annotation attributes and defaults participate, including `name`, `desc`, and a customized `app`. Different inputs may rerun the processor even when the generated code is identical. Ordinary ARouter runtime imports and existing deployment overlays do not independently trigger generation or warnings.

If relevant old classes cannot be read, declaration ownership is ambiguous, or relevant values and types cannot be resolved, ARouter generation is skipped for this module round. Debug information is recorded only in the log file, and this warning is not shown in compilation output. Other processors and normal source compilation continue; old indexes may be stale. If normal compilation then updates business classes, resubmitting the same inputs does not guarantee that old indexes will be refreshed. A complete Gradle build restores consistency. Current semantic resolution does not cover the entire Java or Kotlin language, nor does it promise to recognize every new structural dependency introduced by an arbitrary fork.

If APT succeeds but language compilation fails, generated sources remain in the existing pending-artifact tracking. The next request still compares current sources against business classes; differences allow another APT execution, and route edits made while artifacts are pending are checked again. After D8 or deployment failures, unchanged inputs follow the existing downstream retry flow.

The APT/KAPT path selects AndroidX or legacy Support Fragment from the APK baseline and converts the processor JAR when needed. If both types exist, no query result is available, or multiple APKs make ownership unclear, it uses AndroidX; a missing target Fragment on the current compilation classpath fails explicitly. Actual APT/KAPT/KSP execution failures or generated-source compilation errors stop the task. Removing routes or making other changes that shrink the generated class set retains the old generated directory and requires a complete Gradle build. Route registration indexes retain the existing app restart policy on deployment.

When a loaded custom ARouter processor instance exists, the default Jugg processor yields to that instance. Configuration without successful loading does not trigger this rule. Source-preprocessing adapters must be recompiled against the current Jugg interface before use. To migrate to built-in handling, remove that configuration entry while retaining other extensions. The implementation recognizes the three annotations above, reads attributes from the project's actual definitions, and invokes the project's processor. A customized `app` or a different number of generated call arguments is not independently rejected. Early `@Param` or unresolved new structures require checking actual results. See [Annotation processors](./annotation-processors.md) for the supported scope.

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
