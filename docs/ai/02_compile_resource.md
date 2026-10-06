# Compilation System: Resource and Asset Chain

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page follows Android `res/`, assets, Compose Multiplatform resources, and external AAR R classes to their compilation and deployment handoffs. It explains APK ownership, aapt2 `inclink` state, and resource-specific diagnosis. See `02_compile_core.md` for Run decisions; `02_compile_manifest.md`, `02_compile_databinding.md`, and `02_compile_obfuscation.md` own their respective internals.

## 2. Core Source Index

| Owner | Location | Responsibility |
|---|---|---|
| `ResourceOverlayCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceOverlayCompiler.kt` | Coordinates Manifest, `.flat`, and arsc work for each owning APK; filters final overlays |
| `ResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceCompiler.kt` | Expands resource inputs, hands layouts through binding generation, and produces `.flat` files |
| `ArscCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ArscCompiler.kt` | Loads each APK's resource baseline into aapt2 and links new flats/Manifest to overlays and `R.java` |
| `AssetOverlayCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AssetOverlayCompiler.kt` | Routes ordinary assets, APK-root classpath resources, and native libraries into overlays |
| `ComposeResourceCompiler`, `ComposeResourceGeneratorBridge` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/` | Assemble complete Compose resource context, call the project's official generator, and compile accessors |
| `RDexForSubmoduleCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/RDexForSubmoduleCompiler.kt` | Creates APK-owned module or external-AAR-namespace `R*.dex` from this Run's host R output |
| `FileChangesHandler` | `main/src/main/java/com/sickworm/intellij/jugg/project/change/FileChangesHandler.kt` | Classifies resource roots and excludes build-generated files before compilation |

## 3. Data Handoffs

| Producer → consumer | Contract |
|---|---|
| `FileChangesHandler` → `ResourceOverlayCompiler` | `Resource` may be a file or directory; directory inputs expand into actual files before link. `.flat` outputs from different resource roots must remain distinct when passed to `ArscCompiler`. |
| `DataBindingGenBaseClassesCompiler` → `ResourceCompiler` → `SourceCompiler` | Binding split XML replaces the original layout before aapt2; generated Java/Kotlin goes to source compilation. Mapper generation occurs in `SourceCompiler`, after the resource stage. |
| `ResourceCompiler` → `ArscCompiler` | `.flat` files are link inputs only. `ArscCompiler` emits APK-scoped `resources.arsc`/compiled resources and `R.java`; `JuggCompiler` compiles R and stages final overlays. |
| `GradleProjectInfoReader` → `DependencyDiffResultHelper` → `RDexForSubmoduleCompiler` | External AAR R namespace comes from `android-symbol-with-package-name`, falling back to AAR Manifest `package`; it travels with the changed resource. No namespace must yield an explicit compile failure. |
| `ComposeResourceCompiler` → `JuggCompiler` → `AssetOverlayCompiler` | Modern Compose outputs are `Asset` under `assets/`; legacy classpath resources remain at APK-root paths via `ClasspathResource`. Neither enters Android `res/`/aapt2. Generated classes continue to source/dex. |
| `DeployFileStateTracker` → `DeployDataPlanner` → deployment | This Run's compiled Compose-resource input, not an arbitrary `Asset` output, sets `isComposeResourceCompiled`. A nonempty deployment then requests a process restart. |

## 4. Android Resource Link Flow

```text
JuggCompiler.doCompile() resource stage
  -> ResourceOverlayCompiler.doCompile() -> BaseCompiler.splitApkAndCompile()
     -> ResourceOverlayCompiler.doApkCompile(): one ApkFileUnit at a time
     -> AndroidManifestCompiler.doApkCompile(): emit Manifest only on a real diff
     -> ResourceCompiler.doModuleCompile(): binding split/generated sources -> aapt2 .flat
     -> ArscCompiler.doApkCompile(): load current APK resource table -> incLinkCompile() on new .flat/Manifest
     -> ResourceOverlayCompiler.filterResources(): remove non-deployable/generated extras
  -> move APK-scoped resource outputs to staging overlays
  -> compile R.java, route R.dex, pass binding sources to SourceCompiler
