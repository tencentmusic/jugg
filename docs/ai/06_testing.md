# Testing and Verification Strategy (Authoritative Rules)

> Last checked: 2026-09-11
> Consistency rule: when documentation conflicts with code, follow the code.
> **Relationship to AGENTS.md / CLAUDE.md:** Top-level rules retain only non-negotiable constraints. **This page is the sole authority for verification evidence, test value, layers, TDD, test placement, and existing-test maintenance.** If another `docs/task/YYYY-MM/*` conflicts, this page prevails.

---

## 0. Scope

This page answers testing/verification questions in a fixed order:

1. What verification evidence this change needs.
2. Whether a new automated test is valuable.
3. Which behavior owner is responsible if it is worth testing.
4. Whether a valuable test belongs at L1, L2, or L3.
5. Which alternative evidence to use when no valuable automated assertion is possible.

**Never skip the test-value judgment and create a test solely because of change type, coverage, or a formal TDD demand.**

---

## 1. Common Decision Order

### 1.1 Governing Principles

- Every development task needs **verification evidence** appropriate to its risk.
- Automated tests are one evidence type; not every change needs a new automated test.
- Apply the **test-value gate** before adding or retaining an automated test.
- The value gate outranks formal TDD, L0–L3 layers, and placement rules such as “reuse an existing test file.”
- TDD applies only to behavior that passes the value gate and supports a stable automated assertion.
- No new automated test does not mean no verification. Record failure evidence, why automation is unsuitable, and alternative verification.

### 1.2 Decision Flow

```text
What must this change prove?
  -> Is there independent, stable, observable behavior that a real change could break?
     -> No: add no test; run compilation, static checks, or other needed verification
     -> Yes: Can it be automated stably without binding to private implementation or adding a test-only seam?
        -> Yes: identify behavior owner -> write failing test first / confirm existing regression -> choose L1/L2/L3
        -> No: do not manufacture implementation-detail tests -> preserve failure reproduction -> choose alternative verification
```

### 1.3 Verification Evidence Types

| Evidence | Applies to | Proves |
|------|----------|------------|
| Automated test | Stable, deterministic, observable behavior | Behavioral regression can be detected continuously. |
| Targeted compilation | API wiring, type compatibility, module dependencies | Current sources compile against target dependencies. |
| Build/artifact inspection | Plugin package, APK, DEX, generated script | Final artifact exists with expected structure. |
| Bytecode/API inspection | Android Studio / Gradle binary compatibility | Compiled output actually links to intended API shape. |
| Logs and stable reproduction | External runtime, real device, IDE-internal failure | Original problem occurred and its trigger is established. |
| L3 Flow | Main path such as Run → compile → deploy | User-visible process works end to end. |
| Manual regression matrix | Real device/IDE combinations without stable automation | Final behavior works in specified environments. |

Alternative verification should approach the actual failure boundary. Successful compilation cannot substitute for runtime behavior; source-string inspection cannot substitute for user behavior.

---

## 2. Test-Value Gate

### 2.1 Passing Conditions

A test worth keeping usually satisfies all of these:

1. Protects a stable contract such as user-visible behavior, business rule, external protocol, naming convention, compatibility, recovery policy, concurrent state, or critical execution order.
2. A real contract break makes the test fail consistently with a deterministic assertion explaining it.
3. Has a clear behavior owner, without another test proving the same responsibility at the same granularity.
4. Asserts observable results rather than freely changeable private structure.

Mockito, test layer, number of classes, and coverage cannot alone establish test value.

### 2.2 Do Not Test by Default

Normally do not add or retain automated tests merely for:

- Existence of a class, field, getter, constant, path, or simple data carrier.
- Unchanged parameter forwarding or only verifying a mock call, with no business result, state change, or critical order.
- Increasing coverage, traversing branches, or freezing current implementation shape.
- Reflectively inspecting private fields/methods, internal signatures, or ordinary UI properties.
- Reading production source and using `contains`, regex, or strings to lock down private method bodies, constructor overloads, concrete calls, class names, or delegates.
- Exact strings in ordinary logs or non-contract wording.
- Printing output or relying on manual observation without an adjudicable result. If “does not throw” is itself the contract, assert it explicitly with `assertDoesNotThrow` or equivalent.
- Benchmarks without a stable baseline, threshold, or regression decision.

