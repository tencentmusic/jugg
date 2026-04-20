# Unit Test Triage Report — 2026-04-16

> Scope: `main/src/test/` (75 test files)
> Status: partial — deploy / gradle / project / logger / ide / tools modules completed; compiler and mcp modules need a separate run.

---

## 1. Executive Summary

| Module | Classes Run | Tests Run | Failed | Skipped | Status |
|--------|-------------|-----------|--------|---------|--------|
| deploy | 14 | 71 | 3 | 0 | RED |
| gradle | 6 | 16 | 6 | 0 | RED |
| project | 3 | 6 | 0 | 0 | GREEN |
| logger | 1 | 8 | 0 | 0 | GREEN |
| ide | 1 | 1 | 0 | 0 | GREEN |
| tools | 1 | 1 | 0 | 0 | GREEN |
| mcp | (run, all pass per console output) | ~180+ | 0 | 0 | GREEN |
| **compiler** | **NOT FULLY CAPTURED** | 118 (partial) | **12** | 4 | RED (OOM) |

**Key issues found:**
1. `compiler.*` — 12 failures + OOM during `DexMinifyCompilerPhase2Test`; likely insufficient JVM heap (`-Xmx4g` needed).
2. `gradle.script.ReadProjectInfoGradle7CompatTest` — 2 failures (InjectApplication-related script assertions).
3. `gradle.script.ReadProjectInfoGradle9CompatTest` — 4 failures (same root cause as Gradle7, AGP 9.0 variant).
4. `deploy.data.DeployDataGeneratorReleaseTest` — 2 failures (minify removal detection misses obfuscated classes).
5. `deploy.data.DeployDataDatabaseSqLiteHelperTest` — 1 failure (fastjson2 class ordering in `testUpdateApkInfos`).

---

## 2. Failing Tests Detail

### 2.1 deploy.data.DeployDataGeneratorReleaseTest (2 failures)

| Test | Failure message |
|------|-----------------|
| `testMinifyRemoveMinifyTestActivity` | `AssertionError: Lc3/a; should be detected` |
| `testMinifyRemoveKeepClassName` | `AssertionError: Ljava/lang/Object; should be detected` |

**Root cause hypothesis:** Minify removal detection is looking for obfuscated class names (`Lc3/a;`) that may have changed due to R8/mapping changes in the demo project. The expected minified names are hardcoded or derived from a stale APK mapping.

**File:** `main/src/test/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGeneratorReleaseTest.kt:120`

---

### 2.2 deploy.data.DeployDataDatabaseSqLiteHelperTest (1 failure)

| Test | Failure message |
|------|-----------------|
| `testUpdateApkInfos` | `AssertionError: Iterable elements differ at index 1. Expected element <Lcom/alibaba/fastjson2/util/Fastjson1xS...>` |

**Root cause hypothesis:** The expected class list in the DB after `updateApkInfos` depends on ordering of fastjson2 dependency classes. Either the APK changed (dependency version bump) or the ordering assumption is wrong.

**File:** `main/src/test/java/com/sickworm/intellij/jugg/deploy/data/DeployDataDatabaseSqLiteHelperTest.kt:109`

---

### 2.3 gradle.script.ReadProjectInfoGradle7CompatTest (2 failures)

| Test | Failure message |
|------|-----------------|
| `generatedScript_shouldRunManifestTaskOnAndroidAppWithInjectApplicationEnabled` | `AssertionError` |
| `generatedScript_shouldRunOnAndroidAppWithInjectApplicationEnabled` | `AssertionError` |

**Root cause hypothesis:** The generated Gradle init script content for `InjectApplication=enabled` mode changed but the test expected strings were not updated.

**File:** `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/ReadProjectInfoGradle7CompatTest.kt`

---

### 2.4 gradle.script.ReadProjectInfoGradle9CompatTest (4 failures)

| Test | Failure message |
|------|-----------------|
| `generatedScript_shouldRunManifestTaskOnAgp90WithInjectApplicationEnabled` | `AssertionError` |
| `generatedScript_shouldRunManifestTaskOnAndroidAppWithInjectApplicationEnabled` | `AssertionError` |
| `generatedScript_shouldRunOnAndroidAppWithInjectApplicationEnabled` | `AssertionError` |
| `generatedScript_shouldRunOnAgp90WithInjectApplicationEnabled` | `AssertionError` |

**Root cause hypothesis:** Same root cause as Gradle7 variant — `InjectApplication` script template changed, test expected strings stale. AGP 9.0 path adds 2 additional cases.

**File:** `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/ReadProjectInfoGradle9CompatTest.kt`

---

### 2.5 compiler.* — 12 failures + OOM (INCOMPLETE DATA)

Observed from console output only (XML not captured due to process kill):

