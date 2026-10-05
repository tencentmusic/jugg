# Compilation System: Resource Chain (res/assets/arsc/Compose Resources)

> Last verified: 2026-09-01
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page covers resource-related incremental compilation: how `res/`, `assets/`, Compose Multiplatform resources, native libraries, and manifests become deployable overlays. It emphasizes APK-scoped compilation, aapt2 `inclink` state, Compose accessor/asset routing, DataBinding/ViewBinding output handoff, and resource-filtering boundaries.

For Manifest diffs, see `02_compile_manifest.md`; for release obfuscation, see `02_compile_obfuscation.md`; for detailed DataBinding policy, see `02_compile_databinding.md`; for deployment consumption of resource overlays, see `03_deploy_core.md`.

---

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|
| `ResourceOverlayCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceOverlayCompiler.kt` | Main resource coordinator; connects manifest, flat compile, and arsc link per APK-scoped task, then filters the final overlay |
| `ResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceCompiler.kt` | Compiles resource files or directories into `.flat`; first handles ViewBinding/DataBinding split XML and generated sources |
| `ArscCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ArscCompiler.kt` | Uses aapt2 `inclink` to load the current APK resource table and link `resources.arsc`, compiled resources, and `R.java` |
| `AssetOverlayCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AssetOverlayCompiler.kt` | Handles ordinary `Asset`, APK-root `ClasspathResource`, native libraries, and other non-res overlays |
| `ComposeResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/ComposeResourceCompiler.kt` | Selects legacy XML or modern CVR resource model from Gradle metadata, assembles complete resource context, and compiles generated Kotlin |
| `ComposeResourceGeneratorBridge` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/ComposeResourceGeneratorBridge.kt` | Isolates project Compose plugin JAR loading and invokes the official legacy or modern Kotlin generator according to API shape |
| `ComposeResourceScanner` / `ComposeValueResourceConverter` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/` | Scans legacy XML or modern drawable/font/value descriptions; the modern pipeline generates CVR version 0, and `files/` gets no accessor |
| `AndroidManifestCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestCompiler.kt` | Incremental Manifest merge; its output becomes `ArscCompiler` input |
| `DataBindingGenBaseClassesCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenBaseClassesCompiler.kt` | Generates ViewBinding/DataBinding base classes and split XML before layout resources enter aapt2 |
| `RJavaFixer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/RJavaFixer.kt` | Fixes aapt2-generated `R.java` for subsequent source compilation |
| `RDexForSubmoduleCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/RDexForSubmoduleCompiler.kt` | Derives package-renamed R.dex from the host's main R `*.dex` generated this run: ordinary modules use module namespace; temporary modules use external AAR R namespace |
| `StyleableFileGenerator` / `ResGuardMappingFileGenerator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/` | Supply styleable and resource-obfuscation mapping inputs for `inclink --load` |

---

## 3. Core Data Flow

