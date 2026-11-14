# Compilation System: Custom Compilers and Host Interaction

> Last verified: 2026-10-27
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Purpose

This page locates the custom compiler SPI, its JAR and instance lifecycle, stage insertion, and the interaction boundary between shared compilation and IDEA or Standalone Runtime hosts. For built-in stage order, see `02_compile_core.md`.

## 2. Core Source Index

| Entry | Location | Responsibility |
|---|---|---|
| `ProjectCustomConfigManager` | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/ProjectCustomConfigManager.kt` | Applies the effective local/server configuration to runtime collaborators. |
| `CustomCompilerManager` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/CustomCompilerManager.kt` | Resolves JARs, loads SPI instances, and owns their `Disposable` scope and classloader. |
| `ICompilerCreator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/ICompilerCreator.kt` | ServiceLoader entry that creates an `ICompiler` for one compile context. |
| `BaseCompiler` / `CompileOrder` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/` | Insert custom compilers before or after ranges exposed by built-in stages. |
| `CompileUiHandler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileUiHandler.kt` | Carries confirmations, cancellation, status/output, and deploy-related choices across the Host boundary. |
| `JuggCompileUiHandler` / `StandaloneCompileUiHandler` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandler.kt`; `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneCompileUiHandler.kt` | Supply IDE or noninteractive standalone behavior. |

## 3. JAR and Instance Lifecycle

```text
ProjectCustomConfigManager applies effective configuration
  -> CustomCompilerManager resolves local/project-relative JARs or cached remote JARs
  -> missing remote JARs download asynchronously
  -> changed configuration/JAR list or completed download releases the old
     compiler scope, closes the child URLClassLoader, and invalidates instances
  -> BaseCompileContext.customCompilers requests lazy ServiceLoader instances
     through ICompilerCreator.create(context, disposableScope)
  -> Runtime disposal closes the manager and its loaded resources
```

`CustomCompilerInfo` supplies JAR filename, path, and MD5. Configured existing JARs are checked before entering the current list; a newly downloaded JAR is checked after download. The HTTP download reset scans cached `.jar` files, so when diagnosing an unexpected SPI provider, inspect the actual loaded JAR list rather than inferring it only from the config. A missing remote JAR can leave the current compilation without its compiler; it becomes available on a later run after download and reload.

`CustomCompilerManager.updateCustomCompilers(null)` itself retains prior state, but the normal `ProjectCustomConfigManager` caller passes `config.customCompilers.orEmpty()`. An absent list in the effective project config therefore clears configured compilers. The separate `BuildIncrementalApkCommand` CI build path supplies explicit `customCompilerJars` directly to the manager; see `04_engineering_compat.md` §5 for that CI entry point.

The loader uses Jugg's classloader as parent, so custom JARs should use the host Jugg API types. `ICompilerCreator` receives a manager-owned `Disposable` for SPI compatibility; the manager disposes that scope before closing the old loader. The IDEA server-update path applies configuration under the project write lock; the background download can invalidate loaded compilers separately.

## 4. Stage Insertion and Results

```text
BaseCompiler.compile(task)
  -> before hooks whose ICompiler.order falls in this stage's before range
     filter downstream inputs through consumeFiles() and add custom outputs
  -> built-in stage runs only if the accumulated result succeeded
  -> after hooks whose order falls in the after range receive accumulated
     outputs converted to CompileFile; their outputs join the result
```

The range belongs to the *built-in compiler instance*, not merely to the global names in `CompileOrder`. Check the target stage's `beforeCompileOrderRange` or `afterCompileOrderRange` when a hook does not run. Before hooks can change the files seen by the built-in stage. After hooks receive accumulated outputs, including successful before-hook outputs; each after hook receives the same converted task rather than a chain of previous after-hook outputs. A before-hook failure suppresses built-in and after work; an after-hook failure marks the result failed while the loop still visits other after hooks. Thrown exceptions produce developer diagnostics and user-visible warnings.

To provide a compiler, package an `ICompilerCreator` implementation and `META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator`, choose an order exposed by the intended stage, then provide the JAR path and MD5 in project custom configuration. The `custom_compilers` module contains example providers.

## 5. Host Interaction Boundary

Shared compilation calls `CompileUiHandler` for fallback and change confirmations, progress, cancellation, Gradle output parsing, and run/deploy policy. Its `testEventSinkFactory` connects androidTest instrumentation events to the Test Results UI; see `06_android_test.md` for that flow. `JuggCompileUiHandler` can display IDE dialogs and Run UI; RPC mode resolves confirmations without dialogs. `StandaloneCompileUiHandler` supplies noninteractive choices and cancellation/progress state. `CompileUiHandler.DEFAULT` is a no-UI fallback used by specific noninteractive and test paths; it is not the behavior to assume for an ordinary IDE Run.

`CompileUiHandler.confirmDeployPrompt()` owns the complete installation confirmation and returns `JuggInstallSession.PromptAction`. `JuggCompileUiHandler` handles manual IDE dialogs and RPC uninstall decisions; `StandaloneCompileUiHandler` authorizes uninstall without a dialog. The no-UI default returns `CANCEL`. Deployment hosts do not provide a second confirmation policy. Session adaptation and changed-option retry remain in the deployment layer; see `03_deploy_core.md`.

