# Compilation System: Source Compilation Chain (Java/Kotlin/Dex)

> Last verified: 2026-10-07
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

`SourceCompiler` records generated files before language compilation so a failed run does not make them disappear from the next changed-file set. A JuggApt processor exception warns and leaves the main compilation available. A retry removes its changed-file registration only when a *direct source diagnostic* identifies a JuggApt artifact produced in this run. Generic batch failures do not prove that cause. When Kotlin fails, Java inputs are marked skipped because compiling them without current Kotlin classes would give misleading results.

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

`GradleProjectInfoReaderManager` resolves the R8 actually loaded by the project's AGP; a Gradle instrumentation-cache code source must map back to its original buildscript artifact. `DexFileMaker` isolates that D8 from Jugg's bundled R8 and caches it by canonical path. Missing/unsafe path, load/API incompatibility, or execution failure falls back to bundled R8. The external failure is a user-visible warning; debug logs retain its exception and classpath. If bundled D8 fails too, its exception is final.

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
| Kotlin reports `cannot access` / `unresolved supertypes` for a framework-JAR superclass after SDK `android.jar` | The direct supertype diagnostic may qualify for one classpath-order retry; a direct `unresolved reference` does not. This Kotlin classifier-path failure does not imply javac has the same problem | `AndroidJarClasspathRetry`, first `android.jar`, actual framework JAR and first diagnostic |

The SDK-`android.jar` retry moves only its first occurrence behind a later framework JAR, shares the invoker's single retry budget, and remembers successful source files for the current compile context. On failed retry the original diagnostic wins. For a project plugin's `unsupported plugin option`, the invoker removes only that plugin ID's Gradle-resolved arguments and caches a successful fallback by toolchain and original arguments. A `required plugin option not present` retry disables an identified plugin only for later invocations; an unidentified plugin keeps the failure. User-supplied free compiler arguments are outside these fallbacks.

For host-JDK failures, `KotlinCompilerHostCompat` presets the shaded Java version only after its probe fails; on JDK 25 or newer with `android.jar` in the classpath, the invoker adds `-no-jdk`. A complete IDE file-system-close signature is keyed by the project compiler classpath and retries once in an isolated process. That process cannot update the expect/actual tracker cache; its UTF-8 argument file avoids overflowing the source/plugin argument list, while the Java launcher's compiler classpath remains a separate command-line input.

## 7. Related Documents

- Stage scheduling and fallback: `02_compile_core.md`
- Resource and Compose source generation: `02_compile_resource.md`
- DataBinding: `02_compile_databinding.md`
- Release mapping: `02_compile_obfuscation.md`
- Verification owners and test-value gate: `06_testing.md`; ROM hidden-API and Kotlin opt-in regressions are in §6.5
- Runtime evidence interpretation: `09_plugin_runtime_debug.md`
