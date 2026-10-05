# androidTest Support Guide

> Last checked: 2026-08-29
> Consistency rule: when documentation conflicts with code, follow the code.

---

## 1. Capability Scope

Jugg currently supports **androidTest in app modules**, and has integrated **library-style self-targeting Test APKs** for exact sourcePath selection, lazy backfill of missing APKs, and multi-APK ownership deployment:

- When app RunConfig enables `enableAndroidTest`, compilation targets `BuildTarget.ANDROID_TEST`.
- A full Gradle compile produces both app APK and app test APK.
- Later changes to app sources and `app/src/androidTest` sources can enter Jugg incremental compilation.
- Deployment adds no Test-APK-specific protocol: it reuses `install / code swap / full swap` and splits scoped deploy data by applicationId.
- After successful deployment, run `am instrument` and render instrumentation output in Jugg console.
- androidTest Run Panel uses SM Test Runner for a `Test Results` tree, source navigation from test nodes, and rerun failed tests.
- If a library-style Test APK is missing, derive and run `:<module>:assemble<Variant>AndroidTest` only for the androidTest module matching current `sourcePath`, then add the new Test APK to this run's APK set.

Current exclusions:

- Incremental compilation of androidTest resources.
- `androidTestAnnotationProcessor` / `androidTestKapt`.
- Lazy backfill of app-style other-targeting Test APKs.
- Debug Executor.
- Persistent test harness or in-process redefine of a kept-alive test process.

---

## 2. Core Models

### 2.1 BuildTarget

Entry: `main/src/main/java/com/sickworm/intellij/jugg/compiler/BuildTarget.kt`

| Target | Compilation scope |
|--------|----------|
| `APP` | App variant. |
| `ANDROID_TEST` | App variant plus androidTest variant. |

`BuildTarget` describes compilation scope only; it does not choose how this run starts. When app RunConfig enables `enableAndroidTest`, even ordinary App Run maintains app and Test APK baselines under `BuildTarget.ANDROID_TEST`. Running `am instrument` depends on this run having a nonempty `AndroidTestRunSpec`; with none, launch app normally.

Thus a no-file-change androidTest direct-deploy branch requires both `BuildTarget.ANDROID_TEST` and an `AndroidTestRunSpec` this run. An ordinary App Run must not bypass no-change confirmation based only on `BuildTarget`.

### 2.2 Identifying Test APKs

Entries:

- `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkInfo.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkInfoReader.kt`

`ApkInfo` identifies a Test APK from the APK Manifest's `<instrumentation>` element:

| Field | Meaning |
|------|------|
| `instrumentationTargetPackage` | Package of the app under test; nonempty means Test APK. |
| `instrumentationRunner` | Runner declared in Test APK Manifest. |
| `isTestApk` | `instrumentationTargetPackage != null`. |

Downstream code should prefer `ApkInfo.isTestApk`, not guess from path or filename.

### 2.3 androidTest ModuleInfo

Entries:

- `main/src/main/java/com/sickworm/intellij/jugg/project/info/JuggProjectInfo.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/compiler/context/IdeaProjectModelSource.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/project/info/ProjectModelSource.kt`

androidTest uses a **separate synthetic ModuleInfo**, not merged into its owner:

| Field | Current convention |
|------|----------|
| `name` | `${ownerModuleName}.androidTest`. |
| `moduleType` | `ModuleInfo.Type.Library`. |
| `buildVariant` | `${ownerVariant}AndroidTest`. |
| `applicationId` | App androidTest uses Test APK applicationId; self-targeting library androidTest defaults to `${owner namespace}.test`. |
| `instrumentationTargetPackage` | App androidTest uses app applicationId; self-targeting library androidTest uses `${owner namespace}.test`, matching the produced self-targeting Test APK Manifest. |
| `sourceDirs` | Owner module's `src/androidTest` Java/Kotlin roots. |
| `moduleDependencies` | Owner module. |

Identify androidTest modules through `ModuleInfo.isAndroidTestModule`, meaning `instrumentationTargetPackage != null`. The `.androidTest` suffix is only a candidate for completing IDE module data, not final identity.

When Gradle project info is absent or lacks a synthetic androidTest module, `IdeaProjectModelSource#readProjectInfoFromIde()` handles each IDE `.androidTest` module during IDE project-info creation:

- `.androidTest` suffix only identifies an IDE-module creation candidate in `ModulePathMergePolicy`. Final identity is a completed nonnull `instrumentationTargetPackage`.
- `sourceDirs` comes from IDE module source roots and additionally includes androidTest IDE-module test-source-root types, supporting custom androidTest roots without hard-coded standard directories.
- Gradle and IDE synthetic `.androidTest` modules use `${ownerVariant}AndroidTest` for both `buildVariant` and `ModuleBuildPathInfo.buildVariant` (e.g. `debugAndroidTest` / `jooxDebugAndroidTest`), syncing test classpaths such as `build/tmp/kotlin-classes/<variant>AndroidTest` and `intermediates/javac/<variant>AndroidTest/classes` after a full build.
- Test and target packages come from IDE Android model exposed by `AsDeployerCompat#getIdeModuleInfo`. Chipmunk / Narwhal feature / Otter / Panda inheritance chain reads AndroidTest and main artifact applicationIds consistently. For a self-targeting library, fall back to `selectedBasicVariant.testApplicationId`, `androidProject.testNamespace`, or `${androidProject.namespace}.test`; target package uses test package.
- IDE project info no longer infers missing fields from a saved Test APK Manifest. Mark an IDE module as androidTest only when test and target packages are both valid; `uninitialized.application.id` is invalid.
- Under `BuildTarget.APP`, filter IDE `.androidTest` modules. Under `BuildTarget.ANDROID_TEST`, include IDE-only `.androidTest` in merge so source roots are retained when first switching target before Gradle snapshot merge finishes.
- Gradle nonempty test fields still take priority at merge, and Gradle-only androidTest modules are appended only for `BuildTarget.ANDROID_TEST`.

---

## 3. Compilation Path

### 3.1 Full Gradle Compile

Entries:

- `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriver.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/FullBuildInfo.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt`

Key invariants:

- Do not rewrite compile command or APK-output path in an existing RunConfig. IDE model build folder is only for new configurations.
- `BuildTarget.ANDROID_TEST` injects `-Pjugg.buildTarget=ANDROID_TEST` through Gradle init script and puts same-variant `assemble<Variant>AndroidTest` before the user-requested Gradle task.
- If `LibraryTestApkBuildHistory` finds recent self-targeting library Test APK records, Gradle compile passes historical task list via `-Pjugg.libraryTestTasks=...`. In the same `projectsEvaluated` phase, init script adds those library androidTest tasks before user-requested task. `BuildTarget.APP` does not participate.
- Gradle client first finds app APK by user configuration, then derives same-variant `<actual-build-dir>/outputs/apk/androidTest/<variant>/*.apk` from `/outputs/apk/` in actual app APK path. If app APK has a custom location under module `build` without `/outputs/apk/`, preserve existing lookup/storage and recursively search same module's `build/outputs/apk/androidTest/*.apk`, then `build/intermediates/apk/androidTest/*.apk`. History-based library Test APK outputs likewise derive from `ModuleBuildPathInfo.buildDir` as optional APKs: append hits to this run; log misses without adding to `failedApkPaths`.
- `full_build_info.json` records `FullBuildInfo{compileCommand, buildTarget, createdAt}`. A target switch or missing file triggers full Gradle compile to avoid reusing outputs from wrong app/test mode.
- During Gradle project-info read, only `-Pjugg.buildTarget=ANDROID_TEST` creates synthetic `.androidTest` ModuleInfo for Application and Library modules with androidTest source set. `APP`/absent property creates none. Synthetic module retains external `LibraryDependency` and project `ModuleDependency` from AndroidTest compile classpath, plus explicit owner dependency, so outputs from `androidTestImplementation(project(...))` enter incremental classpath. Merge and localFetch must use this run's `BuildTarget` explicitly, not infer it from old `FullBuildInfo`. After first switch from `APP` to `ANDROID_TEST` with successful local full Gradle compile, `JuggCompilerHelper` performs one localFetch merge for this target immediately before install/deploy; remote compile skips this compensation. Library uses `${namespace}.test` for self-targeting Test APK ownership, enabling subsequent missing-APK lazy backfill by `sourcePath`.

### 3.2 Incremental Compilation

Entries:

- `main/src/main/java/com/sickworm/intellij/jugg/compiler/context/CompileContextManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/project/info/ProjectModelSource.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongs.kt`

`CompileContextManager` filters:

- `BuildTarget.APP`: keep filtering `.androidTest` modules.
- `BuildTarget.ANDROID_TEST`: include `.androidTest` modules.
- Keep filtering `.test` / `.unitTest` in both targets.

