# Compilation System: Source Compilation Chain (Java/Kotlin/Dex)

> Last verified: 2026-10-14
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page follows changed Java/Kotlin/class inputs through generated source, language compilation, D8, and optional dex remapping. It records the baseline state that incremental compilation must preserve and the meaning of its fallbacks. Resource generation, DataBinding internals, and release mapping belong to `02_compile_resource.md`, `02_compile_databinding.md`, and `02_compile_obfuscation.md`.

## 2. Core Source Index

| Owner | Location | Why open it |
|---|---|---|
| `SourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceCompiler.kt` | Module stage order, generated-source tracking, language failure, and JuggApt retry |
| `JuggAptCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/JuggAptCompiler.kt` | Custom generated Java/Kotlin inputs; processor contract is `IJuggAptProcessor` beside it |
| `SourceDataBindingProcessor` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceDataBindingProcessor.kt` | Handoff from source inputs to DataBinding mapper Java |
| `KotlinCompiler` / `KotlinCompilerInvoker` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/` | Android owner, KMP closure, Kotlin command, module identity, and bounded retries |
| `KotlinComplementaryFilesCache` / `K2JVMCompilerIsolate` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/` | Gradle expect/actual cache and project compiler loading/tracker adaptation |
| `JavaCompiler` / `JavaCompilerInvoker` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/` | Java/KAPT inputs and javac invocation |
| `BaseCompileContext` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/context/BaseCompileContext.kt` | Source classpath, Gradle R choice, included-build R priority, and desugaring configuration |
| `DexCompiler` / `TransformerCompiler` / `DexFileMaker` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/` | Program-class analysis, Hilt entry-point rewrite, D8 input and runtime selection |
| `ClassPreparation` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ClassPreparation.kt` | One run's analyzed program classes and transformation output |
| `CompileEffectAnalyzer` / `DeployFileManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/` | Installed-APK/deploy-DB facts required for desugaring |
| `DexMinifyCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompiler.kt` | Remaps D8 output for minified variants |

## 3. Stage and State Handoffs

```text
JuggCompiler groups changed files by module, preserving androidTest module identity
  -> SourceCompiler.doModuleCompile() / prepareSourceCompile(): record JuggApt Java/Kotlin as changed files and ask DataBinding for mapper Java
  -> KotlinCompiler compiles changed/generated Kotlin, possibly with KMP complementary sources
  -> JavaCompiler receives changed Java, JuggApt Java, KAPT Java, and mapper Java
  -> on language failure: skip dex; retry once without JuggApt only for a direct diagnostic on its artifact
  -> DexCompiler analyzes classes once, then TransformerCompiler updates Hilt entry points
  -> D8 uses installed-baseline desugaring context and the APK owner's effective minSdk
  -> minified variant: D8 writes `tempCompileDir/un_minify`, then DexMinifyCompiler writes final output
  -> deployment consumes dex `apkPath` plus all `targetApkPaths`; non-class outputs pass through