| Test | Error |
|------|-------|
| `SourceMinifyCompileTest > testKeepClassName` | `AssertionError at line 92` |
| `SourceMinifyCompileTest > testAllMinifyClasses` | `AssertionError at line 436` |
| `SourceMinifyCompileTest > testSerializableClass` | `AssertionError at line 252` |
| `ConstRefCacheDatabaseTest > queryClassesBySimpleNames should find outer class for nested inner class` | `AssertionError at line 614` |
| `ConstRefEngineTest > initializeFullScan should defer first full scan until startup stabilization` | `AssertionError at line 1072` |
| `DexMinifyCompilerPhase2Test > testJuggFixClassGeneration` | `AssertionError at line 86` |
| `DexMinifyCompilerPhase2Test > testJuggFixShouldStubMethodsDeletedInUsageFile` | `OutOfMemoryError` |
| (4 more) | `AssertionError` (details not captured) |

**Note:** OOM errors are likely from ClassGraph workers during `DexMinifyCompilerPhase2Test`. Needs re-run with `-Xmx6g` in test JVM.

---

## 3. Slow Tests (>30s)

| Class | Time | Tests | Note |
|-------|------|-------|------|
| `ReadProjectInfoGradle9CompatTest` | 407.6s | 5 | Spawns real Gradle process per test — very slow by design |
| `ReadProjectInfoGradle7CompatTest` | 172.2s | 3 | Same reason |
| `DeployDataGeneratorTest` | 71.0s | 24 | APK assemble + D8 compile |
| `DeployDataGeneratorReleaseTest` | 43.9s | 5 | APK assemble + R8 minify |
| `ReadProjectInfoGradle5CompatTest` | 26.3s | 1 | Spawns real Gradle process |
| `DeployHistoryManagerTest` | 21.5s | 4 | SQLite I/O |
| `BaseCompileContextChangedFileBridgeTest` | 11.9s | 2 | — |

**The two `ReadProjectInfoGradle*` suites dominate total runtime.** They run real Gradle processes and are expected to be slow. No optimization needed unless parallelized.

---

## 4. Modules Not Yet Run

The following test classes have NOT been run in this session and need a separate execution:

| Module | Classes | Est. Risk |
|--------|---------|-----------|
| `compiler.constref.*` | 8 files | Medium — ConstRefEngineTest is very large (1100+ lines) |
| `compiler.obfuscation.*` | 6 files | High — DexObfuscatorTest is 106KB, OOM risk |
| `compiler.overlay.*` | 2 files | Low |
| `compiler.source.*` | 3 files | Medium |
| `compiler.IncrementalCompilerHelperTest` | 1 file | Low |
| `compiler.SourceCompileTest` | 1 file | Low |
| `compiler.SourceMinifyCompileTest` | 1 file | Medium (3 failures seen) |

---

## 5. Fix Plan

Priority order based on severity and ease of fix:

### P0 — Quick fixes (test expectation updates)

| # | Test Class | Issue | Action |
|---|------------|-------|--------|
| 1 | `ReadProjectInfoGradle7CompatTest` | Stale expected script strings | Diff actual vs expected output, update expected strings |
| 2 | `ReadProjectInfoGradle9CompatTest` | Same as above, AGP 9.0 | Same action |
| 3 | `DeployDataDatabaseSqLiteHelperTest.testUpdateApkInfos` | Ordering assumption on fastjson2 classes | Change assertion to `assertContainsAll` or sort both sides |

### P1 — APK mapping drift

| # | Test Class | Issue | Action |
|---|------------|-------|--------|
| 4 | `DeployDataGeneratorReleaseTest` | Obfuscated class names stale | Re-assemble demo project, update expected obfuscated class names OR make test class-agnostic |

### P2 — Compiler tests (need full run first)

| # | Test Class | Issue | Action |
|---|------------|-------|--------|
| 5 | `SourceMinifyCompileTest` | 3 failures — likely APK mapping drift | Re-run with updated demo APK |
| 6 | `DexMinifyCompilerPhase2Test` | OOM + assertion failure | Increase test JVM heap to 6g, investigate assertion |
| 7 | `ConstRefCacheDatabaseTest` | 1 assertion failure | Investigate after full run |
| 8 | `ConstRefEngineTest` | 1 assertion failure | Investigate after full run |

---

## 6. Next Steps

1. **Re-run compiler module with more heap:**
   ```bash
   ./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.*" \
     -Dorg.gradle.jvmargs="-Xmx8g -XX:MaxMetaspaceSize=1g" --no-daemon
   ```
2. **Fix P0 items** — open `ReadProjectInfoGradle7CompatTest` and `ReadProjectInfoGradle9CompatTest`, run with `--info` to see actual vs expected diffs.
3. **Fix P1 items** — delete `skip_assemble` flag, rebuild demo project, update minified class name expectations.
4. **Fix P2 items** — pending full compiler run results.

---

## 7. Coverage Gaps (not in scope of this triage)

- `idea/src/test/` — not run in this session (requires IDE context, separate Gradle task `:idea:test`).
- `ConstRefEngineBenchmarkTest` — performance test, intentionally excluded from regression.