| Data | Producer | Consumer | Key constraint |
|---|---|---|---|
| `CompileFile.Type.Resource` | Change scanner / upstream compilation task | `ResourceOverlayCompiler` | May represent one file or a directory; expand directories into their real resource-file sets |
| Split layout XML | `DataBindingGenBaseClassesCompiler` | `ResourceCompiler.aapt2Compile()` | The split XML replaces the original layout before aapt2 compile |
| Generated Java/Kotlin | `ResourceCompiler` | `SourceCompiler` | ViewBinding/DataBinding generated source is not deployed; it must return to source compilation |
| `.flat` | `ResourceCompiler` | `ArscCompiler` | Link input only, not directly deployed. Output directories are separated by resource root within a module so same-name files from multiple `res.srcDirs` cannot overwrite one another |
| Latest resource APK | `ArscCompiler.getResApk()` | aapt2 `inclink --load` | If this APK has deployed `resources.arsc`, combine deployed arsc and manifest into a temporary resource APK rather than linking again from the original APK's old table |
| `styleables.txt` | `StyleableFileGenerator` | aapt2 `inclink --load` | `resources.arsc` does not preserve styleable declarations; reconstruct them from R.jar / R.class of modules related to the target APK |
| `res-guard-mapping.txt` | `ResGuardMappingFileGenerator` | aapt2 `inclink --load` | In release/AabResGuard cases, map original resource names to obfuscated names used by the baseline APK; on generation failure, load without mapping best-effort |
| `resources.arsc` / compiled resources / manifest | `ArscCompiler` | Deployment-data conversion | `CompileOutput.apkPath` binds the current APK; multi-APK ownership must be retained |
| External AAR R namespace | `GradleProjectInfoReader` reads `android-symbol-with-package-name`, falling back to AAR Manifest `package` | `DependencyDiffResultHelper` -> Resource `CompileFile.extraInfo` | Prefer symbol; if both sources are absent and this run needs external R.dex, fail explicitly rather than guess a namespace |
| External-namespace `R*.dex` | `RDexForSubmoduleCompiler` -> `DexPackageRenamer` | Deployment-data conversion | Rename every main R `*.dex` from the host's current run; deduplicate by namespace and exclude the application main R package; keep temporary-module base-APK routing |
| `targetApkPaths` | `CompileOutput` / downstream deploy item | Deployment routing | Classes/dex can belong to multiple APKs; resource/manifest outputs remain APK-scoped |
| `CompileFile.Type.ComposeResource` | `FileChangesHandler` | `ComposeResourceCompiler` | `baseDir` is the matching default/custom Compose resource root, not module root |
| `CompileFile.Type.ClasspathResource` | `JuggCompiler` | `AssetOverlayCompiler` | Classpath resource that must retain its classpath-relative path and go to the APK root; currently used by legacy Compose resources |
| Compose task metadata | `GradleProjectInfoReader` / project info | `ComposeResourceCompiler` | Contains generator API shape, classpath, package, Res class name, public/content-hash flags, source-set directories, and asset-relative path. Validate task properties, not Kotlin/Compose version numbers |
| Prepared CVR + generated Kotlin | `ComposeResourceCompiler` | Generator bridge / Kotlin compiler | Accessor generation reads the full values/resource context from every known resource directory, not just changed files, so existing keys are not lost |
| Changed Compose resource file | `ComposeResourceCompiler` | `AssetOverlayCompiler` | Modern pipeline copies changed/new CVR, drawable, font, and `files/` into `assets/` as `Asset`. Legacy pipeline emits `ClasspathResource` and preserves APK-root classpath paths. Neither enters AAPT2 |

---

## 4. Core Call Chain

```text
JuggCompiler resource stage
  -> ResourceOverlayCompiler.splitApkAndCompile()
  -> BaseCompiler separates one module's input by owning APK using moduleBelongsApkMap
  -> AndroidManifestCompiler.doApkCompile() emits a manifest overlay only for a real manifest diff
  -> ResourceCompiler.doModuleCompile() handles layout split / generated source, then aapt2 compiles to .flat
  -> ArscCompiler.doApkCompile() runs loadTable for the current APK, then inclink on flat files and optional manifest
  -> ResourceOverlayCompiler.filterResources() removes extra aapt2 outputs and unchanged manifest that must not deploy
  -> emit resource overlay while handing generated Java/Kotlin to SourceCompiler
```

In a multi-APK case, the resource chain does not copy the same output to several APKs. `splitApkAndCompile()` calls `doApkCompile()` separately for each `ApkFileUnit`, because each APK may differ in resource table, package ID, manifest, and dynamic-feature dependencies.

### 4.1 `inclink` Baseline Loading and Incremental Contract

```text
ArscCompiler (independently per APK)
  -> choose resource baseline
     -> deployed resources.arsc exists: combine it with the current manifest into a temporary resource APK
     -> otherwise: use the Gradle baseline APK
  -> generate styleables.txt from R.jar / R.class
  -> generate AabResGuard mapping when needed
  -> create an aapt2 daemon and run inclink --load
  -> cache the loaded invoker
  -> subsequently pass only this run's flat files / manifest to inclink
```

Jugg does not reread all historical `.flat` files. It loads the final `resources.arsc` and compiled resources directly from an APK, reusing resource IDs already determined by Gradle and avoiding a full intermediate read/link on every run. The cost is stateful link context: when the invoker dies or load/link fails, release it and reload on the next round. A running process is not proof that its resource table is available.

Two kinds of side-path information are absent from the APK baseline:

- `resources.arsc` does not preserve aggregated `styleable` declarations. `StyleableFileGenerator` reads `R$styleable` from R.jar files or the Java classpath for modules related to the target APK, merges them, and supplies them through `--styleables` to `inclink --load`.
- AabResGuard changes resource names in the APK. `ResGuardMappingFileGenerator` converts the Gradle mapping into `inclink` input so new/changed XML references use the installed APK's obfuscated names.

The `inclink` resource table adds or overwrites incrementally; it does not remove old entries. Deleting a `res/` file creates no resource-removal data. Old IDs and contents in the installed APK or existing overlay remain accessible through `Resources`. Additional configurations such as `layout-v22` generated for high-API attributes cannot simply be deleted either. Even after removing a high-version attribute this run, emit the corresponding configuration to overwrite its old entry. This makes `inclink` appropriate for development-time increments, not a complete production resource linker. Use a full Gradle build to refresh the baseline only when old resources must actually disappear.

