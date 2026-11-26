# Annotation Incremental Support: Integration Guide

> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope and owners

`IJuggAptProcessor` is the generated-source entry point, not a restriction to JSR 269. Integrate through `JuggAptCompiler` and `ProcessorRegistration`; reuse `SourceCompiler`, normal Kotlin/Java compilation, D8/minify, and deployment. Do not create a second compilation chain. Concrete processor behavior is in `02_compile_custom_ui.md` and `02_compile_source.md`; verification policy remains in `06_testing.md`.

| Existing adapter | Generation and input | Boundary |
|---|---|---|
| Kuikly Page | Appends missing registrations to existing `KuiklyCoreEntry.kt`; current Kotlin candidates | Does not run project KSP; existing-route deletion/rename/change needs Gradle |
| ARouter | Compares current annotation semantics with business classes, then runs project APT/KAPT or KSP over the module | Full-module aggregation is paid only after a real trigger; annotation-free inputs do not recover old annotation identity |
| ButterKnife | Runs project official APT/KAPT for current candidates plus affected same-module binding descendants | Parent facts can come from classpath; inheritance queries alone do not require every source file |

Owners under `main/src/main/java/com/sickworm/intellij/jugg/`: `compiler/source/apt/AptSourceAnalysis.kt` owns shared text, lazy Java AST/Kotlin PSI, declaration facts and invalidation after rewriting; `AptSemanticResolver.kt` owns lazy constant/type/source resolution with current source ahead of old classes. `AnnotationProcessorClasspath` reads processor SPI after candidate detection. `AnnotationProcessorRunner` and `compiler/source/kotlin/KspProcessorRunner` execute project engines. `GeneratedSourceCheckpoint` publishes complete aggregate directories; adapter-specific semantics remain under `compiler/source/apt/processors/`.

## 2. Decide the supported contract before implementation

Read the actual project processor version, runtime lookup and Gradle wiring. Record output ownership (per type, module aggregate, or append-only), required source versus classpath facts, baseline and change semantics, add/edit/delete/rename behavior, engine/parameters/JDK requirements, processor dependencies, and runtime consumption/restart/cache requirements in the task plan. Report both retained incremental scope in normal runs and the local failure boundary; reuse the explicit user Gradle entry only where local handling cannot produce a valid result.

## 3. Detection cost and input closure

Use available module configuration/dependency or generated-entry gates, shared conservative text filtering, then syntax confirmation, lazy semantics/baseline comparison where valuable, and only then expand inputs and run generation. Skip a gate when it cannot be reliable. Do not open JARs, prepare classpath or walk source trees every round just to discover unrelated modules.

Specify normal/star/qualified imports, Kotlin alias/use-site and unsupported indirect forms explicitly. Text may overmatch comments/strings; syntax filters them. Whether annotation-free text can return depends on deletion semantics: ButterKnife associates prior bindings including `Outer$Inner_ViewBinding`; ARouter does not recover the removed final annotation. Shared facts are not a complete compiler symbol system; method annotations beyond that model need explicit handling. Start with current files plus classpath; extend only the affected closure unless outputs really aggregate the whole module. Semantic deduplication is not mandatory: ButterKnife currently regenerates annotated files after method-body edits.

## 4. Execution and artifact lifecycle

Select actual project processors explicitly and retain their compiler/classpath/parameters. Verify joint execution only for real processor dependencies. Reuse Java, KAPT and KSP runners; KAPT owns Kotlin stub semantics. Confirmed javac internal-API conflicts may use an isolated JVM with scoped exports/opens. Current KAPT supports explicit isolation; ButterKnife isolated javac remains specific, not a shared general runner.

Generate to temporary output, validate expected outputs and unsupported shrinkage, publish compile-ready sources, register generated changed files and invalidate rewritten snapshots, compile business/generated inputs together, then pass normal DEX/staging to deployment. Generation success is neither language-compile nor deploy success. Do not advance a semantic baseline merely after generation; ARouter compares business classes. These stages are not a single transaction.

Complete aggregate output may replace its directory only after shrink validation (ARouter). Per-type output overwrites this round's files while preserving unrelated types (ButterKnife). Append-only entry updates preserve prior registrations (Kuikly). Do not apply whole-directory replacement to partial output. Return Java/Kotlin `CompileFile` with correct module/baseDir; appended business inputs must remain in failure attribution.

R2 placeholders are compilation constants, not runtime IDs: preserve baseline names, add only missing names, compile temporary R2 with bindings and let the official processor retain/convert symbolic R references. Resource/layout delivery remains in the resource pipeline. Layout rename does not rewrite business annotations. Reflection-based lookup can cache absence, so first new ButterKnife binding should be checked after App restart.

## 5. Failure, diagnostics and verification

No candidate leaves normal compilation intact without user-visible processor execution logs. ARouter detection/baseline errors currently debug and skip this module round; ButterKnife errors fail closed. Neither failure means semantically unchanged. Real execution failure, missing required output or unsupported shrinkage must fail the affected round instead of silently claiming old output as current. `failClosed` and `ownsGeneratedFile` stop a retry without generated code from masking direct language errors or appended business-input failures. IDE navigation mirror failure is auxiliary and preserves valid compile output.

Use debug/trace for skip/cost details, info for actual execution/module/start/finally duration, warn for unexpected failures. `APT summary` stage times are inclusive, not additive; counts expose work avoided without brittle wall-clock assertions.

Apply the test-value gate first. Verify unaffected no-candidate compilation, real processor-version naming and generated references, input closure, consecutive rounds and generation-then-compile failure recovery, supported deletion/rename boundaries, SourceCompiler-to-DEX/staging flow, and runtime consumption where required. Existing owners include `JuggAptCompilerTest`, `ARouterJuggAptProcessorTest`, `ButterKnifeJuggAptProcessorTest`, `ARouterKspCompileFlowTest`, and IDEA `JuggCompilerTest`; distinguish host staging from device evidence. Refresh demo baselines under `06_testing.md` before using changed fixtures. Do not prebuild annotation DSLs, global dependency graphs, inheritance frameworks or signature databases for hypothetical future adapters.
