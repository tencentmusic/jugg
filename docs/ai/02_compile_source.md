# Compilation System: Source Compilation Chain (Java/Kotlin/Dex)

> Last verified: 2026-09-12
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page covers the main incremental path from source to dex: source generation by JuggApt/KSP/KAPT, DataBinding mapper generation, Kotlin/Java compilation, dex generation, and remapping for minified variants. It focuses on stage order, Kotlin module identity, desugaring context, failure fallback, and ownership across multiple APK targets.

For resource and `R.java` generation, see `02_compile_resource.md`; for DataBinding details, see `02_compile_databinding.md`; for release obfuscation and `_jugg_fix`, see `02_compile_obfuscation.md`.

---

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|
| `BaseCompileContext.getGradleRFilePaths()` | `main/src/main/java/com/sickworm/intellij/jugg/project/BaseCompileContext.kt` | Selects one Gradle R provider per module type for source compilation, avoiding same-name class shadowing between modern and legacy R layouts |
| `SourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceCompiler.kt` | Coordinates JuggApt, DataBinding mapper, Kotlin, Java, Dex, and minify within a module |
| `JuggAptCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/JuggAptCompiler.kt` | Runs custom source-generation processors and outputs Java/Kotlin shadow sources |
| `IJuggAptProcessor` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/IJuggAptProcessor.kt` | JuggApt processor interface |
| `SourceDataBindingProcessor` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceDataBindingProcessor.kt` | Generates Java needed by the DataBinding mapper before language compilation |
| `DataBindingGenMapperCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenMapperCompiler.kt` | DataBinding mapper generation implementation |
| `KotlinCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompiler.kt` | Kotlin source compilation entry point |
| `KotlinCompilerInvoker` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompilerInvoker.kt` | Kotlin CLI arguments, plugin arguments, error parsing, and retries |
| `AndroidJarClasspathRetry` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/AndroidJarClasspathRetry.kt` | Detects inaccessible-supertype diagnostics and moves the first SDK `android.jar` to the end of the classpath |
| `IKmModuleMergerForCompilation` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/IKmModuleMergerForCompilation.kt` | Reads and merges `.kotlin_module` files on the module classpath, retaining top-level declarations and file-facade metadata |
| `KotlinComplementaryFilesCache` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinComplementaryFilesCache.kt` | Locates and reads complementary files from the project's Kotlin Gradle incremental cache on demand |
| `ComposeResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/ComposeResourceCompiler.kt` | Compiles Compose-generated expect/actual sources in one Kotlin invocation before the ordinary source stage |
| `K2JVMCompilerIsolate` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/K2JVMCompilerIsolate.kt` | Isolated Kotlin compiler loading, classpath checks, project-version ExpectActualTracker injection, and incremental-cache API adaptation |
| `JavaCompiler` / `JavaCompilerInvoker` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompiler.kt`, `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompilerInvoker.kt` | Java compilation and javac argument assembly |
| `TransformerCompiler` / `HiltAndroidEntryPointTransformer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/` | Consumes program classes analyzed by `DexCompiler`, performs equivalent bytecode transformations on Hilt Android entry points, and updates the final preparation |
| `DexCompiler` / `DexFileMaker` / `DexFileMerger` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/` | Unified reading and analysis of program classes before D8, class-to-dex compilation, file-per-class output, D8 desugaring context, and dex merging |
| `CompileEffectAnalyzer` / `DeployDataGenerator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/CompileEffectAnalyzer.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt` | Identifies default interfaces and core-library rewrites from APK/deploy DB to supply the classpath and configuration D8 needs |
| `DexMinifyCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompiler.kt` | Dex remapping and `_jugg_fix` generation for minified variants |

---

## 3. Core Data Flow

| Data | Producer | Consumer | Key constraint |
|---|---|---|---|
| Raw Java/Kotlin/Class changes | Upstream `JuggCompiler` tasks | `SourceCompiler` | Group by module compile order; an androidTest module uses name + root as its grouping key |
| JuggApt-generated Java/Kotlin | `JuggAptCompiler` | `KotlinCompiler`, `JavaCompiler` | Register with `ICompileContext.addChangedFile()` so a failed run after writing files does not cause them to be missed next time |
| DataBinding mapper Java | `SourceDataBindingProcessor` | `JavaCompiler` | Kotlin and JuggApt Kotlin sources also enter mapper processing inputs |
| Kotlin compilation output Java | `KotlinCompiler` | `JavaCompiler` | Kotlin compiles first; Java generated by KAPT and others enters the Java stage afterward |
| Class output | `KotlinCompiler`, `JavaCompiler`, raw Class inputs | `DexCompiler` | No dex stage after class compilation fails; a failure result quick-fails the remaining files |
| Dex output | `DexCompiler` | `DexMinifyCompiler` or deployment-data conversion | Non-minified output is direct; minified output goes to `un_minify` before remapping |
| `targetApkPaths` | `DexCompiler`, `JavaCompilerInvoker`, `DexFileMerger` | Deployment routing | Dex merge combines input `targetApkPaths` and retains their union |
| Compose-generated common/platform Kotlin | `ComposeResourceGeneratorBridge` | `KotlinCompilerInvoker` | All generated files enter one batch; common files are passed through typed `Options.commonSourceFiles` as `-Xmulti-platform -Xcommon-sources=...` |
| `kotlinCommonSourceDirs` | `GradleProjectInfoReader` | Ordinary incremental KMP Kotlin compilation | Read from the selected Android Kotlin task's `commonSourceSet` structure or FileTree relative paths; also add to `sourceDirs` for unified source identification, but preserve common identity without expanding to a full build |
| `agpR8Classpath` | `GradleProjectInfoReaderManager` | `DexCompiler` / `DexMinifyCompiler` | References the project's AGP R8 distribution; resolves the original buildscript artifact if Gradle code source is instrumented. Does not copy the JAR or enter `FullBuildInfo` or on-disk compile-context format |
| Ordinary KMP complementary closure | Kotlin Gradle incremental cache | `KotlinCompiler` | Query only when an Android owner has Gradle-authoritative `kotlinCommonSourceDirs` and source contains an expect/actual token. Deduplicate requested and complementary files by canonical path, compile together in the Android-owner invocation, then refresh bidirectional edges in place via the tracker after success |
| Kotlin module identity | `ModuleInfo` + Kotlin baseline output | `KotlinCompilerInvoker` | `module-name`, friend path, output directory, and `.kotlin_module` must preserve one Gradle module/variant meaning |
| Kotlin compiler plugin options | Selected Kotlin Gradle task's `KotlinCompilerPluginData` | `KotlinCompilerInvoker` | Save Gradle-resolved `plugin:<id>:<key>=<value>` per module and pair each with `-P` for the CLI. Prefer the current compilation; fall back to the nearest parent module only when its arguments are empty |
| Effective Kotlin free compiler args | `GradleProjectInfoReader` | `KotlinCompilerInvoker` | Prefer Kotlin 2.x typed `compilerOptions.freeCompilerArgs`; turn each typed `compilerOptions.optIn` marker into `-opt-in=<marker>`, combine and deduplicate by complete argument string while preserving order. Older versions fall back to `kotlinOptions.freeCompilerArgs`; if `optIn` is unreadable, omit only that enhancement |
| `DesugarInfo` | APK/deploy DB + changed-class parser | `DexCompiler` / D8 | Default interfaces, `j$.*` rewrites, and `desugar.json` use the installed APK's desugaring facts as the baseline |
| `ClassPreparation` | `DexCompiler`, updated by `TransformerCompiler` | `getDesugarInfo` / `CompileEffectAnalyzer` / D8 | Explicitly carries final program files, one analysis result, and the classpath needed for transformation; never persists in `CompileFile.extraInfo` or deployment history |
| Included-build module roots | Main Gradle project info + `include_build_*` project info | `BaseCompileContext` | Identify solely by snapshot origin; modules with the same directory in the main snapshot take precedence. Do not infer identity merely because a module is outside the project root |

---
## 4. Core Call Chain

```text
SourceCompiler.doModuleCompile()
  -> prepareSourceCompile()
       -> JuggAptCompiler collects generated Java/Kotlin and registers changed files
       -> SourceDataBindingProcessor generates mapper Java
  -> compileLanguageStagesWithRetry()
       -> KotlinCompiler compiles Kotlin + JuggApt Kotlin first
       -> JavaCompiler compiles Java + JuggApt Java + KAPT Java + DataBinding Java afterward
       -> if a real source diagnostic directly points at a JuggApt artifact, remove changed-file registration and retry once without JuggApt
  -> compileDexOutputs()
       -> DexCompiler receives compiled classes / raw Class inputs
       -> DexCompiler analyzes program classes once
       -> TransformerCompiler consumes the analysis and prepares final program classes
       -> DexCompiler calls D8
       -> minified cases go to DexMinifyCompiler; non-minified cases return dex and non-class auxiliary outputs directly