If “write a test first” can only be satisfied this way, automation failed the value gate; use alternative verification.

### 2.3 Source and Static Architecture Guards

Source checks are not ordinary behavior tests. Allow them only when the **contract itself is a source/dependency boundary**:

- A module must not import or expose a runtime type.
- A public compatibility interface must not leak version-specific APIs.
- Generated scripts, protocol text, or mirror files must stay synchronized to one source.
- A forbidden dependency is documented architecturally but cannot be expressed directly by the build system.

Source guards must satisfy:

1. Name describes the architecture contract, not a private method implementation.
2. Assertion spans module, public boundary, generated artifact, or forbidden dependency—not a helper body.
3. Prefer compilation, dependency constraints, or static analysis; scan source only when those cannot express the rule.
4. Do not present a source guard as TDD replacement for a feature/bug fix.

For example, “IDE main path must not depend on old deployer runtime types” may be an architecture guard. “`createAdbClient` must invoke a particular three-argument constructor” is not.

---

## 3. Automated Test Layers

Layers decide **where** a test that passed the value gate belongs, not whether to write one.

```text
                    ┌─────────────────────────────┐
              L3    │  *FlowTest / release matrix │  real demo compile → deploy/run
                    ├─────────────────────────────┤
              L2    │  multi-class + Mockito API   │  recovery/retry/orchestration/compatibility
                    ├─────────────────────────────┤
              L1    │  domain test + real artifact │  algorithm/parser/serialization/output
                    ├─────────────────────────────┤
              L0    │  no automated test           │  pure data/forwarding/implementation details
                    └─────────────────────────────┘
```

| Layer | Typical location | Proves |
|------|----------|----------|
| **L3** | `idea/src/test/.../manager/TopLevelFlowTest`, `TopLevelFlowWithGitTest`, `AndroidTestTopLevelFlowTest` | User-visible main path and equivalence before/after refactoring. |
| **L2** | `idea/.../deploy/run/*Test`, `JuggCompileHelperTest` | Recovery, retry, concurrency, compatibility, and IDE orchestration branches. |
| **L1** | `main/.../DeployDataGeneratorTest`, `main/.../deploy/direct/*Test`, parser/output tests | Deterministic transformations, complex structures, and real artifacts. |
| **L0** | — | Add no test; choose necessary alternative evidence. |

### 3.1 Allowed L1 Scope

After passing the value gate, these behaviors commonly fit L1:

| Type | Example | Module |
|------|------|------|
| Impact analysis / graph propagation | `DeployDataGenerator`, `DeployDataDatabase` | main |
| Bytecode / Dex / APK parsing | `ParsedDex`, `ApkInfoReader` | main |
| Protocol / log parsing | `InstrumentationOutputParser`, `AdbLogWrapper` | main |
| Timing / buffering algorithms | `AndroidTestLogAttributor`, `TestLauncher` logcat attribution | idea |
| Pure-function derivation | `AndroidTestCommandDeriver`, `InstrumentCommandBuilder` | main |
| External naming / compatibility convention | IDE module to Gradle variant mapping | main / idea |
| Direct Overlay checks | `DirectOverlayStateChecker`, `DirectOverlayWriter` | main |
| Serialization round trip | `ApkInfoSerializer`, `JuggDeploymentCacheStore` | main / idea |
| Generated artifacts | Gradle init script, APT output, Manifest, R files | main / idea |
| Init-script generation contract | `ReadProjectInfoScriptContentTest` (trailing commas, companion, disabled APIs, etc.) | main |

Outside L1:

- Collaboration branches in `JuggDeployerHelper`, `DeployStateRecover`, and `DeployRetryHandler` belong at L2; user main path belongs at L3.
- `DeployOptions` fields, path constants, and simple getters belong at L0.
- A one-line pure syntax conversion belongs at L0; if it encodes an external protocol or naming contract, add it to an existing owner.

---

## 4. Behavior Owner and Test Placement

### 4.1 Owner Principles