Deleting an ordinary `assets/` file also produces no removal overlay, so old files remain readable via `AssetManager` from the installed APK or existing overlay. On a resource/asset rename, only the new path is an add/modify input; the old path follows deletion semantics and remains. A full Gradle build makes the removal effective; deletion does not cause automatic fallback.

### 4.2 Compose Multiplatform Resource Flow

```text
FileChangesHandler
  -> matches ComposeResourceInfo.resourceDirectories
  -> ChangedFile(Type.ComposeResource, file, resourceDirectory, module)
JuggCompiler (before asset/resource/source)
  -> ComposeResourceCompiler
     -> legacy pipeline scans XML directly; modern pipeline converts all values XML to CVR
     -> scan values/drawable/font across all known source sets; files are assets only
     -> ComposeResourceGeneratorBridge invokes the official generator in the project plugin JAR by API shape
     -> sync generated Kotlin to the module Compose-generated-source path for IDE indexing, highlighting, and auto import
     -> compile generated source in one Kotlin invocation; the modern pipeline marks expect/actual common sources explicitly
  -> emit only changed CVR/drawable/font/files from this run as resource output
  -> JuggCompiler converts legacy output to ClasspathResource and keeps modern output as Asset
  -> AssetOverlayCompiler copies modern resources to overlays/assets and keeps legacy values/drawable/font at APK-root paths
  -> generated classes continue to source/dex
DeployDataPlanner
  -> identifies this run's ComposeResource compilation from DeployFileStateTracker.compiledFiles
  -> writes JuggDeployData.isComposeResourceCompiled; restart the app process after deployment
```

“Complete context” and “changed-only output” have distinct meanings: accessors must see every resource directory listed by the project snapshot, while the deployment overlay includes only files added or changed this run. Default/custom roots absent at configuration time are persisted and treated as empty directories during scanning, so the first newly created resource can still be detected. Compose assets do not pass through `ResourceOverlayCompiler`, `ResourceCompiler`, `ArscCompiler`, or AAPT2.

The Compose-resource restart decision uses compile inputs from this run, not an inference from final `CompileOutput.Type.Asset`. `compiledFiles` remains until the last device commits successfully, so normal deployment and retry reconstruction of `JuggDeployData` can both recover the flag; warm-up does not set it. Although modern resources live under `assets/**`, `AssetManager` / Compose runtime may cache previously read contents, so an Activity restart does not guarantee freshness. Like legacy APK-root resources, they require a process restart.

The Compose-generated-source path is derived directly from the module build directory by `ModuleBuildPathInfo.composeResourceGeneratedSourcePath`, rather than stored in Gradle project info. After generating accessors, Jugg overwrites that directory so Android Studio can index new resources, highlight them, and suggest auto imports. A sync failure sacrifices only the IDE enhancement; it does not invalidate generated incremental compilation outputs. When monitoring or effect propagation reports a path under these build directories, `FileChangesHandler` filters it before type recognition. Thus this run's Compose-resource compilation uses accessor classes generated by `ComposeResourceCompiler` rather than compiling same-name Kotlin source from Gradle build output again.

---

## 5. Hidden Constraints / Design Rationale / Known Boundaries