```

One module can belong to several APKs, but resource outputs cannot be copied blindly between them: resource table, package ID, Manifest, and feature dependencies can differ. `splitApkAndCompile()` invokes the APK-specific path for each `ApkFileUnit` and preserves `CompileOutput.apkPath` through staging. When a base APK's arsc changes, the feature link also receives this Run's base flats to keep resource IDs aligned.

The binding stage runs before aapt2 so split layouts replace the originals; generated binding source returns to the source stage. `ResourceOverlayCompiler` removes root `Manifest.java`, an unchanged root `AndroidManifest.xml` that would trigger APK repackaging, and aapt2-created extra configurations only when an explicit override XML would otherwise be overwritten.

### 4.1 `inclink` baseline and daemon state

```text
ArscCompiler.doApkCompile(), per APK
  -> deployed resources.arsc exists: combine it with the current Manifest as a temporary resource APK
     otherwise: use the Gradle baseline APK
  -> reconstruct styleables and optional AabResGuard mapping missing from that APK table
  -> inclink --load into a new daemon; cache only after successful load
  -> link only this Run's .flat files and optional Manifest
  -> failed/dead invoker: release; load again on the next compile call
```

The APK baseline contains final compiled resources and IDs, so Jugg need not replay historical `.flat` files. Loading only the original Gradle APK after earlier Jugg increments would discard the latest deployed table. The aapt2 process and its loaded table are distinct states: `loadTable failed` proves this invoker has no usable loaded baseline, and `no cache data found, run with --load first` should prompt a check of the same invoker's load result, not a conclusion from process liveness alone.

`resources.arsc` omits aggregated `styleable` declarations. `StyleableFileGenerator` reconstructs them from APK-related R.jar/R.class inputs; `ResGuardMappingFileGenerator` supplies release name mapping so newly compiled XML refers to baseline obfuscated resource names. These are best-effort auxiliary inputs to `--load`: failure to generate one does not prove the link failed, but a later styleable/reference problem requires checking the actual generated input, not merely a running daemon. aapt2 receives a list of arguments, one protocol line per argument; a path with spaces must remain one list element.

`inclink` adds or overwrites entries; it does not remove old IDs. Deleting a `res/` file produces no resource-removal record, and prior installed/overlay content can remain accessible. Removing a high-API attribute may still require a generated compatibility configuration (such as `layout-v22`) to overwrite its old value. Ordinary `assets/` deletions likewise produce no removal overlay. A rename adds the new path but does not erase the old one. Use a full Gradle build when removal must take effect; deletion alone does not force fallback.

## 5. Compose Multiplatform Resources

```text
Gradle project info retains generator API shape, classpath, source-set roots and asset path
  -> FileChangesHandler emits ComposeResource with its matched resource root as baseDir
  -> ComposeResourceCompiler reads all known roots for accessor context
     -> legacy: scan XML; modern: convert values XML to CVR, scan values/drawable/font
     -> ComposeResourceGeneratorBridge calls the official project plugin generator
     -> write generated Kotlin to ModuleBuildPathInfo.composeResourceGeneratedSourcePath for IDE indexing
     -> compile generated expect/actual sources and emit changed-only resource assets
  -> JuggCompiler routes legacy APK-root or modern assets and generated classes
  -> DeployDataPlanner marks a nonempty Compose-resource deployment for process restart
