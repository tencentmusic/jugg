# Testing and Verification Strategy (Authoritative Rules)

> Last checked: 2026-10-07
> If this page conflicts with current code, follow the code. This page is the sole current knowledge-base authority for verification evidence, test value, TDD, L0–L3 layers, test placement, and existing-test maintenance; historical `docs/task/YYYY-MM/` plans are context, not policy.

## 0. Scope and decision order

Every development task needs evidence proportional to its risk. An automated test is one possible form of evidence, not a requirement for every change. Decide in this order:

1. State the failure or regression risk and the result that must be proved.
2. Apply the **test-value gate** before adding or retaining a test. It takes precedence over formal TDD, layer rules, reuse of an existing test file, and coverage targets.
3. If a valuable automated assertion exists, identify its behavior owner, obtain a failing test for a feature/bug fix, then change production code and run the targeted regression.
4. Otherwise preserve the failure reproduction, explain why automation is unsuitable, and verify at the real boundary with compilation, artifact/bytecode inspection, logs, or a manual IDE/device matrix. “No new test” never means “no verification.”
5. Compare the final result with the original failure and an unaffected normal path.

JOOX Android work follows its separate no-unit-test rule. Do not use a Jugg test policy to override it.

## 1. Evidence and test value

| Evidence | Appropriate claim |
|---|---|
| Targeted automated test | Stable observable behavior has a deterministic regression decision. |
| Targeted compilation or static analysis | API wiring, dependency direction, and type compatibility. |
| Build/artifact or bytecode inspection | Generated script, plugin ZIP, APK/DEX, or binary compatibility actually contains the expected result. |
| Logs plus stable reproduction | External IDE, device, process, or toolchain failure and its trigger. |
| L3 Flow or documented real-environment matrix | Run → compile → deploy and other user-visible end-to-end results. |

Compilation does not prove runtime behavior. Source-string checks do not prove user behavior. Logs may establish the observed branch without proving the final user outcome.

A test passes the gate when it protects an independent, stable contract that a real change could break; a contract break would fail a deterministic, adjudicable assertion; it has a clear owner without duplicating another test at the same granularity; and it asserts observable result rather than freely changeable private structure. Typical contracts include behavior, protocol, serialization, naming, compatibility, recovery, concurrency, and critical ordering.

Do **not** add or retain a test by default for fields/defaults/path constants, simple forwarding, mock calls without an outcome, coverage alone, private methods/constructors/delegates, ordinary log wording, reflective implementation shape, or output printed for manual inspection. A benchmark needs a stable baseline, threshold, or defined regression decision. When “does not throw” is genuinely the contract, assert that outcome explicitly.

A source/dependency guard is justified only when the *contract itself* is a module/public API boundary, a forbidden dependency, or a generated/mirror artifact invariant. Name it as an architecture/contract test, prefer compilation or dependency analysis, and scan source only where those cannot express the rule. Do not use `readText().contains(...)` on private helper bodies as a TDD substitute.

## 2. L0–L3 and behavior ownership

The layer locates a test **after** it passes the value gate:

| Layer | Owner and assertion |
|---|---|
| L0 | No valuable automation; use the appropriate alternative evidence. |
| L1 | Deterministic domain rule, parser, serialization, generated artifact, DEX/APK transform, or narrow transport algorithm; prefer `main/src/test` for IDE-independent behavior. |
| L2 | Multi-class recovery, retry, compatibility, concurrent state, or IDE orchestration; usually `idea/src/test`. Mockito can isolate external dependencies while asserting a business result. |
| L3 | User-visible main Flow such as Run → compile → deploy or instrumentation; `idea/src/test/.../manager/*FlowTest` or a documented real-device matrix. |

One primary owner should protect each behavior. Extend it for a new state, exception, or compatibility branch; do not create a test for every intermediate class or retest forwarding through factory, carrier, manager, action, and HTTP layers. Keep static architecture guards in `*ArchitectureTest` / `*ContractTest` files separate from behavioral tests. Create a new test file only for independent behavior with no suitable owner.