```

`SourceCompiler` records generated files before language compilation so a failed run does not make them disappear from the next changed-file set. An ordinary JuggApt processor exception warns and leaves main compilation available; `failClosed` processors instead stop the round, including direct errors attributed through `ownsGeneratedFile()`. A retry removes its changed-file registration only when a *direct source diagnostic* identifies a JuggApt artifact produced in this run. Generic batch failures do not prove that cause. When Kotlin fails, Java inputs are marked skipped because compiling them without current Kotlin classes would give misleading results.

When deployment needs a combined DEX payload, `DeployDataPlanner.mergeDex()` calls `IncrementalCompilerHelper.mergeDex()`. That shared merge retains the union of input `targetApkPaths` along with an `apkPath` anchor; inspect this handoff if merged DEX reaches the wrong APK.

`ClassPreparation` is run-scoped: `DexCompiler` parses program classes once, `TransformerCompiler` consumes and updates that preparation, and desugaring analysis and D8 consume the result. It does not live in `CompileFile.extraInfo` or deployment history. A changed class inside a Java 11 nest brings its available host/mates from the new JAR into the D8 unit; a CRC-only class diff could otherwise produce D8's missing-nest-mate error.

Deleting or renaming a whole source file produces no new class-removal input. Previously installed classes may remain directly or reflectively loadable even if a new path compiles. Use a full Gradle APK baseline when the absence of an old class is the behavior under test.

## 4. Kotlin Baseline and KMP Boundaries

The Kotlin command must preserve one Gradle module/variant identity across this run and the baseline: `-module-name` controls JVM names for `internal` declarations; `-Xfriend-paths` grants same-module access; non-KAPT `-d` writes into the module Kotlin classpath; and `-Xjava-source-roots` lets Kotlin resolve current Java sources before javac runs. The `.kotlin_module` merger preserves top-level and file-facade metadata across partial compilation. Its failure warns without changing the immediate compile result, but later extension lookup or effect analysis may be incomplete.

The baseline classpath picks the newest existing Built-in Kotlin, KMP Android, or legacy Kotlin output directory; ties prefer that order, and no existing directory falls back to the legacy path. Full-build synchronization covers all three. For source R classes, `BaseCompileContext` adds this run's temporary classes first and chooses at most one explicit Gradle R provider per module: APK owners, including synthetic androidTest, use their aggregate R; other Android/library modules use their module compile R; Java libraries contribute none. The aggregate and module-compile candidates are each selected by mtime, without comparing across the two sets. For a recognized included-build source module, inferred target APK and main-build feature/base R precede its ordinary output, so independently built R IDs cannot shadow the host APK's final IDs. The identity comes from merged Gradle snapshot origins; absent/incomplete snapshots or a single-snapshot CLI run retain the ordinary ordering.

`ModuleInfo.sourceDirs` includes effective roots for ownership and change detection; `kotlinCommonSourceDirs` retains only the Gradle-authoritative common subset. A directory named `commonMain` alone does not activate KMP behavior. For ordinary source containing an expect/actual token, `KotlinCompiler` resolves a same-root Android owner, reads complementary files from that task's Gradle incremental cache, and compiles the canonical-path closure together. Missing, ambiguous, or corrupt cache information leaves the original inputs in place. Only a successful in-process invocation of the *project* Kotlin compiler can write tracker edges back; KSP-only phases, failed/intermediate retries, and isolated processes cannot. Cache write-back failure does not invalidate compiled output.

K2 fragment arguments come only from `multiplatformStructure` of the selected Android Kotlin task and apply to ordinary KMP complementary invocations. Older metadata takes the original path. Kotlin 1.9 may require a temporary baseline view that excludes stale outputs of the dirty expect/actual closure; that exclusion uses the incremental cache's source-to-output edges and changes both classpath and friend path, never the official output directory. A missing edge keeps the original baseline. Compose-generated expect/actual source uses a separate typed `commonSourceFiles` parameter and one Kotlin invocation before the ordinary source stage; it does not use the business-source fragment graph. See `02_compile_resource.md` for its resource ownership and diagnostics.

Gradle task data also supplies Kotlin plugin arguments and free compiler args. The invoker takes the first nonempty Gradle-resolved plugin option set from the current module toward its parent, preserving repeated options. Project plugin options become `-P` only with the project plugin loaded. Typed Kotlin 2.x `compilerOptions.optIn` markers are merged as `-opt-in=` arguments with free args at the project-info boundary; an unreadable opt-in field omits only that enhancement. An incremental opt-in error while Gradle succeeds should therefore be checked against the selected task's typed and legacy arguments.

## 5. D8 Baseline and Compatibility

D8's `minApi` comes from the effective `minSdk` of the variant owning the target APK, including flavor overrides; if that owner is unreadable, `getDexMinApi()` tries the application module before 21. `DexMinifyCompiler` resolves `_jugg_fix` dex against the application module. Fallback 21 can cause *more* language desugaring than the installed APK and introduce references to absent `$-CC` classes. `isEnableDesugared` is a diagnostic observation of baseline `$-CC` / `$DefaultImpls`, not a substitute for minSdk.

`CompileEffectAnalyzer` and the APK/deploy database identify baseline default interfaces, which become temporary D8 classpath context rather than business dependencies. Core-library `desugar.json` is sought only if the APK already contains `j$.*`; a dependency declaration by itself cannot turn rewriting on for every module. If the matching configuration is missing, Jugg warns and continues, so runtime references to newer Java APIs still need inspection.

A raw Hilt `@AndroidEntryPoint` or `@HiltAndroidApp` class needs the same entry-point bytecode form as the Gradle baseline. `TransformerCompiler` uses the existing generated `Hilt_*` superclass, rewrites superclass/signature/super calls, and handles Receiver injection according to the marker in the generated bytes. The supported comparison covers Hilt 2.41–2.60.1; this is not an APT/KAPT/KSP run. Missing generated output fails explicitly. Changes to injections, bindings, constructors, or the generated graph require a full Gradle build first.

`GradleProjectInfoReaderManager` resolves the R8 actually loaded by the project's AGP; a Gradle instrumentation-cache code source must map back to its original buildscript artifact. `DexFileMaker` isolates that D8 from Jugg's bundled R8 and caches it by canonical path. Missing/unsafe path, load/API incompatibility, or execution failure falls back to bundled R8. The external D8 fallback, original exception and classpath stay in debug logs. If bundled D8 fails too, its exception is final.

## 6. Failure Interpretation

| Observation | What it establishes | Next discriminating evidence |
|---|---|---|
| JuggApt warning followed by source failure | The processor warning alone does not fail the run; only a direct diagnostic on this run's generated file permits the one retry | `SourceCompiler` result diagnostics and changed-file registration |
| A batch reports many Java failures after Kotlin fails | Skipped or knock-on results can lack direct diagnostics | First Kotlin diagnostic, then `compileLanguageStages()` result |
| Kotlin `internal` method missing at runtime or smart cast treated as cross-module | This may indicate mismatched module identity rather than a source error | Actual `-module-name`, `-Xfriend-paths`, `-d`, and baseline metadata |
| New R field is missing although `R$...` resolves | A same-name older R provider may have won classpath order | `BaseCompileContext.getGradleRFilePaths()` selects the module's R provider; compare its aggregate/module candidate with the first supplying JAR in actual `-classpath` |
| Included-build source inlines a wrong resource ID | The new DEX and installed base/split resource tables disagree | `BaseCompileContext.findIncludedBuildTargetRFiles()` selects host APK R files; compare merged snapshot identity, target/host-feature R ordering, DEX constant, and APK tables |
| D8 reports a missing nest mate or default-method/runtime failure | The wrapper does not prove source compilation failed | New JAR's nest entries; D8 `minApi`, baseline `$-CC` / `$DefaultImpls`, `j$.*`, and `desugar.json` |
| Kotlin shaded `JavaVersion.parse` fails on a new host JDK | Recreating the compiler retains that host environment | `KotlinCompilerHostCompat` probe, `-no-jdk` log, host JDK and project Kotlin version |
| Kotlin stack contains `DescriptorLoadingContext.close` and `DelegatingFileSystem.close` | Only the complete host-conflict signature permits an isolated-JVM retry | `KotlinCompilerOutputParser` signature and invoker's toolchain-keyed fallback log |
| Kotlin reports `cannot access` / `unresolved supertypes` for a ROM superclass | Inspect proactive framework detection and actual classpath order, not an SDK-last retry | `FrameworkJarDetector`, `BaseCompileContext`, compile/runtime difference and detected paths |

For a project plugin's `unsupported plugin option`, the invoker removes only that plugin ID's Gradle-resolved arguments and caches a successful fallback by toolchain and original arguments. A `required plugin option not present` retry disables an identified plugin only for later invocations; an unidentified plugin keeps the failure. User-supplied free compiler arguments are outside these fallbacks.

For host-JDK failures, `KotlinCompilerHostCompat` presets the shaded Java version only after its probe fails; on JDK 25 or newer with `android.jar` in the classpath, the invoker adds `-no-jdk`. A complete IDE file-system-close signature is keyed by the project compiler classpath and retries once in an isolated process. That process cannot update the expect/actual tracker cache; its UTF-8 argument file avoids overflowing the source/plugin argument list, while the Java launcher's compiler classpath remains a separate command-line input.

A project Kotlin compiler can request the IDE installation home while reading shaded plugin descriptors. `KotlinCompilerHostCompat.ensureIdeaHomePath()` preserves an existing directory containing `bin/idea.properties`, otherwise validates the Host `PathManager.getHomePath()` result before setting `idea.home.path`. The isolated compiler process receives that same valid value. If unavailable, keep the original compiler exception and existing fallback; do not invent an IDE installation for Standalone.

## 7. Related Documents

- Stage scheduling and fallback: `02_compile_core.md`
- Resource and Compose source generation: `02_compile_resource.md`
- DataBinding: `02_compile_databinding.md`
- Release mapping: `02_compile_obfuscation.md`
- Verification owners and test-value gate: `06_testing.md`; ROM hidden-API and Kotlin opt-in regressions are in §6.5
- Runtime evidence interpretation: `09_plugin_runtime_debug.md`

## 8. Annotation, platform and process boundaries

`ButterKnifeJuggAptProcessor` gates on resolved butterknife-compiler artifact names before reading source/JARs. Shared text then real field/method annotation checks support normal/star/qualified imports and Kotlin alias/use-site; matching previous binding identities also detect removal of the last binding. Cross-file typealias is outside detection. No candidate performs no classpath preparation, inheritance scan or execution. Real candidates run official project ButterKnifeProcessor: isolated javac `-proc:only` uses configured JAVA_HOME or host java.home, scoped javac exports on JDK 9+, and `-Xlint:-options`; Kotlin requests isolated KAPT. Only butterknife.minSdk/debuggable are passed. Execution logs start and finally duration at info.

Binding parents are read from classpath; same-module bound descendant sources join generation and language compilation without whole-module collection. Cross-module bound descendants that cannot commit atomically fail the affected round. Output is published per file under incrementalDataDir/butterknife/module/variant/sources; unrelated types remain. Removed binding output, deletion/rename or generation failure stops the round and requires explicit Gradle refresh. R2 compatibility and reflection-cache restart boundaries are in `02_compile_annotations.md`. Owners: ButterKnifeJuggAptProcessorTest and JuggCompilerTest.testButterKnifeAnnotationChangeCompilesGeneratedBinding; host staging does not establish device E2E.

`GeneratedSourcePaths` replaces only roots containing zero-byte Java with cleaned per-round filtered copies outside the generated search tree; preserve relative paths and other files, never edit originals. A root's scan/copy/cleanup failure warns and retains that original root, without returning a partial copy or discarding other roots. `GeneratedSourcePathsTest` and `Issue75EmptyJavaReproTest` own this boundary.

`KmModuleMergerForCompilation` uses kotlin-metadata-jvm 2.4.10 and actual file headers across Kotlin 1.3–2.4, preserving the complete successfully read metadata version, including patch. Kotlin 1.3 metadata is 1.1.16; pre-1.4 flags differ. Preserve original bytes on first read and restore only the unreadable file for this round with the project logger warning; do not replace failed reads with an empty default-version index. Other files merge normally, including warm-up and subsequent incremental rounds; that fallback cannot merge new top-level declarations into the unreadable index. Existing empty indices require full Gradle regeneration. Real-compiler fixtures and version roundtrips belong to KmModuleMergerTest and CompileEffectAnalyzerTest.

Android javac isolates platform APIs only for non-JavaLibrary inputs containing android.jar. Source ≤8 uses PLATFORM_CLASS_PATH, even with higher bytecode targets; source ≥9 uses `--system none` and a ZIP filesystem MODULE_PATH view of the SDK core-for-system-modules.jar as java.base. Explicitly choose the jar provider and independent Path open; close it with the file manager. Old SDK fallback uses android.jar plus available core-lambda-stubs, preferring declared Build Tools then latest installed. Android uses `-XDstringConcat=inline`. Platform preparation failure warns, closes prepared resources and uses a fresh host-JDK file manager without residual --system none. Ordinary source errors do not retry with a different platform; absent lambda stubs only omit that enhancement. JavaCompilerInvokerTest owns these contracts.

`FrameworkJarDetector` runs at context creation/model update on compile-only paths of modules with nonempty runtime dependencies. It recognizes SDK 37.2 top-level packages in directories/ZIPs, skips missing paths and locally warns on ZIP failure. Only already-present detected entries move before SDK on a source classpath; Java bootstrap/platform views keep the same order. There is no Kotlin diagnostic-triggered SDK-last retry. With no runtime metadata detection is skipped. RomApiCompileTest and opt-in demo romApi sources cover compilation; these synthetic APIs are not ordinary-device runtime fixtures.

Kotlin parser output can be deferred while still parsing diagnostics/artifacts and logging debug. `printDiagnostics()` explicitly prints deduplicated actual diagnostics, including Caused by; no greedy tag regex scans long untagged KAPT lines. A successful retry returns its outputs without first-attempt errors; a failed retry preserves first-attempt errors and no outputs. Shared APT summaries do not enumerate every CompileError; runner warnings preserve real Java/KAPT diagnostics, excluding empty knock-on entries.

Host JDK ≥25 defaults project Kotlin compilation to an isolated JVM; recreating the same in-process compiler cannot repair host environment conflicts. Keep the existing bounded retry budget for plugin/metadata/filesystem compatibility. `KotlinCompilerProcessDiagnostics` logs PID-linked start/exit/termination/output/flush phases. At minutes two and four it samples CPU and output read/forward progress; minute four additionally records host stacks and tries same-Java-home jcmd Thread.print once, with a five-second wait and 256 KiB cap. Ten seconds of stalled cleanup records host stacks, phase and known process survival. Missing/failed diagnostics preserve partial evidence without changing the five-minute compiler timeout. A termination request is not proof of process exit, unavailable CPU is not zero, and runner output-thread join still has no timeout: these diagnostics do not themselves fix a hang.

Kotlin/Java stage results must retain the preparation compileTask parent when merging, including generated Kotlin inputs, to satisfy CompileTask.plus identity. androidTest friend paths add only the direct same-root main owner whose variant matches ownerVariant + AndroidTest, retaining test baseline friends and excluding unrelated dependency internals.