For androidTest source compilation, use the synthetic module's own test-variant aggregate R.jar before owner module and other dependencies. This lets a self-targeting Library Android Test resolve existing test-resource fields in the full-build baseline without being shadowed by the Library main-variant R in the same namespace. It only supplies source classpath for existing test resources; incremental androidTest resource compilation remains unsupported.

Rerunning androidTest does not use ordinary App Run's no-change fallback semantics. On the next androidTest run with no changed files and no uncompiled files, `JuggCompilerHelper` returns incremental success straight into deployment. Reuse compiled/staging pending outputs if present; otherwise enter empty deployment/instrumentation without rerunning Kotlin/D8 or misclassifying rerun as Gradle fallback.

`ModuleApkBelongsUtils` returns wrapper `ModuleApkBelongs`. `getBelongsApk()` retains single-APK behavior; `getAllBelongsApk()` exposes multi-APK ownership. Route androidTest modules by runtime classloader ownership: app-style other-targeting androidTest runs in main-APK process named by `instrumentationTargetPackage`, so belongs to main APK. Self-targeting/library-style androidTest has `applicationId == instrumentationTargetPackage` and belongs to matching Test APK. With a self-targeting library Test APK, `getAllBelongsApk()` for an ordinary library module includes both base APK and library Test APK.

`CompileOutput.targetApkPaths` and `DeployItem.targetApkPaths` carry multi-APK ownership into deployment and include a real `apkPath` when present. Dex merge, resource APK, embedded APK update, and overlay update must prioritize target paths. The old `allTargetApkPaths` view has been removed.

---

## 4. Run Entry Points

### 4.1 IDE RunConfig and Gutter

Entries:

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfigurationOptions.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunConfiguration.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestLineMarkerContributor.kt`

Entry-point layers:

| Configuration | Responsibility |
|------|------|
| `JuggRunConfiguration.enableAndroidTest` | Controls whether app RunConfig permits androidTest compilation/running. |
| `JuggAndroidTestRunConfiguration` | General page mirrors Android Instrumented Tests: Module row, two Test scopes, dynamic fields, editable Instrumentation class, and existing Instrumentation arguments. |
| `JuggAndroidTestLineMarkerContributor` | Adds Jugg gutter to JUnit tests under `src/androidTest` and stores test-file path as `sourcePath`. |

`JuggAndroidTestRunConfiguration` supports two execution scopes:

| Scope | Configuration fields | Run mapping | Validation |
|-------|----------|----------|------|
| `CLASS` | `testClass` | Append `-e class <testClass>`. | testClass required. |
| `METHOD` | `testClass` + `testMethod` | Append `-e class <testClass>#<testMethod>`. | Both required. |

`sourcePath` anchors target resolution for test class/method, androidTest module, and Test APK. Package/regex are no longer target entries. If `instrumentationRunner` is empty, use Test APK Manifest runner/default runner; otherwise override with `<testPkg>/<instrumentationRunner>`.

Gutter defaults: a class gutter creates `sourcePath + CLASS + testClass`; a method gutter creates `sourcePath + METHOD + testClass/testMethod`. Rerun failed uses `AndroidTestRunSpec.testFilters` and does not write back into General-page scope.

Gutter constraints:

- Support test paths containing `/src/androidTest/`; library Test APK resolves later through `sourcePath`.
- Recognize `org.junit.Test` and `org.junit.jupiter.api.Test`.
- Support both Java and Kotlin PSI; determine marker by test-annotation owner, not one PSI type.
- With `enableAndroidTest` off, show a Notification directing user to enable it in app RunConfig, without changing configuration automatically.
- Do not log every `hasMarker=true/false` transition. `JuggAndroidTestLineMarkerContributor` emits actionable logs only at file-scan threshold, a single slow scan, deduplicated marker hit, gutter click execution, or configuration blocking, avoiding low-value ordinary PSI misses.

For Agent/CLI requests to run androidTest or instrumented unit tests, if `jugg status` reports `enabledAndroidTest=false`, stop before `instrument` and tell the user to open the Jugg App Run Configuration, enable Android Test / `enableAndroidTest`, run one full build / `gradle-build` for the AndroidTest full-build baseline, then check `status.data.enabledAndroidTest=true` before continuing. Calling `instrument` anyway returns MCP `INVALID_PARAMS` with the same instructions.

### 4.2 AndroidTestRunSpec Propagation

Entries:

- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/AndroidTestRunSpec.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/DeployOptions.kt`

A gutter invocation creates `AndroidTestRunSpec` through `JuggAndroidTestRunSpecFactory`, then passes it via `JuggManager.runTask(...)`, `JuggConfigurationRunner`, and `JuggRunningTask` into `DeployOptions.androidTestRunSpec`. Ordinary app Run has `androidTestRunSpec = null` and unchanged behavior.

### 4.3 Test Results UI

Entries:

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestConsoleProperties.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRerunFailedTestsAction.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationSmRunnerBridge.kt`

For androidTest, `JuggConfigurationRunner` creates an SM Test Runner console; ordinary app Run keeps the text console. SM Runner owns UI only, not Jugg compilation, deployment, or instrumentation execution.

UI event chain: `InstrumentationOutputParser` produces `InstrumentationEvent`; `InstrumentationSmRunnerBridge` converts it to TeamCity service messages; `SMTestRunnerConnectionUtil` drives Test Results tree.

Create one `InstrumentationSmRunnerBridge` per androidTest run. Multiple devices create sinks in device order but share one SM Runner session, so each device does not emit a separate `enteredTheMatrix` block.

SM Runner process output receives project-level Jugg `info/warn` logs like the text console, so compilation file lists, errors, and failure summaries remain visible in Run window. Test Results nodes show test material only through instrumentation service messages and method-level logcat.

Node conventions:

| Node | name | locationHint |
|------|------|--------------|
| Device suite | Display name | Empty. |
| Class suite | FQCN | `java:suite://FQCN`. |
| Method test | methodName | `java:test://FQCN/methodName`. |

Device-suite display:

- **One device:** hide device suite and show class/method nodes directly.
- **Multiple devices:** show device suites to group class/method nodes per device.
- **Display name:** `TestLauncher` prefers device brand/model and appends `API xx` for a readable Android Test device dimension.
- **Device detail:** right pane shows Serial, Name, API, and raw instrumentation log for that device.
- **Result matrix:** in multi-device runs, add a text matrix `Test | device1 | device2 ...` showing each test's `Pass / Fail / Ignored / Running / -` state by device.

`JuggAndroidTestConsoleProperties` uses IntelliJ `JavaTestLocator` for source navigation. `JuggAndroidTestRerunFailedTestsAction` converts failed leaf tests into `AndroidTestRunSpec.testFilters` and reruns, preserving original `runnerOverride` and `extraArgs`.

---

## 5. Deployment and Instrumentation

### 5.1 Deployment Policy

Entries:

- `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/instrument/LibraryTestApkBackfillHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/ApkInstallOrder.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt`

Group by `applicationId` during deployment; `ApkInstallOrder.sortedForInstall()` puts app APK before Test APK. Key differences:

- **Base APK:** retain full deployment policy (install / code swap / full swap) and JVMTI agent push/attach and compatibility checks.
- **App-style other-targeting Test APK:** incremental test code should take effect through main-APK overlay. If it reaches package deployment loop by non-INSTALL mode, warn and skip; do not force INSTALL.
- **Library-style self-targeting Test APK:** separate runtime package and install target; retain full deployment policy.
- **Multi-APK scoped data:** before each applicationId deployment, call `JuggDeployData.filterForApks(...)` to retain only classes, overlays, and updateApkFiles belonging to current APK set, preventing base/Test APK cross-deployment.
- **Instrumentation result separate from deployment state:** run `am instrument` after successful Jugg deployment. A failing instrumentation assertion makes this run a test failure, but deployment history, staging commit, and Direct Overlay ID advance with already successful deployment. Next androidTest rerun should neither recompile again nor reinstall because of stale overlay ID.

App-style `am instrument` runs test code in main APK process without a separate other-targeting Test APK process. Self-targeting library Test APK is its own runtime package and needs full deployment capability.

`LibraryTestApkBackfillHelper` fills a missing library-style self-targeting Test APK only if all conditions hold:

- `sourcePath` uniquely matches an androidTest `ModuleInfo`.
- Current APK list cannot resolve that module's Test APK.
- `module.applicationId == module.instrumentationTargetPackage`, indicating self-targeting/library-style Test APK.

On incremental deployment, after backfill succeeds, install the Gradle-produced Test APK once as a full APK and immediately merge its new overlay ID into deployment history, so subsequent dry deployment does not mistake the new library Test APK for cross-project state. Update deploy target, deployment-data database, and compile-context APK lists synchronously. On full install, backfill adds Test APK to this run's install list before final install `runTask`. The APK already includes latest source outputs this run and does not consume this run's Jugg incremental deploy items.