```

Generator support is decided by the API shape captured from Gradle tasks, not an exact Kotlin/Compose version allowlist. The snapshot retains detected-but-unsupported roots and a reason: a changed resource must fail visibly rather than disappear because `composeResourceInfo` is null. The legacy generator supports its upstream string/drawable/font forms; the modern path supports string, string-array, plurals, drawable, and font, including Res class name/content-hash settings. `files/` is copied as an asset without a typed accessor.

Accessor generation reads all configured source-set directories, while overlay output contains only changed files from this Run. A configured root absent on disk is treated as empty, allowing its first new resource to be noticed. The modern generator's expect/actual files compile in one Kotlin invocation with common sources marked explicitly. Modern values become CVR; drawable/font/`files/` keep their asset role. Legacy `values/...` and `drawable/...` must retain classpath-relative APK-root paths, because moving them under `assets/` would change runtime lookup. Android aapt2 never processes these Compose resources.

The generated-source destination comes from the module build directory, not a persisted Gradle path. Syncing generated Kotlin there supports IDE indexing; a failed sync loses that IDE enhancement without invalidating the already generated incremental compile outputs. `FileChangesHandler` filters build-directory events, so the IDE copy is not compiled again as another input. For a same-root virtual KMP source-set module, `ComposeResourceCompiler` invokes the Android owner and identifies common source sets from that root's virtual-module metadata; `androidMain` remains platform-only. The deployment flag comes from compiled inputs retained for device retry, excluding warm-up. Compose runtime/`AssetManager` can cache a value before deployment, so an Activity restart may still show old content; a nonempty Compose-resource deployment requests an app-process restart. An ordinary Android asset does not acquire that rule solely by living under `assets/**`.

A deleted Compose resource is not an incremental input: no accessor, asset, APK-root resource, or old class removal is emitted. The old result remains until a full Gradle baseline is built. Do not infer successful deletion from a green zero-file compile. The current path has no deletion graph or complete source-set dependency graph.

## 6. External AAR R Namespace

An AAR `classes.jar` normally lacks its R class. When an external AAR resource changes, `DependencyDiffResultHelper` attaches the library R package to the resource input. `RDexForSubmoduleCompiler` copies this Run's host main `R*.dex` into each required external namespace, deduplicates namespaces, excludes the application main package, and keeps the temporary module on the base APK. If both symbol artifact and AAR Manifest package are unavailable, it fails with the dependency name rather than guessing. Completing `LibraryDependency.rPackageName` in an older project-info snapshot does not itself count as a dependency file/CRC change.

## 7. Diagnostic Boundaries

| Observation | What it establishes | Next discriminating evidence |
|---|---|---|
| `aapt2 compile failed` | A `.flat` input failed or its expected artifact is absent | `ResourceCompiler.aapt2Compile()` input root, `compile --legacy` result, flat file |
| `aapt2 link failed` | The APK-specific overlay link failed; it does not identify load, table, or input as cause | `ArscCompiler.loadTable()`/`incLinkCompile()` result for the same APK and invoker |
| `multiply apk load not supported` | aapt2 interpreted more than one APK load argument | Inspect the caller's argument list; do not split a whole command string on spaces |
| Wrong feature ID or missing styleable | Feature/base table or reconstructed styleables may be inconsistent | Base flats passed to feature link; `--styleables` input and R.jar/R.class providers |
| New release XML still refers to old names | Mapping may be missing/incomplete, but daemon liveness proves nothing about it | Generated ResGuard mapping and `--load` arguments |
| Compose accessor Kotlin compilation fails and every changed resource shows the same diagnostic | `mapGeneratedCompileFailure()` copies the generated-source diagnostic line/text to each resource input; its line is not a location in the resource file | Generated Kotlin file and original compiler diagnostic, then the resource key/source-set that produced it |
| Old `res/` or asset content remains after deletion | Incremental output contains no removal record | Confirm deleted path and baseline/overlay contents before attributing it to a link failure |
| `NoSuchFieldError` after external AAR resource change | Runtime R class lacks a field; it does not alone prove the namespace producer failed | `r_package_name` on the resource input, host R dex from this Run, external-namespace `R*.dex` staged for the right APK |
| `Can not resolve R package name for external dependencies` | Neither supported namespace source was available to this compile | Symbol artifact and AAR Manifest `package`; refresh with a full Gradle baseline |

Check counter-evidence before concluding a resource issue: the owning APK, current deployed arsc, generated auxiliary inputs, and actual staging outputs can disagree with a surface error. For runtime log scope, see `09_plugin_runtime_debug.md`; for verification ownership and Compose cache-priming criteria, see `06_testing.md`.

## 8. Related Documents

- Compilation control and deletion semantics: `02_compile_core.md`
- Manifest, binding, and obfuscation: `02_compile_manifest.md`, `02_compile_databinding.md`, `02_compile_obfuscation.md`
- Deployment of resource overlays: `03_deploy_core.md`