When maintaining existing tests, remove or merge tests that have no adjudicable result, target unreachable capability, mirror implementation, or duplicate another owner. A test already living in a file does not make a new source-string assertion valuable. Keep real offline retry, state wake-up, atomic replacement order, and module-boundary guards when they protect independent contracts.

Never add a production-only-for-tests `provider`, `supplier`, `factory`, `override` lambda, function-type parameter, mutable closure, or default lambda merely to mock a dependency. Use an existing business-meaningful interface or class and ordinary dependency injection. Do not change production code solely to enable a test.

## 3. Workflow by change type

- **Feature/bug fix:** obtain a failing test, stable reproduction, exception log, or external-API comparison first. For a valuable automated assertion, write and confirm the failing test at its owner before the code change, then run it after. If automation would require an implementation-detail assertion or test-only seam, record why and use evidence at the actual failure boundary.
- **Refactor/optimization:** identify and run existing regression owners before changing behavior; add only uncovered stable behavior. Compile/deploy orchestration requires L3 or an existing equivalent Flow regression. Performance work needs a baseline, threshold, or defined L3 benchmark scenario.
- **Documentation only:** automated tests are normally unnecessary; check `git diff --check`, references/paths, index consistency, and a documentation build when the edit affects rendered output.

For a task report, record failure evidence, test-value judgment, owner/layer if applicable, alternative evidence, and the final result. Follow the repository's `AGENTS.md` execution checklist as the response format.

## 4. Demo testcase and artifact ownership

A demo testcase belongs under `android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/<scenario>/`. Keep one scenario per directory with role-revealing classes such as parent, child, and invoker. `AssembleAndroidProjectOnce` supplies the assembled demo artifact to L1 `DeployDataGeneratorTest` and L3 Flow tests. After changing a testcase, remove `~/.jugg/test_flag/skip_assemble` or assemble manually, then rerun both relevant owners; otherwise a stale APK can make tests pass against old code. When `~/.jugg` is unwritable, `JuggGlobalPathManager` uses a temporary Jugg root.

For deployment-effect assertions, use the real D8/APK `ParsedDex` shape and compare affected source files; hand-constructed `MethodNode` graphs can omit synthetic R8 details. This is an L1 evidence rule, not a requirement to duplicate the same graph assertion at every orchestration layer.

## 5. Layer selection examples

| Behavior | Owner/layer |
|---|---|
| Graph propagation and affected sources | `DeployDataGeneratorTest` (L1). |
| Direct Overlay checkpoint and write ordering | `DirectOverlayStateCheckerTest` / `DirectOverlayWriterTest` (L1). |
| Deploy recovery, retry, offline install | `JuggDeployerHelperRecoverTest`, `DeployRetryHandlerTest`, `JuggDeployerInstallTest` (L2). |
| Complete Run or androidTest route | `TopLevelFlowTest`, `TopLevelFlowWithGitTest`, `AndroidTestTopLevelFlowTest` (L3). |
| Forbid old deployer runtime types in main path | `DeployCompatArchitectureTest` (static boundary guard). |

These examples identify existing owners; they do not bypass the value gate.

## 6. Special evidence boundaries

### 6.1 AndroidTest

The test-value gate still governs instrumentation work. `06_android_test.md` defines current app/library capability and result boundaries; historical `docs/task/2026-04/androidtest_support_design.md` is background. `TestLauncherResultTest` and `LibraryTestApkBackfillHelperTest` own their stable L2 branches; `AndroidTestTopLevelFlowTest` protects the end-to-end path. Do not add `DeployOptions*Test` or path-constant tests merely because a new field carries the spec.

### 6.2 Compose resources

Separate metadata/generator correctness from runtime freshness. Existing L1 owners are `ComposeValueResourceConverterTest`, `ComposeResourceScannerTest`, and `ComposeResourceGeneratorBridgeTest`; `FileChangesHandlerTest` covers source classification/build-directory exclusion. Inspect metadata, generated accessors/classes, and target APK with targeted integration/artifact evidence. Do not claim a Flow test unless that file exists and actually ran.