- `ArscCompiler` caches one `Aapt2DaemonInvoker` per APK. If the invoker dies or link fails, it releases the invoker and runs `loadTable` again next round.
- `Aapt2DaemonInvoker` writes a structured argument list into the daemon protocol, one argument per line, allowing spaces in APK, resource, and output paths.
- On `loadTable()` failure, immediately release the invoker and return failure. Never cache a daemon without a loaded resource table; a later inclink could otherwise degrade to `no cache data found`.
- Deleting Android resources does not remove old IDs from `resources.arsc`; old resources remain readable. Use a full Gradle build to refresh a complete table only when the removal must take effect.
- Compatibility configurations for high-API attributes must overwrite the old baseline. Do not decide whether to emit extra configurations solely from whether the current XML still contains a high-version attribute.
- Styleables and ResGuard mapping are best-effort auxiliary inputs to `loadTable()`. Generation failure does not stop loading, but a new styleable or release resource reference may fail later at compile/runtime. A running aapt2 daemon alone is not proof these inputs were valid.
- Dynamic-feature compilation depends on the base APK. After base arsc changes, `ArscCompiler` adds this run's base flat files to feature link inputs so resource IDs stay aligned.
- `getResApk()` prefers a temporary resource APK made from deployed `resources.arsc` and manifest. Looking only at the original APK misses prior Jugg resource increments.
- `ResourceOverlayCompiler.filterResources()` removes root `Manifest.java` and, when manifest has no real change, root `AndroidManifest.xml` to avoid APK repackaging.
- aapt2 may emit several configuration-directory outputs for one resource. If an extra output's override XML already exists, filtering removes that extra output so it cannot overwrite a user-declared resource.
- `ResourceCompiler` groups both file and directory inputs by resource root: a file uses `CompileFile.baseDir`, while a directory uses its own path. Each root gets a child output directory named by MD5 of its normalized absolute path. This compiles only real inputs from this run while preventing same-name files such as `values/strings.xml` in multiple `res.srcDirs` of one module from overwriting the same flat file.
- Asset/resource changes first observed during a full Gradle build remain queued for the next incremental run according to when Jugg receives the event, not the file's own `lastModified`. Copy tools can preserve an old timestamp even though the corresponding Gradle merge task finished before the file appeared.
- DataBinding mapper generation does not finish in the resource stage. That stage handles only base classes / split XML; `SourceCompiler` handles Mapper before source compilation.
- An external AAR's `classes.jar` does not contain its R class; the host resource build flow supplies it. On an external AAR resource change, `RDexForSubmoduleCompiler` reads `r_package_name` recorded in the resource change by `DependencyDiffResultHelper` and derives `R*.dex` for that namespace from the host's current main R `*.dex`. It emits one set per namespace and does not duplicate the application main R package. If the namespace cannot be resolved at all, throw a compile exception naming the dependency and ask for a full Gradle build; do not guess or silently succeed.
- External AAR R namespace moves only as dependency metadata (`LibraryDependency.rPackageName`), not in dependency file sets or CRC diffs. Filling that field in an old project-info cache is therefore not mistaken for a dependency update. If the symbol artifact is unavailable, fall back to AAR Manifest `package`; symbol wins if they conflict.
- Jugg performs Compose preparation rather than Gradle Compose-resource tasks. Kotlin generation calls the official generator API in the project's Compose plugin JAR. It currently supports the legacy single-task API and the modern converter/accessor/collector API; missing APIs return a structured unsupported reason.
- Legacy Android runtime loads classpath resources such as `values/...` and `drawable/...` from the APK root. Incremental overlays must use explicit `CompileFile.Type.ClasspathResource` to retain those same root paths; ordinary Android asset's `assets/` prefix is wrong. Modern Compose resources continue to use `CompileFile.Type.Asset` and the asset-relative path supplied by Gradle metadata.
- Compose-resource compilation restarts the process only when this run has nonempty deployment data. An ordinary Android asset does not automatically require an app restart merely because it lives under `assets/**`.
- The modern pipeline supports string, string-array, plurals, drawable, and font, passing through the Res class name and content hash. The legacy pipeline supports string, drawable, and font according to upstream capability. `files/` is copied as an asset but has no typed accessor.
- A deleted Compose-resource file is ignored by the current incremental entry because a nonexistent file cannot reveal resource type or `baseDir`. This run emits no removal data for accessor, asset, classpath resource, or old class; existing outputs remain. Run a full Gradle build only when deletion must take effect. There is currently no deletion graph, generated-source/cache reuse, or complete source-set dependency graph.

### 5.1 Test Placement

- L1: `ComposeValueResourceConverterTest`, `ComposeResourceScannerTest`, and `ComposeResourceGeneratorBridgeTest` cover CVR/scanning results, missing roots, diagnostic remapping, source-set identity, and official golden Kotlin output.
- L1 (external AAR R namespace): `RDexForSubmoduleCompilerTest` covers generation for several namespaces, same-namespace deduplication, exclusion of application namespace, explicit failure for missing namespace, and target-APK regression for ordinary feature/project modules. `DependencyDiffResultTest` covers symbol preference, Manifest fallback, null when both are missing, and no false dependency update when metadata is completed.
- L2 (external AAR R namespace): `JuggCompilerTest.external AAR resource update generates library namespace R dex` checks that staging contains `R.dex` / `R$string.dex` under the external namespace after an AAR resource change and that `R$string` carries the newly added field.
- L2: `FileChangesHandlerTest` checks mapping of default/custom/unsupported/first-created directories to `ComposeResource` with the correct `baseDir`, and filters file/directory events from traditional/centralized build directories. `KmpComposeFlowReproTest` checks real Gradle metadata, compilation, D8, staging, and generated-accessor writeback for corresponding Compose generators in Kotlin 1.9/2.1/2.3, including no duplicate class when both a resource and Gradle-generated accessor are reported in one run. The Kotlin 1.7 demo profile remains for non-Compose-Multiplatform regression and explicitly excludes `kmpCompose`.
- L3: `KmpComposeDeployFlowTest` uses a real demo full install, baseline resource-cache warm-up, resource-only incremental compile/deploy/run, and logcat on a representative Compose profile to verify accessor consumption after process restart, target APK, and absence of incremental Gradle Compose tasks. L2 covers the multi-version artifact-path matrix instead of duplicating it in L3.