- One primary owner should protect each stable behavior. Test names/assertions describe behavioral responsibility, not repeat a production method name.
- Add the same behavior to an existing owner instead of creating a `*Test.kt` at every production layer.
- L3 owns external main path; L2 covers exceptional, recovery, compatibility, and concurrent branches too numerous for L3; L1 covers deterministic domain rules.
- A data flow need not be retested for forwarding at factory, carrier, manager, action, and HTTP layers.
- An existing source-scan test in a file does not make a new source-string assertion valuable automatically; apply the value gate again.

### 4.2 Module Priority

1. Put deterministic IDE-independent behavior in `main/src/test` first.
2. Put IDE APIs, RunConfig, `JuggRunningTask`, and deploy/run orchestration in `idea/src/test`.
3. Name architecture guards separately as `*ArchitectureTest` / `*ContractTest`, not within behavior owners.
4. Create a test file only for new independent behavior without a suitable owner.

### 4.3 Existing-Test Maintenance

Delete, migrate, or merge first when a test:

- Has no adjudicable result and only prints content or inspects a real environment by hand.
- Verifies only fields, defaults, paths, getters, forwarding, or mock calls.
- Freezes only a private method, concrete delegate, construction shape, reflection signature, ordinary log, or non-contract wording.
- Mixes behavior tests with static architecture guards in one owner file.
- Duplicates an owner without a new exception, compatibility, state, or boundary branch.
- Targets a production capability no longer reachable or registered.

Examples:

| Case | Decision | Reason |
|------|------|------|
| `JuggDeployerInstallTest#install retries once after offline exception and succeeds` | Keep (L2) | Protects ADB-offline recovery and retry count. |
| `DeployStateManagerTest#waitForPendingFileProcessing...` | Keep (L2) | Protects timeout and condition wake-up. |
| `DirectOverlayWriterTest#write should remove payload targets before unzip` | Keep (L1) | Protects atomic replacement order. |
| `DeployCompatArchitectureTest` forbidding old deployer types in main path | Keep (static architecture guard) | Contract is the module boundary itself. |
| Assertion of which `AdbClient` constructor a private Quail helper uses | Delete / do not add | Freezes implementation, not install behavior. |
| `DeployTargetManagerTest#test` | Delete | No assertion, only accesses a real device. |

---

## 5. Workflow by Change Type

### 5.1 Feature / Bug Fix

1. Obtain **failure evidence** of the missing behavior first: failing test, stable reproduction, exception log, crash stack, or external-API comparison.
2. Apply the test-value gate.
3. For a valuable automated assertion, identify owner, write and confirm a failing test, then change production code.
4. If automation would bind to details or require a test-only seam, add no test; record why and choose alternative verification.
5. After fix, run targeted test or alternative verification and compare with failure evidence to confirm disappearance.

### 5.2 Refactor / Optimize

- List existing regression owners and confirm they pass before change.
- Add tests only for stable behavior lacking protection, not internal structural changes.
- Deploy/compile orchestration changes require L3 or an existing equivalent Flow regression.
- Performance optimization needs a stable baseline, threshold, or defined L3 benchmark scenario.

### 5.3 Documentation Only

- Automated tests are not required.
- Run `git diff --check`, sample paths, index consistency, or documentation build according to risk.

### 5.4 Execution Checklist

Record as applicable in a development task:

```text
- Failure evidence: test / log / stable reproduction / N/A
- Automated-test value judgment: add / reuse / no new test + reason
- Test owner and layer: Class#method (L1/L2/L3) / N/A
- Alternative verification: compile / build / artifact / bytecode / manual matrix / N/A
```

---

## 6. Testcase Class Rules (L1 / L3)

### 6.1 Directory Convention

```text
android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/
└── <feature>/
    ├── TargetClass.kt
    └── InvokerClass.kt
```

- One scenario per directory; class names show roles such as `Parent` / `Child` / `Invoker`.
- After adding/changing a testcase, delete `~/.jugg/test_flag/skip_assemble` or assemble manually.

### 6.2 Relationship to L3

L3 Flow depends on `AssembleAndroidProjectOnce`; L1 `DeployDataGeneratorTest` uses the same demo artifact. Rerun both after changing a testcase.

---

## 7. Typical Test Locations by Path

### 7.1 Compile → Deploy