| Symptom | First evidence to compare |
|---|---|
| Configured compiler absent | Effective config, actual JAR list and MD5, completed background download, then ServiceLoader registration. |
| Hook ran at the wrong point | Hook `order` and the chosen built-in stage's exposed range. |
| Custom compiler failed | `BaseCompiler` warning and underlying debug exception; check whether built-in work was suppressed. |
| Prompt or cancellation differed by entry point | Concrete Host `CompileUiHandler`, RPC mode, and its current process/status holder. |

For excessive source changes, both shared compiler helpers always ask the supplied handler. IDEA alone applies `isConfirmFallbackWhenTooManyChanges`: disabled confirmation or RPC mode returns FALLBACK; an interactive enabled preference opens the dialog. DEFAULT and standalone retain FALLBACK. CI `BuildIncrementalApkCommand` locally delegates DEFAULT and overrides only this choice to CONTINUE, preserving further-round skip state and final DEX merge without writing global thresholds.

## 6. Related Documents

- `02_compile_core.md` — built-in stage orchestration.
- `04_engineering_project.md` — project configuration and context.
- `04_engineering_ide.md` — IDE run and task lifecycle.

## 6. Built-in annotation adapters

`ProcessorRegistration` registers ARouter without a custom JAR. It uses project annotationProcessor/kapt/ksp dependencies, never a bundled ARouter compiler. A loaded external `IJuggAptProcessor` with id `ARouter` suppresses the built-in instance; recompile extensions against the current four-argument `process()` interface. Removing that extension must preserve other custom compiler configuration.

ARouter text filtering precedes `AptSourceAnalysis.facts()` confirmation of Route/Autowired/Interceptor declarations or fields, including ordinary/star/qualified imports and Kotlin aliases. Actual candidates compare annotation input, declaration identity and required inheritance with business-class baseline before collecting complete module source. Body/format/unrelated-member edits do not regenerate. Annotation-free old identities and indirect annotation users after an unannotated parent/interface/type edit are not recovered: old output remains until explicit Gradle. Triggered generation that shrinks generated class identity fails closed. ConstRef-driven recompilation rechecks semantics even with unchanged source text.

`AptSourceAnalysis` is shared per module/round and invalidated after rewrite. Read-only Kotlin PSI removes a header BOM and normalizes CRLF/CR to LF, preserving raw text/rewrite offsets separately; actual syntax errors preserve the normalized error offset. Lazy resolver results, including misses, are cached only within preparation. `APT summary` logs input counts, processor/inclusive stage times and query counts; nested durations cannot be summed.

`ARouterProcessorCompatibility` discovers JSR 269 or KSP SPI, not artifact names. Missing/ambiguous/incomplete providers fail generation. Java accepts AROUTER_MODULE_NAME/moduleName and kapt overrides duplicate Java options; KSP accepts AROUTER_MODULE_NAME/MODULE_NAME without inventing APT options. APT/KAPT plus KSP in one module, or multiple KSP JARs, fails explicitly. Fragment compatibility inspects actual processor, effective classpath and APK signals: only an unambiguous Support-only APK selects Support, otherwise AndroidX; a missing APK database result is not proof of class absence. Convert only a private processor copy when required, replace both execution and auxiliary processor paths, and verify SPI/Fragment references without editing project files.

`KspProcessorRunner` runs fresh complete-module aggregation in temporary output. KSP1 loads project compiler/plugin through `K2JVMCompilerIsolate`; Kotlin 2.x KSP1 still uses classic frontend with language/API capped at 1.9. KSP2 uses the selected task Analysis API runtime and provider via KSPJvmConfig/KotlinSymbolProcessing in an isolated classloader. Preserve options/common sources; never accept stale Gradle output or substitute an embedded engine on missing/incompatible APIs. Generated Kotlin and Java rejoin normal compilation, while IDE mirrors remain auxiliary. Further adapter design and output-lifecycle constraints are in `02_compile_annotations.md`.

### ARouter publication and retry interpretation

`AnnotationProcessorRunner.generate()` selects native KAPT for mixed modules and javac for pure Java, with selected processors only. Ordinary language compilation keeps APT/KAPT off. KSP output identities compare package/class names independently of java/kotlin root layout. Publish a complete successful directory only after shrink checks; failed generation/shrink preserves old output. A full build clears incremental directories and restores Gradle baseline use. Moshi KSP support is not expanded by ARouter support.

Best-effort IDE mirrors overwrite only current generated files, without clearing shared directories: prefer actual existing APT/KAPT generated roots, otherwise ap_generated_sources/variant/out or source/kapt/variant; KSP prefers actual module source roots preserving java/kotlin layout, otherwise ksp/variant. Mirror failure warns with module/directory/cause and retains Jugg compilation sources. It happens before language compilation and proves neither compile nor deploy success.

Detection/aggregate-input failure debugs affected files/cause and skips this module generation, possibly leaving stale indices while normal classes advance; repeating identical input is not guaranteed to regenerate. Full Gradle restores consistency. Real execution/shrink/direct generated-language diagnostics remain fail-closed. Generation then language failure retains normal changed/compiled/staging/deployed tracking; changed business classes permit regeneration on the next request, consuming newest output. After D8/deploy failure with business input already equal, skip generation and retry downstream. Pending output is not a replacement for new input comparison. Processor JAR/dependency/options changes belong to project-model/full-build lifecycle, without a separate environment fingerprint or in-place JAR replacement watcher.
