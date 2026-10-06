# Android instrumentation tests

> Last checked: 2026-10-07. Code is authoritative if this page drifts. For verification policy and targeted tests, see [06_testing.md](06_testing.md).

## Source map

| Boundary | Owner |
| --- | --- |
| Build scope and project model | `BuildTarget`, `JuggCompilerHelper`, `GradleProjectInfoReader`, `IdeaProjectModelSource`, `ModulePathMergePolicy` |
| Test APK identity and source selection | `ApkInfoReader`, `AndroidTestTargetResolver`, `AndroidTestApkSelector` |
| Missing library Test APK | `LibraryTestApkBackfillHelper`, `LibraryTestApkBuildHistory` |
| APK ownership and deployment | `ModuleApkBelongsUtils`, `JuggDeployData`, `AndroidTestPackageDeployPolicy`, `JuggDeployerHelper` |
| Run and results | `JuggAndroidTestRunConfiguration`, `JuggAndroidTestLineMarkerContributor`, `JuggConfigurationRunner`, `TestLauncher`, `InstrumentationSmRunnerBridge`, `AndroidTestLogAttributor` |

## Build scope, model, and baseline

### Run scope and full-build baseline

`BuildTarget.ANDROID_TEST` includes app and androidTest sources in compilation; `AndroidTestRunSpec` requests instrumentation **for this run**. An App Run configuration with `enableAndroidTest` can maintain both APK baselines and still launch the app normally when its run spec is null. The no-change direct-deploy path requires both `ANDROID_TEST` and a nonnull run spec; the target alone must not turn an ordinary App Run into a test run. A changed target or missing `full_build_info.json` requires a full Gradle build, so outputs from the other scope are not reused.

The Gradle init script adds the same-variant `assemble<Variant>AndroidTest` before the configured task and passes `-Pjugg.buildTarget=ANDROID_TEST`. APK lookup requires the app APK and its Test APK; missing optional library Test APK outputs do not fail the full build. The client derives the app Test APK path from the actual app output path, with a module build-directory fallback for custom output locations. It does not rewrite the saved Run configuration. Recent library backfill records can add their tasks to a later `ANDROID_TEST` full build: at most three same-variant records from the last 30 days. `APP` builds do not replay them.

### Synthetic modules and model merge

Gradle project info creates separate synthetic `.androidTest` `ModuleInfo` entries only for the androidTest target, including AndroidTest compile-classpath dependencies and an owner-module dependency. The test module has its own source roots and `<ownerVariant>AndroidTest` build paths. `ModuleInfo.isAndroidTestModule` depends on a valid `instrumentationTargetPackage`; the name suffix is only a model candidate.

Under `APP`, IDE and Gradle androidTest modules are excluded. Under `ANDROID_TEST`, IDE-only candidates can supply source roots before the Gradle snapshot merges, while nonempty Gradle test fields take precedence. IDE data must provide valid test and target package IDs; a saved Test APK manifest is not used to fill missing model fields.

On the first **local** target switch after a successful full build, `JuggCompilerHelper` immediately merges a localFetch model for the new target before deployment; this compensation is not performed for remote compilation.

### Source classpath and incremental limits

The synthetic test module's own test-variant aggregate R.jar precedes its owner's R.jar on the source classpath. This resolves fields from the full-build test-resource baseline, but incremental compilation of androidTest resources and `androidTestAnnotationProcessor`/`androidTestKapt` remains unsupported. With no changed or uncompiled files, an instrumentation rerun proceeds to deployment without another Kotlin/D8 compilation; pending staged outputs still participate if present.

## APK identity and ownership

`ApkInfoReader` identifies a Test APK from manifest `<instrumentation>` metadata: `instrumentationTargetPackage` makes `ApkInfo.isTestApk` true, and `instrumentationRunner` supplies the runner unless the run spec overrides it. Paths and file names are not identity evidence.

`sourcePath` is the deterministic anchor for a class or method run. `AndroidTestTargetResolver` requires an existing file under exactly one known androidTest source root, then resolves one matching Test APK. An absent source path uses the first Test APK and is suitable only when that ambiguity is acceptable. A missing or ambiguous model/APK match fails explicitly.

App-style other-targeting androidTest code belongs to the target app's runtime APK; a self-targeting library test module (`applicationId == instrumentationTargetPackage`) belongs to its own Test APK. An ordinary library can belong to both base and library Test APKs. `getAllBelongsApk()` and `targetApkPaths` carry that ownership from compilation through deploy-data generation and overlay updates; `getBelongsApk()` retains the single-APK view.

If a source-anchored self-targeting library run has no matching Test APK, `LibraryTestApkBackfillHelper` builds only that module's `:<module>:assemble<Variant>AndroidTest`. Other-targeting app Test APKs are not backfilled this way. The helper validates the resulting APK set against the source anchor, records the Gradle task/time/output pattern after a successful build and resolution, then installs the complete APK and updates the run's APK lists and overlay history. The history record does not mean installation succeeded. The Run balloon says `Library Test APK missing. Run Gradle compile once to build the test APK.` even though this branch attempts the build itself; inspect the subsequent Gradle and resolution result before asking for a separate manual build.