```

The essential order cannot be changed arbitrarily: JuggApt/DataBinding must finish before language compilation, Kotlin must precede Java, and minify must follow dex generation.

### 4.1 Kotlin Module Identity and Metadata

```text
KotlinCompilerInvoker
  -> prefer the project's Kotlin compiler; fall back to the bundled compiler when unavailable
  -> module-name = Gradle module name + build variant
  -> friend path points to this module's baseline Kotlin output
  -> resolve Kotlin and Java source roots from the same module together
  -> non-KAPT compilation writes directly into the module Kotlin classpath
  -> merge and save `.kotlin_module` before and after compilation
```

These parameters jointly preserve the meaning that a single file compiled this run still belongs to its original Gradle module:

- `-module-name` must match the Gradle baseline. Otherwise, JVM name suffixes on `internal` methods/properties change, potentially causing callers to see `NoSuchMethodError` at runtime.
- `-Xfriend-paths` lets current sources access `internal` declarations in the same-module baseline. If the KMP baseline is isolated, the friend path must switch to the isolated view too.
- For non-KAPT compilation, `-d` points to the module Kotlin classpath so the compiler treats baseline classes and current source as one compilation result. This avoids false `public API property declared in different module` diagnoses and broken smart casts.
- `-Xjava-source-roots` lets Kotlin read current or same-module Java sources first, resolving mutual Java/Kotlin references. Kotlin therefore always precedes Java in the language stages.
- `.kotlin_module` contains top-level functions, extension functions, and file-facade information that class files do not fully express. Merge baseline and new metadata both before and after single-file compilation. A merge failure only warns and preserves the main compile result, but later extension references may be unresolved or effect propagation incomplete.

For the source-compilation classpath, `BaseCompileContext` chooses exactly one Gradle R provider per module identity. Application/DynamicFeature, synthetic androidTest, and `Unknown` already resolved as an APK owner use their own aggregate `rFilePath`. Other Android/Library/`Unknown` modules use `ModuleBuildPathInfo.moduleCompileRFile`. JavaLibrary contributes no R. A module without a candidate contributes nothing, best-effort. androidTest must prefer its own aggregate R so its owner's main-variant R does not shadow the same-namespace test-variant R. Order: Jugg temporary module (including this run's generated R.class) → included-build target/base/feature R → current module ordinary output plus its one Gradle R.jar → dependency-module ordinary outputs plus one Gradle R.jar each → library/parent dependencies → other aggregate R (`getRFiles()` fallback) → task dependencies. A module contributes at most one explicit Gradle R.jar. The aggregate set and module-compile set each choose the latest by mtime; they are not compared across sets.

Included-build Library/JavaLibrary source may see both the included build's independently built R and the main APK's final R. In the IDE flow, included-module roots are saved from Gradle snapshot origins. On a match, `BaseCompileContext.getModuleDependencies()` first adds the inferred target APK's `R.jar`, then main-build Application/Dynamic Feature `R.jar` files, and only then ordinary module output. Thus, even if base `R.jar` lacks a business R package that exists only in a split, Kotlin/Java finds the host feature's final resource ID before the included build's independent R. The current run's Jugg-generated temporary classpath remains highest priority. Ordinary main-build modules, other module types, missing host R, or incomplete snapshot identity retain the old classpath order.

### 4.2 D8 Desugaring Decisions

Gradle project info saves the selected variant's merged `minSdk`, including product-flavor overrides of `defaultConfig`. D8 continues to consume it from the APK owner's `ModuleInfo.minSdkVersion`. For example, when the default is 29 but the current flavor is 21, use 21 rather than mistaking the default for the effective build argument.

```text
DexCompiler
  -> diff dependency JARs by class content; when a changed class belongs to a Java nest, add the whole available nest from the new JAR
  -> read program classes once and collect interfaces / static invocations / annotations / external superclasses through ClassFileParser
  -> TransformerCompiler consumes explicit ClassPreparation without rereading program classes
  -> rewrite a matching Hilt Android entry point to its generated Hilt_* superclass; otherwise leave its file unchanged
  -> choose D8 minApi from the owner variant minSdk of the APK belonging to the current module (application for base APK, dynamic feature for split); use 21 only if minSdk is unreadable
  -> find default interfaces corresponding to `$-CC` / `$DefaultImpls` in APK/deploy DB
  -> copy these baseline classes into a temporary D8 classpath
  -> if the APK contains `j$.*`, find `desugar.json` in project coreLibraryDesugaring dependencies
  -> use the project's AGP D8; fall back to bundled D8 if its API is incompatible or execution fails