| Goal | Layer | File |
|------|------|------|
| Real deployment after user clicks Run | **L3** | `TopLevelFlowTest`, `TopLevelFlowWithGitTest` |
| androidTest deployment + instrumentation | **L3** | `AndroidTestTopLevelFlowTest` |
| Dry deploy / recover / retry | L2 | `JuggDeployerHelperRecoverTest`, `DeployRetryHandlerTest` |
| Direct Overlay full chain with virtual device | L2 | `JuggDeployerHelperDeployFlowTest` + `VirtualDeployDevice` |
| Early deployment exits | L2 | `JuggDeployerHelperDeployTest` |
| Install offline / retry / mode escalation | L2 | `JuggDeployerInstallTest` |
| Deploy-compat source dependency boundary | Static architecture guard | `DeployCompatArchitectureTest` |
| Narrow transport script | L1 | `DirectOverlaySwapTransportTest` |
| Three-way overlay / writer algorithm | L1 | `DirectOverlayStateCheckerTest`, `DirectOverlayWriterTest` |

A change to `JuggDeployerHelper.deploy` dispatch or recovery→deployment order must include at least one L3 in its execution checklist, or identify an equivalent existing Flow regression.

### 7.2 AndroidTest

Scenario tables in `docs/task/2026-04/androidtest_support_design.md` are background. Test value, verification, and layers follow this page. See `06_android_test.md` for capability details.

### 7.3 Existing L2 Owners

Add new deploy/run branches to these first:

- `DeployRetryHandlerTest` / `JuggDeployerHelperRecoverTest`
- `JuggDeployerInstallTest`
- `TestLauncherResultTest`
- `LibraryTestApkBackfillHelperTest`

Do not add `DeployOptions*Test`, path-constant tests, or equivalent L0 files.

### 7.4 Gradle Init Script (`readProjectInfo.gradle.kts`)

When a change enters inputs to `buildReadProjectInfoScript` (`main/.../gradle/script/**`, embedded `project/data/**`, `DependencyDiffResult`, or `buildReadProjectInfoScript.gradle`), cover **generated-script syntax** independently; higher-version functional compatibility cannot substitute:

| Goal | Layer | Owner | Notes |
|------|------|-------|------|
| Generation contract (trailing commas, companion, APIs unavailable under Kotlin 1.3/1.5, etc.) | L1 / static guard | `ReadProjectInfoScriptContentTest` | Required by default; needs neither Java 8 nor a real Gradle process. |
| Real script compilation (Kotlin DSL language version < 1.4) | L2 | `ReadProjectInfoGradle5CompatTest`, `ReadProjectInfoGradle6CompatTest` | Run with compatible JDK; catches syntax holes missed by Gradle 7+. |
| Higher-version functionality / AGP behavior | L2 | `ReadProjectInfoGradle7CompatTest`, `ReadProjectInfoGradle9CompatTest` | Proves functional behavior only; **cannot** replace syntax regressions from 5/6 or `ScriptContentTest`. |

Missed-regression pattern: if init-script changes run only Gradle 7/9 compatibility checks, pre-1.4 syntax defects such as trailing commas pass silently. See `04_engineering_project.md` §6 for engineering constraints.

---

## 8. DeployDataGeneratorTest Pattern (L1 Example)

Use real D8 output; do not hand-build `MethodNode` and omit details such as `$r8$lambda$`.

### 8.1 Extract ParsedDex from APK

```kotlin
private fun getParsedDex(className: String): ParsedDex {
    val classSigName = className.classSigName
    return ParsedDex(
        parsedApk.classes.filter { it.key == classSigName }.map {
            ClassDeployItem(
                DeployItem(it.key, CompileOutput.Type.Dex, 0, byteArrayOf(), DeployItem.FLAG_CLASS),
                listOf(it.value),
            )
        },
        parsedApk.methodRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
        parsedApk.fieldRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
        parsedApk.subclassRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
    )
}
```

### 8.2 Assert Affected Sources

```kotlin
val data = generator.buildDeployData(modifiedParsedDex, emptyList())
assertEquals(listOf("SubClass1.java", "SubClass2.java").sorted(), data.effectedSourceFileNames.sorted())
```

---