After Gradle compilation succeeds, Test APK path resolves, and `AndroidTestTargetResolver` validates the merged APK list, `LibraryTestApkBuildHistory` stores that library androidTest module's Gradle task, compile time, and APK-output pattern. It no longer records entire compile command or actual APK path and does not require final install success. Records live in `~/.jugg/library_test_build_records/{projectName}_hash{0:8}.json`, with hash from repo URL when Git exists, otherwise project absolute path. For ordinary `BuildTarget.ANDROID_TEST` Gradle-build history, select only the latest three same-variant records within 30 days for replay.
When this missing-APK branch is hit, Jugg displays the Run tool window balloon `Library Test APK missing. Run Gradle compile once to build the test APK.` to tell the user that one Gradle build is needed to establish the Test APK baseline.

### 5.2 am instrument

Entry points:

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/instrument/TestLauncher.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentCommandBuilder.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationOutputParser.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationConsoleRenderer.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationSmRunnerBridge.kt`

`InstrumentCommandBuilder` produces this command form:

```text
am instrument -w -r [-e class <testClass>[#<testMethod>][,<testClass>#<testMethod>...]] [-e <key> <value>]* <testPkg>/<runner>
```

When `AndroidTestRunSpec.sourcePath` is nonempty, the run entry point first uses the source file to validate the single or multiple classes and methods. The deployment stage then uses the file to resolve the precise androidTest module and Test APK. The app androidTest path without `sourcePath` still falls back to the first Test APK. When `AndroidTestRunSpec.testFilters` is nonempty, it takes priority and generates a comma-separated `-e class` argument for rerun failed. Otherwise, the command uses `testClass` / `testMethod`.

For a broad androidTest regression, first run `jugg instrument --source-path ...` once so Jugg compiles, deploys, and refreshes the target APK. After that succeeds, both app-source and androidTest-source changes are in their respective APKs. You can then run a broader class, package, or suite regression with ordinary `adb shell am instrument`; the Jugg CLI no longer needs `sourcePath` as a target anchor for that run.

### 5.3 Log capture and attribution

Entry points:

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/instrument/TestLauncher.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/instrument/AndroidTestLogAttributor.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/AndroidTestResultModel.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationSmRunnerBridge.kt`

AndroidTest log capture:

1. Before instrumentation on each device, `TestLauncher` reads the device-side `date '+%m-%d %H:%M:%S.000'` as the logcat starting point, in `MM-dd HH:mm:ss.SSS` format. It falls back to host time only if reading or formatting the device time fails.
2. Each device starts its own `logcat -T <deviceRunStartTime> -v threadtime` stream. The Run window and debug log both print `Capturing logcat since <deviceRunStartTime>` to verify this run's capture start.
3. `InstrumentationOutputParser` parses only the `am instrument` protocol and emits events such as `TestStarted`, `TestFinished`, and `Aborted`; it does not sample logcat. The `TestStarted` window opens as soon as both `class` and `test` statuses arrive. A later `STATUS_CODE: 1` serves only as compatibility confirmation, so logs emitted at the start of a method are not lost while waiting for code 1.
4. `TestLauncher` first writes every logcat line to `AndroidTestResultModel.recordLog(...)` as the full device-level log. That log supports device details and raw troubleshooting, and is not shown directly as method details. At capture end, it still prints run-level logcat buffer statistics in the debug log and releases its cache reference.
5. `AndroidTestLogAttributor` determines method windows and emits at most 10000 bytes of method-level logcat from each method start. Excess bytes are truncated directly; there is no general tag or message filter. The failure stack is appended by instrumentation events after logcat in the method details and does not count toward the 10000-byte limit. PIDs come from `pidof <targetPackage>` / `pidof <testPackage>`, with exact package-name matching in `ps -A` as fallback. If PID lookup fails, attribution falls back to the time window so method logs are not lost entirely.
6. `InstrumentationSmRunnerBridge` emits the short method-level log as SM Runner `testStdOut`, adding a trailing newline if needed. For a `testFailed` event, `message` is the first exception line and `details` contains only the remaining stack trace, avoiding a duplicate failure summary in IntelliJ's details pane. Class and suite nodes carry no logcat and retain an empty log view. Jugg compile and deployment logs remain in the Run output.

Attribution boundaries:

- Prefer AndroidX TestRunner `TestRunner: started/finished: method(class)` logcat markers as method boundaries, and include only logs from the marker's PID. This covers logcat that arrives before `InstrumentationEvent.TestStarted`, preventing lost method logs when instrumentation protocol callbacks lag.
- Without complete TestRunner markers, fall back to the `InstrumentationEvent.TestStarted(className, testName)` / `TestFinished(className, testName)` lifecycle window. `TestStarted` may be emitted before `INSTRUMENTATION_STATUS_CODE: 1`, but both `class` and `test` statuses must have arrived.
- Logcat outside a method goes only to device details. Global device noise whose PID does not belong to the current test process also goes only to device details, even during an active method window.
- Each device maintains its own active method; attribution state is not shared across devices.
- On `Aborted`, a nonzero instrumentation exit, or a device exception, logs already received within an active method window and its 10000-byte limit remain in the result model. Subsequent logs are not assigned by guesswork.
- Never infer the method from an application logcat tag, message, or timestamp. Only the instrumentation lifecycle or AndroidX TestRunner markers establish method ownership.

`logcat -T` is necessary because the device logcat buffer retains logs from older runs. Plain `logcat -v threadtime` could immediately emit those old logs after this run starts and incorrectly attach them to the first active method. `-T <deviceRunStartTime>` limits capture to this run. The timestamp must come from the device: host/device clock skew could otherwise filter out all of this run's logcat.

`TestLauncher` executes instrumentation serially on each device. The overall Run fails if any device has:

- A nonzero instrumentation-command exit.
- `INSTRUMENTATION_ABORTED`.
- A test result of `FAILURE` / `ERROR` / `ASSUMPTION_FAILURE`.
- An exception during device execution.

---

## 6. Test entry points

Do not run the full test suite. Prefer targeted regressions for androidTest support.

Search for tests by capability area instead of maintaining a static file list that drifts:

```bash
rg --files main/src/test idea/src/test | rg 'AndroidTest|Instrumentation|ApkInstallOrder|TestLauncher|RunSpec'
```

For a typical run, use the `rg` command above to locate the target test class, then replace the `--tests` argument:

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.<MainModuleTestClass>"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.<IdeaModuleTestClass>"
```

Compile when needed for verification:

```bash
./gradlew :idea:compileKotlin
```

---

## 7. Troubleshooting criteria

### 7.1 No gutter icon

Check first:

1. Is the file path under `/app/src/androidTest/`?
2. Does the test method or class have `org.junit.Test` / `org.junit.jupiter.api.Test`?
3. Is the file under an app or library module's `src/androidTest` source root?
4. For Kotlin files, can PSI identify the annotation owner? The current implementation supports both `getAnnotations()` and `getAnnotationEntries()`.

### 7.2 Clicking the gutter does not run the test

Check first:

1. Is `enableAndroidTest` enabled in the App RunConfig?
2. Has `BuildTarget.ANDROID_TEST` had one Gradle full compile?
3. Is `DeployOptions.androidTestRunSpec` nonempty?
4. Does `deployData.apks` contain a Test APK with `ApkInfo.isTestApk == true`?

### 7.3 Incremental changes do not reach the target APK

Check first:

1. Is the current `FullBuildInfo.buildTarget` `ANDROID_TEST`?
2. Does `CompileContextManager` include the `.androidTest` module?
3. Is `ModuleInfo.instrumentationTargetPackage` nonempty?
4. Does `ModuleApkBelongsUtils` route by classloader ownership: app-style other-targeting androidTest to the main APK and self-targeting androidTest to the Test APK?

### 7.4 Instrumentation fails

Check first:

1. Does the Test APK manifest specify the correct `instrumentationRunner`?
2. Does `InstrumentCommandBuilder` produce the correct `<testPkg>/<runner>`?
3. Did `InstrumentationOutputParser` parse `ABORTED`, `FAILURE`, `ERROR`, or `ASSUMPTION_FAILURE`?

### 7.5 Test Results tree or rerun failed is abnormal

Check first:

1. Did `JuggConfigurationRunner` receive nonempty `androidTestRunSpec`, `executor`, and `runProfile`?
2. Does `JuggAndroidTestConsoleProperties.TEST_FRAMEWORK_NAME` match the framework name in `SMTestRunnerConnectionUtil.createAndAttachConsole()`?
3. Did `InstrumentationSmRunnerBridge` emit `java:suite://FQCN` and `java:test://FQCN/method` location hints?
4. Are the rerun-failed `AndroidTestRunSpec.testFilters` nonempty, and does `InstrumentCommandBuilder` prioritize `testFilters`?