For device verification, first establish a Gradle baseline Run. Add a key and verify incremental accessor generation/compilation. For a value-only change, read the value in the baseline process before editing it, then confirm the new runtime value and necessary restart, target APK, and absence of incremental Gradle Compose-resource tasks. The demo Kotlin 1.9 profile uses `src/commonMain/composeResources`; 2.1/2.3 use `composeResourcesExtended` plus `src/androidMain/customComposeResources`. Sync after each profile switch and restore the original. Choose L2 for multi-version metadata/artifact behavior and L3 or a documented device matrix for runtime freshness; do not duplicate the whole matrix at both layers.

### 6.3 External AAR R namespace

`DependencyDiffResultTest` owns symbol-over-Manifest namespace precedence and the no-source case. `RDexForSubmoduleCompilerTest` owns deduplication, app-package exclusion, missing-namespace failure, and APK routing. `JuggCompilerTest` covers the stage handoff into staged `R*.dex`. For runtime `NoSuchFieldError`, inspect the changed AAR's `r_package_name`, current Run host R output, and target APK staged namespace DEX before blaming one producer.

### 6.4 Constant references

`ConstRefEngineTest` and `ConstRefIntegrationTest` own edit-to-impact behavior, including a removed constant within an existing file; `ConstRefCacheDatabaseTest` owns persisted indexing; `RepoSharedFingerprintStoreTest` owns cross-worktree reuse. A source-file deletion removes state through a different path from an in-file removed-definition query. Verify affected-source output and the post-deploy commit boundary; a cache hit alone does not prove recompilation. See `03_deploy_const_ref.md`.

### 6.5 Source classpath and Kotlin opt-in

`02_compile_source.md` owns the compatibility behavior and diagnostic boundaries. Use these existing regressions when changing the corresponding source-compilation contract:

| Contract | Existing owner | Fixture and evidence |
|---|---|---|
| Recover Kotlin compilation when SDK `android.jar` shadows a framework-JAR supertype | `SourceCompileTest.romHiddenApi_shouldRecoverWhenSdkAndroidJarShadowsFrameworkJar` in `main/src/test/java/com/sickworm/intellij/jugg/compiler/SourceCompileTest.kt` | Demo `testcase/romhiddenapi/RomHiddenApiChild.kt` and `android_demo_project/app/romlibs/` JARs declared as `compileOnly`; asserts successful compilation of that fixture. |
| Preserve the Gradle module's opt-in for incremental KMP common source | `JuggCompilerTest.compileCommonSourceWithHiddenFromObjCRequiringOptIn` in `idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggCompilerTest.kt` | Demo `kmpCompose` common/Android `ObjCRefinementCase` sources; verifies compilation, the `ExperimentalObjCRefinement` opt-in argument, staged `ObjCRefinementCaseKt.dex`, and no Compose Gradle task. |

Keep the ROM fixture's framework JARs on the compile classpath. The opt-in fixture deliberately has no file-level opt-in, so adding one would bypass the module-argument contract. Apply §4's baseline refresh rule after fixture changes; compile/staging assertions alone do not prove device runtime behavior.

## 7. Typical test locations by path

### 7.1 Compile → Deploy

| Goal | Existing owner / layer |
|---|---|
| Run → compile → deploy | `TopLevelFlowTest` / `TopLevelFlowWithGitTest` (L3). |
| androidTest deployment and instrumentation | `AndroidTestTopLevelFlowTest` (L3). |
| Recovery, retry, early exits | `JuggDeployerHelperRecoverTest` / `DeployRetryHandlerTest` / `JuggDeployerHelperDeployTest` (L2). |
| Direct Overlay full chain | `JuggDeployerHelperDeployFlowTest` with `VirtualDeployDevice` (L2); writer/checker tests (L1). |
| Install/offline/mode escalation | `JuggDeployerInstallTest` (L2). |
| Deploy-compat dependency boundary | `DeployCompatArchitectureTest` (static guard). |

A change to compile/deploy dispatch or recovery-to-deployment order needs L3 execution or an existing equivalent Flow regression. The branch-specific L2/L1 tests complement that evidence; they do not replace it.

### 7.2 AndroidTest

Use `06_android_test.md` for capability constraints, then the test-value gate and `6.1` here for placement.

### 7.3 Existing L2 owners