### 5.2 Android Studio E2E Verification Criteria

Android Studio E2E should establish three layers of evidence: first Jugg Run completes a Gradle baseline; adding a resource key makes Jugg generate and compile an accessor incrementally; then changing only the value makes runtime read the new content. Before verifying the value update, read that resource in the baseline process to form a cache. A nonempty Compose-resource increment restarts the app process after overlay completion; `Always restart app after deployment` need not be enabled. Version 1.9 uses `src/commonMain/composeResources`; 2.1/2.3 use `composeResourcesExtended` and additionally cover `src/androidMain/customComposeResources`. Gradle Sync is required after each profile switch; restore 1.9 at the end. In automation, `KmpComposeFlowReproTest` (L2) owns the 1.9/2.1/2.3 compilation and artifact-path matrix, while `KmpComposeDeployFlowTest` (L3) checks the real runtime flow of a representative profile only.

---
## 6. Investigation Entry Points

| Symptom | First entry point |
|---|---|
| aapt2 compile failure | `ResourceCompiler.aapt2Compile()`: inspect the `compile --legacy` command and whether flat output exists |
| aapt2 link / arsc failure | `ArscCompiler.doApkCompile()` and `incLinkCompile()`: inspect `loadTable`, `inclink` errorOutput, and whether the invoker was recreated |
| `multiply apk load not supported` | Check for a caller still splitting an entire command on spaces; every path argument must be a separate element of `Aapt2DaemonInvoker.invoke(List<String>)` |
| `no cache data found, run with --load first` | First find `loadTable failed` for the same invoker; a failed instance must not enter `aapt2InvokerMap` |
| Wrong dynamic-feature resource ID | Inspect `ArscCompiler.isBaseApkArscUpdate` / `baseApkUpdateFlatFiles` and confirm that base updates enter the feature link |
| ID conflict or missing attribute after adding a styleable | Inspect `StyleableFileGenerator` and `--styleables` input to `ArscCompiler.loadTable()` |
| Release/AabResGuard resource references still use original names | `ResGuardMappingFileGenerator` and `AabResGuardHandler.writeAapt2IncLinkMappingFile()` |
| Old content remains readable after deleting a `res/` or `assets/` file | Deletion emits no removal data; this is the expected incremental result. Refresh the baseline with a full Gradle build only when old content must disappear |
| Old entry remains after removing a high-API attribute | `inclink` overwrites incrementally; check whether an additional output was generated to overwrite the old configuration |
| Resource overlay goes to the wrong APK | `BaseCompiler.splitApkAndCompile()` and `CompileOutput.apkPath`: confirm module-to-APK ownership and output apkPath |
| Unchanged manifest triggers repackaging | `ResourceOverlayCompiler.filterResources(...)`: check that root manifest is filtered when `isNeedOutputManifest=false` |
| Layout-generated source does not enter source compilation | `ResourceCompiler.processViewBinding()` and `SourceCompiler.prepareSourceCompile()` |
| R-reference error | `R.java` generated by `ArscCompiler.incLinkCompile()` and `RJavaFixer.fixIfNeeded()` |
| Runtime `NoSuchFieldError` / knock-on `NoClassDefFoundError` after adding a resource in an external AAR | `DependencyDiffResultHelper.resolveRPackageName()` and `RDexForSubmoduleCompiler.doTempModuleCompile()`: confirm that the resource change carries `r_package_name`, the host main R.dex changed this run, and external-namespace `R*.dex` was generated with the new field |
| Incremental compilation reports “Can not resolve R package name for external dependencies” | Both symbol artifact and AAR Manifest `package` are unavailable; follow the prompt to run a full Gradle build and refresh the baseline |

---

## 7. Related Documents

- Incremental Manifest merge: `02_compile_manifest.md`
- Obfuscation mapping: `02_compile_obfuscation.md`
- Source compilation: `02_compile_source.md`
- DataBinding: `02_compile_databinding.md`
- Deployment core: `03_deploy_core.md`