## 9. Test Infrastructure

### 9.1 Prerequisites

```kotlin
fun clearBuild() {
    AssembleAndroidProjectOnce.ensure()
    buildDir.clearDir()
}
```

### 9.2 Key Globals (`mock/Commons.kt`)

| Variable | Meaning |
|------|------|
| `buildDir` | Temporary compilation output. |
| `assetsAndroidDir` | Root of `android_demo_project`. |
| `context` | `SimpleCompileContext`. |
| `projectInfo` | APK metadata. |

---

## 10. Running Tests and Verification

This repository and `android_demo_project` set `org.gradle.daemon=false` and idle timeout to 10 seconds. CLI tests leave no Gradle daemon after completion; IDE/Tooling API may still start one that exits after about 10 seconds idle. Test fixtures additionally pass `--no-daemon` to `./gradlew` so user-level `~/.gradle/gradle.properties` cannot override project settings.

Never run unfiltered full `:main:test` / `:idea:test`.

For a full JVM test run that explicitly skips real-device tests, set `JUGG_TEST_SKIP_DEVICE=true`. Every class using `RequiresDeviceRule` skips before probing adb or launching an emulator, with no test-class inventory to maintain.

```bash
JUGG_TEST_SKIP_DEVICE=true ./gradlew test --continue
```

Use this mode for batch runs containing ordinary tests. Do not select only one real-device test class with `--tests`: after the whole class skips, Gradle may report `No tests found` because the filter saw no test events.

```bash
# L3
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest"

# L2
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.DeployRetryHandlerTest"

# L1
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest"

# Compilation / build as alternative verification
./gradlew :idea:compileKotlin
./gradlew :idea:buildPlugin
```

---

## 11. Skipping Assemble for Speed

```bash
mkdir -p ~/.jugg/test_flag
touch ~/.jugg/test_flag/enabled
touch ~/.jugg/test_flag/skip_assemble
```

If `~/.jugg` is unwritable, flags live under `${java.io.tmpdir}/jugg-<user>/test_flag`. Delete `skip_assemble` after adding a testcase.

---
## 12. Common Pitfalls

| Problem | Cause | Remedy |
|------|------|------|
| A source-string test added to satisfy TDD | Value gate skipped. | Delete the test, record failure evidence, and select alternative verification. |
| `readText().contains(...)` freezes private helper | Implementation mistaken for contract. | Keep only explicit module/protocol/generated-artifact guards. |
| L2 passes but production still breaks | L3 absent. | Add a Flow or release regression matrix. |
| `getParsedDex` empty | Demo not assembled / wrong class name. | Delete `skip_assemble`. |
| SQLite and in-memory DB differ | Only in-memory DB tested. | Add coverage to SQLite owner. |
| Third `*Test` for a Helper | Existing owner ignored. | Merge into Recover/Retry/Flow owner. |
| Every intermediate class has a test | Tests mirror code structure. | Retain final behavior, exceptional branch, or protocol owner. |
| Test only checks a mock call | No business result or critical order. | Assert observable behavior or delete if impossible. |
| Manual logs described as an automated test | No continuous decision mechanism. | Classify as alternative verification with environment and result. |

---

## 13. Investigation Entry Points

| Question | Start with |
|------|----------|
| Unsure whether automation is needed | §1 decision flow and §2 value gate. |
| Cannot write failing test but have stable reproduction | §5.1 Feature / Bug Fix. |
| Unsure of L1/L2/L3 | §3 layers and §4 owner. |
| Unsure whether source scan is appropriate | §2.3 static architecture guards. |
| deploy/run branch reproduced only at L2 | §7.1; check whether L3 is also needed. |
| androidTest test placement unclear | `06_android_test.md` and §7.2. |
| New testcase reads stale artifact | §6.1 and §11. |

---

## 14. Historical Documents

Historical proposals such as `docs/task/2026-03/TDD_UNIT_TEST_COVERAGE_GAP_REPORT_*.md` and `docs/task/2026-04/androidtest_support_design.md` provide scenario background only. If they imply “every bug fix needs a new unit test,” “more unit tests is always better,” or “unit tests outrank the user main path,” apply this page's verification evidence, value gate, and owner rules instead.