Extend the existing Retry/Recover/Install, `TestLauncherResultTest`, or `LibraryTestApkBackfillHelperTest` owner for a new stable branch before creating another test file.

### 7.4 Gradle Init Script (`readProjectInfo.gradle.kts`)

Changes to `buildReadProjectInfoScript` inputs—`main/.../gradle/script/**`, embedded `project/data/**`, `DependencyDiffResult`, or `buildReadProjectInfoScript.gradle`—need independent **generated-script syntax** evidence. Gradle 7/9 functional success cannot establish Kotlin DSL 1.3/1.5 syntax compatibility.

| Evidence | Owner and prerequisite |
|---|---|
| Generated content contract: trailing commas, companion/inner-class adjustments, unavailable older APIs | `ReadProjectInfoScriptContentTest` (L1/static guard), required by default and independent of a real Gradle process. |
| Actual older script compilation | `ReadProjectInfoGradle5CompatTest` / `ReadProjectInfoGradle6CompatTest` (L2), with a compatible JDK. |
| Higher-version functional behavior | `ReadProjectInfoGradle7CompatTest` / `ReadProjectInfoGradle9CompatTest` (L2); useful for AGP behavior, not a replacement for older syntax checks. |

See `04_engineering_project.md` for why generated script source and embedded resource both matter.

## 8. Test infrastructure and speed flags

The test support `mock/Commons.kt` provides demo root, temporary build output, compile context, and project info. Enable `~/.jugg/test_flag/enabled` and `skip_assemble` only when intentionally reusing a known-current demo artifact; remove `skip_assemble` after any testcase change. `TestModeManager` reads the master flag once per process, so start a fresh test process after changing it. Do not trade away a required assembled-artifact check for speed.

## 9. Selecting a verification run

Run targeted owners and the necessary artifact/Flow checks after development. Never run unfiltered `:main:test` or `:idea:test`. Typical targeted commands are:

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest'
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.deploy.run.DeployRetryHandlerTest'
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.manager.TopLevelFlowTest'
./gradlew :idea:compileKotlin
```

`JUGG_TEST_SKIP_DEVICE=true` can run a deliberately selected broader JVM suite while `RequiresDeviceRule` skips before ADB/emulator probing. Do not select only one real-device class with `--tests` in that mode: after a whole-class skip, Gradle may say “No tests found.” Project and demo Gradle properties disable daemon and set a 10-second idle timeout; Tooling API can still start a short-lived daemon, and fixtures pass `--no-daemon` to protect against user-level overrides.

## 10. Running tests and verification

For an agent-bundle failure at `:jvmti_agent:buildInstrumentJar`, compare the same build under JDK 17 with a JDK 21 failure and inspect the D8 exception. Bundled AGP 7.2.2 D8 may reject JDK 21-generated metadata; JDK 17 success diagnoses build-tool compatibility, not device startup-agent correctness. Verify both relocated Dragonfly/private Kotlin runtime artifacts and the actual device behavior at their own boundaries.

For release packaging, run `:idea:verifyThirdPartyCompliance` against the plugin ZIP. It checks notices, licenses, source revision/checksums, the 104-component inventory, and SPDX SBOM *inside the distribution*; repository files alone are insufficient. After `third_party/components.csv` changes, regenerate notices, modification records, and SBOM with `ruby tools/generate_third_party_compliance.rb`, then verify the package.

Targeted compilation (`./gradlew :idea:compileKotlin`) proves wiring only. `./gradlew :idea:buildPlugin` and artifact inspection prove packaging; they do not establish an IDE/device runtime result.

## 11. Pitfalls and historical context

- L2 success cannot substitute for a needed L3 regression; a mock interaction alone does not prove deployment.
- An in-memory database test cannot prove SQLite persistence. Keep the persisted-state owner where the contract needs it.
- A source-string test that freezes a helper is not failure evidence merely because it was written before a fix.
- Manual logs or a one-time demo Run are alternative evidence, not an automated regression suite; record environment and result.
- Historical `docs/task/2026-03/TDD_UNIT_TEST_COVERAGE_GAP_REPORT_*.md` and `docs/task/2026-04/androidtest_support_design.md` provide scenarios, not a mandate to add unit tests to every fix.