`JuggDeployData.groupByApplicationId()` applies `ApkInstallOrder.sortedForInstall()` so the app is installed before Test APKs, then `filterForApks()` scopes classes, overlays, and APK updates to each package before deployment. Package-scoped data must not commit global lifecycle state. The base APK and self-targeting library Test APK retain normal install/code-swap/full-swap policy. An other-targeting Test APK may be installed, but a non-INSTALL pass for its package is warned and skipped; its changed test code is routed through the app runtime.

**Deployment state and test result have separate commit points.** `am instrument` runs after Jugg deployment. If an assertion, abort, or instrumentation command fails after successful deployment, the run fails as a test, while deployed staging files, history, and overlay IDs remain advanced. A rerun must not recompile or reinstall solely because the test failed.

## Run entry points and result UI

The Android Test Run configuration exposes class and method scopes, an optional runner override, and instrumentation arguments. The Java/Kotlin gutter recognizes JUnit 4 and JUnit 5 tests under androidTest roots and creates a source-anchored class or method run. The entry point validates the selected class or method before deployment; the deploy stage resolves its module and APK from the same source file. With Android Test disabled, the gutter shows a notification rather than silently changing the App Run configuration. `AndroidTestRunSpec` travels through `JuggManager`, `JuggConfigurationRunner`, and `JuggRunningTask` to `DeployOptions`; ordinary App Run passes null. `testFilters` from rerun-failed takes precedence over the original class/method and preserves runner override and extra arguments. `InstrumentCommandBuilder` uses `am instrument -w -r`, with manifest runner or the default AndroidX runner when there is no override.

For CLI/Agent instrumentation, `jugg status` must report `data.enabledAndroidTest=true`, meaning the latest persisted full-build baseline uses the Android Test target. If false, enable Android Test in the App Run configuration and establish a full Android Test build before invoking `instrument`; the MCP action otherwise returns `INVALID_PARAMS`. `sourcePath` remains the target anchor for a Jugg instrument run. Once a source-anchored Jugg run has refreshed the APKs, a broader regression can use ordinary `adb shell am instrument`.

Android Test runs use the SM Test Runner console; ordinary App Runs retain the text console. One bridge serves the whole run, including multiple devices, and produces class/method nodes with Java source location hints. A single device shows tests directly; multiple devices show device suites and a result matrix. Jugg compile/deploy `info` and `warn` output remains in the Run output. Test Results receives instrumentation events and attributed method logcat; class and suite nodes carry no method logcat, while device details retain raw instrumentation and logcat. For a missing tree, compare `JuggAndroidTestConsoleProperties.TEST_FRAMEWORK_NAME` with the framework passed to `SMTestRunnerConnectionUtil.createAndAttachConsole()` in `JuggConfigurationRunner`; they must match. Rerun-failed creates `testFilters` without rewriting the configuration's class/method scope.

## Instrumentation and log diagnosis

`TestLauncher` runs devices serially. Any nonzero instrumentation exit, `INSTRUMENTATION_ABORTED`, failed/error/assumption-failed test, or device exception fails the overall run. `InstrumentationOutputParser` reads the `am instrument` protocol; it opens a method after both class and test statuses arrive, without waiting for status code 1. It does not parse logcat.

Each device captures its own `logcat -T <device-run-start> -v threadtime`. The start time comes from the **device** (`date '+%m-%d %H:%M:%S.000'`), falling back to host time only when that read fails; host/device skew can otherwise omit the run's logs. The Run output prints `Capturing logcat since ...`. Full device logs and raw instrumentation remain available in device details. Method output is bounded to 10,000 UTF-8 bytes per method; the instrumentation failure stack is separate from that limit.

Method ownership comes first from complete AndroidX `TestRunner: started/finished` markers and their PID, including logs that arrived before a protocol callback. Without complete markers, it uses the parser's method lifecycle window and filters by exact target/test package PID when available (`pidof`, then `ps -A`). If PID lookup fails, it retains the lifecycle time window. Unowned logcat stays at device level; no tag, message, or timestamp guess creates a method association. Logs already attributed to an active method survive an abort or device failure within the size bound. A missing method log is therefore distinct from a missing device log: compare capture start, TestRunner markers, lifecycle events, and PID resolution before diagnosing test execution.

## Capability limits and verification

The current path does not support incremental androidTest resources, test annotation processing/KAPT, app-style missing-Test-APK backfill, Debug executor, or a persistent in-process instrumentation harness. For verification evidence, test-value decisions, and targeted Gradle commands, follow [06_testing.md](06_testing.md); do not run the full `:main:test` or `:idea:test` suites.