```

Desugaring cannot be decided from the current module's `minSdkVersion` alone. Incremental DEX must have the same bytecode form as the installed APK. When the baseline contains `$-CC` / `$DefaultImpls`, D8 needs the corresponding interface on the classpath so default-method call shapes remain compatible. When it contains `j$.*`, D8 also needs `desugar.json` from the project's `coreLibraryDesugaring` dependency. If that configuration is missing, Jugg warns and continues; the remaining risk is inconsistent references to newer Java APIs on the device.

The dependency-JAR class diff cannot retain only changed CRC entries. Java 11 nest hosts/members form one D8 input unit through `NestHost` and `NestMembers`; when any member changes, `DexCompiler` recursively includes the whole nest available in the new JAR. Otherwise, filtering out unchanged anonymous or inner classes can trigger `requires its nest mates ... unavailable`.

After language compilation, a Hilt `@AndroidEntryPoint` / `@HiltAndroidApp` class is still in its raw form, without the Gradle Transform. `TransformerCompiler` recognizes an entry point from its complete annotation descriptors, finds the existing generated `Hilt_*` superclass under Hilt's naming rules, and rewrites its direct superclass, generic signature, real `super` calls, and `onReceive` call corresponding to the Receiver marker in a controlled temporary directory. It looks for the generated superclass first among this run's program classes, then strictly in current compile-classpath order across directories and JARs; the found class is also added to the D8 classpath. If a required generated superclass is missing, source compilation fails explicitly and asks for a full Gradle build rather than sending an untransformed class to D8.

The current source-level compatibility range is Hilt `2.41`–`2.60.1`. In Hilt `2.41`–`2.48.1`, the generated Receiver superclass uses a private boolean field `onReceiveBytecodeInjectionMarker`; from `2.49`, it uses an `OnReceiveBytecodeInjectionMarker` class annotation. Jugg reads the same generated-superclass bytes and recognizes both markers, without branching on dependency version. The stated range means entry-point conversion semantics have been compared with the official visitor; the project must still run a full Gradle build with its own Hilt version to generate a baseline matching the current dependency graph.

This processing reuses only code already generated by the most recent full Gradle/Hilt build; it does not run Hilt/Dagger APT, KAPT, or KSP. After changing injected fields, bindings, constructor dependencies, entry-point annotations, or anything else that changes the generated graph, the user must run a full Gradle build to refresh the baseline. A class already extending the corresponding `Hilt_*` remains unchanged. Receiver injection is inserted only when the generated superclass carries an official marker, avoiding duplicate transformations.

Compose resource generated source is a separate prerequisite before the ordinary source chain. `ComposeResourceCompiler` places Res, accessors for each source set, the expect collector, and the Android actual collector into one `KotlinCompilerInvoker` invocation, explicitly passing the common-source file list. Compiled classes then enter `SourceCompiler`'s class/dex path; expect and actual are not compiled separately. Gradle project info may retain generated source under the build directory in `sourceDirs` for Kotlin compilation metadata. `FileChangesHandler` consistently excludes these paths at the file-change boundary so they do not reenter ordinary Kotlin compilation as user source. Generated source that JuggApt or another compiler registers directly during this run does not pass through that file-event filter.

When the IDE exposes a common source set as a same-root virtual module, both ordinary Kotlin and Compose resource compilation first resolve the configured Android owner. They do not take classpath, output, Compose metadata, or APK ownership from the virtual module's flattened snapshot.

Ordinary KMP business source uses the normal Kotlin stage. After `KotlinCompiler` finds an expect/actual token, it switches to the same-root Android owner. `KotlinCompilerInvoker` opens the Gradle cache with the project's Kotlin compiler; `getComplementaryFilesRecursive()` returns supplemental inputs for this run. An in-process project-compiler invocation sets `incrementalCompilation=true` and registers the project-version `ExpectActualTrackerImpl`. After final success, `updateComplementaryFiles()` updates the cache using the requested + complementary closure. Missing or corrupt cache, non-unique candidates, missing edges, and write-back failures produce debug logs only. Ordinary Kotlin files do not trigger the query or tracker.

When a K2 Gradle task exposes `multiplatformStructure`, project info saves fragment-to-source-root mappings, refines edges, and the default fragment. Only ordinary KMP complementary invocations append `-Xfragments`, `-Xfragment-sources`, and `-Xfragment-refines` for the final source closure. Compose resource generated source continues to use its separate typed common-source parameter, not the business-source fragment graph. Old project info or Kotlin tasks lacking this structure keep an empty graph and compile through the original path.

Kotlin 1.9 baseline Kotlin output may contain old JVM classes from the dirty expect/actual closure. The invoker locates these classes through the project's incremental-cache source-to-output relationship, copies the rest of the baseline to a temporary read-only view, and replaces both classpath and friend path with that view. It does not move or delete the official baseline. The temporary view is removed after success or failure; if cache reading fails, the original path remains and the Kotlin compiler decides.

`GradleProjectInfoReaderManager` first reads the R8 code source actually loaded by the Android plugin. If the path points into Gradle's `jars-*` / `transforms-*` instrumentation cache, it selects the same-name original artifact from the Android module's or root project's buildscript classpath; if no original is found, that external runtime is not exposed. `DexFileMaker` then loads D8 from `agpR8Classpath` with a separate `URLClassLoader` to avoid class conflicts between project AGP R8 and Jugg's bundled R8. The runtime is cached by canonical path. Missing path, class/method loading failure, unsupported current desugared-library API, or external D8 execution failure all fall back to bundled R8. An external D8 failure emits only a user-visible `warn` without a stack trace; the classpath and original exception remain in the `debug` log. If bundled R8 also fails, its execution throws the final exception.

---
## 5. Hidden Constraints / Design Rationale / Known Boundaries

- `collectJuggAptGeneratedFiles()` fails open: a processor exception only warns, then main compilation continues. Do not equate a JuggApt warning with failure of the entire run.
- JuggApt-generated files are registered as changed files. Only when a language compiler's real source diagnostic directly attributes a problem to a JuggApt artifact does the retry call `removeChangedFile()` first, preventing a bad shadow source from contaminating later runs.
- If a batch Kotlin compilation fails, same-batch files without direct diagnostics may be marked with a generic failure; Java files in the batch may also have empty error lists. These knock-on failures do not undo JuggApt changed-file tracking; generated files remain for compilation in a later run.
- The JuggApt fallback retries only once and only when a direct source diagnostic points at a JuggApt artifact from this run. Ordinary Kotlin/Java compilation failures do not enter this branch.
- When Kotlin compilation fails, non-Kotlin inputs are marked skipped so the Java stage does not produce misleading errors without the Kotlin classes it needs.
- Each Java compilation creates and closes its own `StandardJavaFileManager`. That object caches JAR handles from the classpath; reusing it across compilations could prevent Gradle clean from deleting intermediate artifacts such as `R.jar` on Windows. A close failure only logs a warning and does not alter an already completed compile result.
- Deleting an entire Java/Kotlin source file creates no new compile input or class-removal data. Old classes in the installed APK or previous incremental deployment remain accessible by direct reference, reflection, or class loading. On rename, the new path may compile, but deletion does not remove the old path's class either. Refresh the full Gradle APK baseline only when verifying that the old class is gone.
- `ModuleBuildPathInfo.kotlinClassPath` chooses the newest existing directory by modification time among AGP 9 Built-in Kotlin `intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes`, KMP Android target `classes/kotlin/android/main`, and legacy `tmp/kotlin-classes/<variant>`. Ties prefer Built-in Kotlin, then KMP Android, then legacy; if none exists, it falls back to the legacy path. Classpath synchronization covers all three directories so Kotlin classes remain available after local or remote full builds. The AGP 9 profile of `android_demo_project` uses the full `src/main` demo instead of a separate source set. The app keeps KSP but routes ARouter through Java `annotationProcessor` to avoid Built-in KAPT task-dependency conflicts with KSP/DataBinding, while KMP moves to `com.android.kotlin.multiplatform.library`. AABResGuard 0.1.10 depends on the removed `AppExtension`, so the AGP 9 profile does not load that plugin and release APKs still use standard R8. Other profiles retain AABResGuard integration coverage.
- Included-build identity is saved only as a runtime set in the IDE's multi-snapshot project-info merge chain; it does not enter the `ModuleInfo` serialization protocol. If the main Gradle snapshot is missing, the included snapshot unreadable, or CLI loads only one Gradle project-info snapshot, the set is empty and compilation follows the existing order best-effort.
- `compileDexOutputs()` retains non-class outputs of the language stage, usually generated source or auxiliary artifacts not directly fed into dex.
- In minified cases, dex is first written to `context.tempCompileDir/un_minify`; `DexMinifyCompiler` then writes to the final task outputDir. Do not inspect only the final directory when investigating paths.
- `DexCompiler` output retains the old `apkPath` anchor and also records every module `targetApkPaths`; deployment routes among multiple APKs using the target set.
- D8 version selection uses the R8 actually loaded by the project's AGP. A Gradle instrumentation-cache artifact must first be resolved back to its original buildscript artifact. If project info provides no safe path, an isolated runtime cannot be created, or external D8 execution fails, Jugg uses its bundled R8.
- D8 `minApi` uses the effective `minSdk` of the variant owning the APK, matching Gradle dex behavior. It falls back to 21 only when `minSdk` is unreadable. `DexMinifyCompiler` resolves minApi the same way for `_jugg_fix` dex. Temporary classpath entries supply default-interface compatibility; `desugar.json` and actual `minApi` jointly determine core-library rewriting.
- Fallback 21 is the more aggressive side for language-level desugaring, not the conservative side. If the baseline was not desugared, it can make D8 generate calls to `$-CC` classes absent from that baseline. Use it only when `minSdk` cannot be read at all, never as a substitute for the real `minSdk`.
- `isEnableDesugared` (whether the baseline APK has `$-CC` / `$DefaultImpls`) is only a diagnostic signal, logged with minApi at debug level. It cannot express the variant `minSdk`. Using it to choose minApi would split incremental DEX from the Gradle baseline (for example, rewriting `java.time` to `j$.time`).
- Default-interface classes in a temporary classpath supply desugaring context, not ordinary business dependencies. Removing this step could give changed classes default-method call forms different from the baseline.
- `DexCompiler` performs unified pre-D8 program-class analysis and passes explicit `ClassPreparation` in sequence to `TransformerCompiler`, `getDesugarInfo`, and D8. `DeployDataGenerator` / `CompileEffectAnalyzer` no longer read metadata from `CompileFile.extraInfo` or fall back to fully parsing program classes; recursive superclasses are read by header only, without traversing method bodies.
- Hilt entry-point transformation is not complete annotation-processing support. It maintains only the entry-point bytecode form corresponding to existing generated outputs from Hilt `2.41`–`2.60.1`. Missing or unreadable generated outputs fail; the user chooses a Gradle fallback, with no new Hilt-specific automatic fallback.
- Core-library rewrite looks for `desugar.json` only after the APK database has found `j$.*`. A project dependency declaration alone must not unconditionally enable it for every module.
- In KAPT cases, Kotlin compiler warning/error text is logged at debug level so APT/KAPT noise does not drown user-visible output; the parser still decides failure.
- Kotlin compiler plugin arguments preferentially reuse `KotlinCompilerPluginData.options.arguments` resolved by the Gradle task, supporting the Kotlin Gradle Plugin's `kotlin_gradle_plugin_common` and older `kotlin_gradle_plugin` getters. If unreadable, keep an empty list rather than fabricate plugin arguments. For the current module's compilation, use the first nonempty argument set from current module toward parent; do not merge modules or deduplicate by option name, preserving `allowMultipleOccurrences` semantics.
- Gradle-resolved plugin arguments become `-P` only when this run loads the project's compiler plugin. If a loaded plugin reports `unsupported plugin option`, remove only that plugin ID's Gradle-resolved arguments and share the global one-retry budget. After successful fallback, cache by compiler toolchain and the original argument set; retry the original set again when the toolchain or arguments change. Arguments explicitly supplied by the user in `kotlinFreeCompilerArgs` are not part of this fallback.
- For `required plugin option not present`, retry at most once. Jugg identifies the plugin ID first from a JAR's `CommandLineProcessor` service and class constants, then falls back to older filename matching. A matched plugin is disabled only for later compilations by this invoker; if no plugin can be identified, retain the original failure rather than disabling every plugin.
- Kotlin 2.x opt-in markers live in separate typed `compilerOptions.optIn` and are not guaranteed to appear in `freeCompilerArgs`. At the Gradle project-info read boundary, turn each marker into `-opt-in=<marker>` and merge with free args, deduplicating by full argument string without changing order. If `optIn` is unreadable, omit only that enhancement; leave `freeCompilerArgs` and the `kotlinOptions` fallback unchanged. Without the argument, an incremental `kotlinc` call can report `this declaration needs opt-in` for a declaration such as `@HiddenFromObjC` while Gradle succeeds. The regression owner is `KmpComposeFlowReproTest.compileCommonSourceWithHiddenFromObjCRequiringOptIn`, with fixture `android_demo_project/kmpCompose/src/commonMain/kotlin/.../ObjCRefinementCase.kt`. In the kmpCompose template, Kotlin 1.9 provides the same opt-in through legacy `freeCompilerArgs`, while 2.1/2.3/2.3-AGP9 use typed `compilerOptions.optIn`.
- `commonSourceFiles` is a typed Kotlin-invoker argument, not a free-form string assembled by callers. When empty it adds no multiplatform arguments; for Compose-generated expect/actual, it adds both `-Xmulti-platform` and `-Xcommon-sources`.
- `ModuleInfo.sourceDirs` is the flat set of all effective source roots for a module. Gradle common and fragment roots are also included for file-change identification, module ownership, source database, and effect analysis. `ModuleInfo.kotlinCommonSourceDirs` is the common subset identified by Gradle-authoritative data; flattened IDE `sourceDirs` must not overwrite it, nor infer it from names like `commonMain` or `sharedMain`. An ordinary KMP invocation uses only this subset to mark common files among final inputs.
- A nonempty `kotlinCommonSourceDirs` gates complementary queries to a KMP module/source set. Merely naming an ordinary Android module's source directory `commonMain` (for example, local-shell aggregated source) does not enable KMP complementary behavior, even if the source text contains expect/actual tokens.
- Ordinary business-source closure has been verified for Kotlin 1.9 expect-only/actual-only, Kotlin 2.1 commonMain/androidMain with intermediate sharedMain refinement, and Kotlin 2.3 expect-only/actual-only. The fragment graph covers only the structure exposed by the selected Android Kotlin task, not a global source-set graph for every project target.
- Kotlin baseline isolation must derive from the incremental cache's source-to-output relationship and be limited to this run's dirty expect/actual closure. Do not guess from filenames or declaration names, or move the entire official output directory.
- Enable the tracker only in an in-process invocation of the project's Kotlin compiler. Failed invocations, intermediate retry attempts, KSP-only phases, and out-of-process invocations do not write the cache; a cache write-back failure does not alter successful Kotlin output.
- Compose common/platform classification uses the identity of IDE source-set modules under the same owner module root. `androidMain` is always platform; other `Unknown` source-set modules can represent a common source set other than `commonMain`. Do not infer this from a custom resource-root path.
- If generated Kotlin compilation fails, aggregate the original line number and diagnostic text from `KotlinCompilerInvoker` back to the original Compose resource input; do not replace them with a generic failure message.
- Compose resource compilation detects capability by generator task/API structure, not exact Kotlin/Compose version allowlists. Kotlin 1.9, 2.1, and 2.3 profiles all have targeted regressions.
- The IDE JVM also hosts the in-process Kotlin compiler. An older compiler's shaded `JavaVersion.current()` may fail to parse a newer host JDK. `KotlinCompilerHostCompat` presets the host feature only if probing fails; when the host JDK is >= 25 and classpath contains android.jar, `KotlinCompilerInvoker` also adds `-no-jdk`. Recreating the compiler does not change its host environment. If the same `INTERNAL_ERROR` recurs after retry, inspect `preset shaded JavaVersion.current to`, `add -no-jdk`, and the actual project Kotlin version.
- When ordinary framework/HideAPI JAR dependencies for ROM or in-car system apps follow the SDK `android.jar`, Kotlin's nested-type resolution can hit the earlier public stub first and report `cannot access ... which is a supertype of ...` or `unresolved supertypes:`. If this diagnostic occurs and the first `android.jar` is not last in classpath, `KotlinCompilerInvoker` moves it to the end and retries once, sharing the existing `isCanAutoRetry` / `hasRetryCompile` single-retry budget. On success it remembers this fallback per source file, and other files in the batch use the shifted order; on failure it retains the first diagnostic and stores no memory. The check does not require an `android.` package name because Kotlin 2.2+ renders only a simple type name. Default `getModuleDependencies()` order is unchanged, and memory clears on compile-context recreation. The actual matching form is “source extends a class in a JAR whose supertype exists only in the shadowed framework JAR.” A source file directly naming a stripped nested type reports `unresolved reference` and does not take this fallback. This failure is specific to Kotlin nested-type classifier-path resolution; javac directly looks up the binary name of a same-name nested class and is unaffected by the earlier stub, so Java compilation does not use this fallback. The regression owner is `SourceCompileTest.romHiddenApi_shouldRecoverWhenSdkAndroidJarShadowsFrameworkJar`, using `compileOnly` fixture JARs under `android_demo_project/app/romlibs/` and the `testcase/romhiddenapi/` scenario.
- When an older project Kotlin compiler closes `DescriptorLoadingContext` in the IDE process, it may erroneously close the IDE's `DelegatingFileSystem` and throw `UnsupportedOperationException`. Only when a complete exception block matches all three signals does the invoker record a host conflict by normalized compiler classpath. Warm-up does not launch a sourceless child process; subsequent compilation for the same toolchain uses an isolated JVM, and a first hit in real source immediately retries once in an isolated JVM. Other compiler classpaths, explicit isolation mode, bundled compiler, and other exceptions follow the original path. An out-of-process invocation does not write expect/actual tracker cache. The one-shot process writes Kotlin compiler arguments into a UTF-8 argfile, so module classpath, plugin arguments, and source lists no longer consume OS command-line space directly. The Java launcher's compiler classpath remains passed as before.

---

## 6. Investigation Entry Points

| Symptom | First entry point |
|---|---|
| Generated source is written but not compiled next run | `SourceCompiler.prepareSourceCompile()`: check whether `addChangedFile()` registered JuggApt output |
| JuggApt-generated code causes compilation failure | `compileLanguageStagesWithRetry()` and `shouldRetryWithoutJuggApt()` |
| Gradle clean cannot delete `R.jar` on Windows | First identify the handle owner; if it is Android Studio, check that `JavaCompilerInvoker` closes `StandardJavaFileManager` after this run's javac completes |
| An old class remains loadable after deleting or renaming a source file | Deletion generates no class-removal data; this is an expected incremental result. Refresh the full Gradle APK baseline only when the old class must disappear |
| Many knock-on Java errors after Kotlin fails | `compileLanguageStages()`: check whether the Java stage was skipped and inspect Kotlin failure details |
| Missing classpath / abnormal Kotlin metadata | `K2JVMCompilerIsolate.checkClasspath`, `KotlinCompilerOutputParser`, `KmModuleMergerForCompilation` |
| Wrong resource ID after incrementally compiling included-build source | Inspect included-build module roots in `CompileContextManager`, `BaseCompileContext.findIncludedBuildTargetRFiles()`, and actual Kotlin `-classpath`; confirm inferred target and host-feature R precede included-module output, then compare the new DEX's inlined ID with actual base/split APK resource tables |
| Source compilation reports `找不到符号: 变量 <新资源字段>` (“cannot find symbol: variable <new resource field>”) while the R class itself is found | Check which JAR supplies the first same-name `R$xxx` in `-classpath`. Check whether `BaseCompileContext.getGradleRFilePaths()` chose legacy `compile_only_not_namespaced_r_class_jar` instead of `compile_r_class_jar`, and compare the `module compile R.jar candidates found in module` debug log |
| Kotlin `internal` method missing at runtime, or smart cast incorrectly considered cross-module | Inspect `module-name`, friend path, and `-d` output directory in `KotlinCompilerInvoker` |
| DataBinding mapper missing | `SourceDataBindingProcessor.processDataBindingMapper()` and `DataBindingGenMapperCompiler` |
| Dex merge failure | `DexCompiler`, `DexFileMerger`, `IncrementalCompilerHelper.mergeDex` |
| Runtime issue after incremental change involving default methods / `j$.*` | Inspect minApi in `DexCompiler`, `CompileEffectAnalyzer.getDesugarInfo()`, and `BaseCompileContext.findDesugaredLibraryConfiguration()` |
| D8 assertion or incompatible bytecode after an AGP/Kotlin upgrade | Inspect `JuggProjectInfo.agpR8Classpath`, isolated loading in `DexFileMaker`, and version logs |
| Kotlin `INTERNAL_ERROR` stack contains shaded `JavaVersion.parse` | Inspect `KotlinCompilerHostCompat`, `K2JVMCompilerIsolate`, and host-JDK compatibility logs in `KotlinCompilerInvoker` |
| Kotlin `INTERNAL_ERROR` stack contains `DelegatingFileSystem.close` and `DescriptorLoadingContext.close` | Inspect host-conflict marker in `KotlinCompilerOutputParser` and isolated-JVM fallback logs in `KotlinCompilerInvoker`; do not retry by recreating the compiler in the same host environment |
| Wrong release dex path or class name | `DexMinifyCompiler.preObfuscateForMinifyInfo()` and `obfuscateDexFile()` |
| Class/dex deployment ownership lost with multiple APKs | Inspect `targetApkPaths` in `DexCompiler` output and `IncrementalCompilerHelper.mergeDex()` |

---

## 7. Related Documents

- Core compilation scheduling: `02_compile_core.md`
- Resource compilation: `02_compile_resource.md`
- DataBinding: `02_compile_databinding.md`
- Incremental Manifest merge: `02_compile_manifest.md`
- Obfuscation mapping: `02_compile_obfuscation.md`
- Testing strategy: `06_testing.md`
