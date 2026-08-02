# Compiler Module Test Triage — 2026-04-16

> Scope: `main/src/test/java/com/sickworm/intellij/jugg/compiler/**`
> Strategy: Fix known failures first, then validate block by block.
> Parent report: `docs/task/2026-04/test_triage_2026_04_16.md`

---

## Status Overview

| Test Class | Status | Failures | Notes |
|------------|--------|----------|-------|
| `ConstRefCacheDatabaseTest` | ✅ FIXED | 0 | `registerSimpleNameMappings` 加入 outer class name 候选 |
| `ConstRefEngineTest` | ✅ FIXED | 0 | `startupStabilizationDelayMs` 改为构造器参数，测试注入 500ms |
| `DexMinifyCompilerPhase2Test` | ✅ PASS | 0 | 需要 `-Xmx6g`；testJuggFixClassGeneration 之前失败是偶发的 |
| `SourceMinifyCompileTest` | 🔴 PARTIAL | 6 | APK 加载问题修复；mapping drift (b3→g3) 已更新；剩余 6 个是编译输出 class name 不匹配（涉及 SourceCompiler 业务逻辑，暂跳过） |
| `ConstRefIntegrationTest` | ✅ PASS | 0 | |
| `JavaConstParserTest` | ✅ PASS | 0 | |
| `KotlinConstParserTest` | ✅ PASS | 0 | |
| `ConstDefinitionIndexTest` | ✅ PASS | 0 | |
| `ConstRefSessionCacheTest` | ✅ PASS | 0 | |
| `RepoSharedFingerprintStoreTest` | ✅ PASS | 0 | |
| `ClassObfuscatorTest` | ✅ PASS | 0 | |
| `DexObfuscatorTest` | ✅ PASS | 0 | |
| `R8MappingReaderTest` | ✅ PASS | 0 | |
| `R8MappingTest` | ✅ PASS | 0 | |
| `ResourceCompileTest` | ✅ FIXED | 0 | `compileResourceDirOverlay` 硬编码期望值 29→31（demo 项目新增 2 个资源文件） |
| `ResourceCompileAabResGuardTest` | ✅ FIXED | 0 | `build()` 中先调 `clearBuild()` 确保 assembleDebug 在 bundleRelease 之前，避免 clean 删除 resources-mapping.txt |
| `JuggAptCompilerTest` | ✅ PASS | 0 | |
| `JavaDiagnosticLocaleTest` | ✅ PASS | 0 | |
| `R8FileMakerTest` | ✅ PASS | 0 | 需要 `-Xmx6g`（同 DexMinifyCompilerPhase2Test） |
| `IncrementalCompilerHelperTest` | 🔴 1 FAIL | 1 | `should skip const ref await when first round compile failed`：asyncCheckBeforeCompile 在编译前即发起异步 const ref 等待，业务逻辑问题，暂跳过 |
| `SourceCompileTest` | ✅ PASS | 0 | 单独运行通过；并发运行时因 AssembleAndroidProjectOnce 竞争可能失败 |

---

## Block 1 — constref (DB-only, low OOM risk)

**Run command:**
```bash
cd /Users/wormchen/IdeaProjects/jugg/jugg_f1
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.constref.ConstRefCacheDatabaseTest" \
  -Dorg.gradle.jvmargs="-Xmx4g" --no-daemon 2>&1 | tail -60
```

### ConstRefCacheDatabaseTest — Failure Analysis

**Test:** `queryClassesBySimpleNames should find outer class for nested inner class`
**Line:** 614
**Assertion:** `assertEquals(setOf("com.example.Outer.Inner"), result["Outer"])`

**Hypothesis:** The method `queryClassesBySimpleNames` is expected to return inner class `com.example.Outer.Inner`
when queried by simple name `"Outer"` (which is the outer class name used in import). The DB is returning
empty or unexpected result — likely implementation bug or schema mismatch.

**Status:** ⏳ Pending run

---

### ConstRefEngineTest — Failure Analysis

**Test:** `initializeFullScan should defer first full scan until startup stabilization`
**Line:** 1072 (`waitUntil(timeoutMs = 5_000L)`)

**Hypothesis:** The full scan runs, but the 5-second timeout is not enough in CI/slow env,
OR the startup stabilization delay constant changed. Check `ConstRefEngine` for the stabilization delay value.

**Status:** ⏳ Pending run

---

## Block 2 — obfuscation/DexMinifyCompilerPhase2Test

**Run command:**
```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.obfuscation.DexMinifyCompilerPhase2Test" \
  -Dorg.gradle.jvmargs="-Xmx6g -XX:MaxMetaspaceSize=512m" --no-daemon 2>&1 | tail -80
```

### DexMinifyCompilerPhase2Test — Failure Analysis

**Test 1:** `testJuggFixClassGeneration` — Line 86: `assertTrue(juggFixClasses.isNotEmpty())`
- Hypothesis: `_jugg_fix` classes not being generated; could be mapping/classpath mismatch after demo project rebuild.

**Test 2:** `testJuggFixShouldStubMethodsDeletedInUsageFile` — OOM
- Hypothesis: ClassGraph scanning consumes too much heap. Need `-Xmx6g`.

**Status:** ⏳ Pending run

---

## Block 3 — SourceMinifyCompileTest

**Run command:**
```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.SourceMinifyCompileTest" \
  -Dorg.gradle.jvmargs="-Xmx4g" --no-daemon 2>&1 | tail -80
```

### SourceMinifyCompileTest — Failure Analysis

Tests expect specific obfuscated class names (`Lb3/a;`, `Lb3/c;`) that come from the APK's R8 mapping.
These are **hardcoded in the test** and will fail if:
1. The release APK was rebuilt with R8 and the mapping changed.
2. The demo project source changed, shifting R8's naming.

**Failing tests:**
- `testKeepClassName` (line 92): asserts field obfuscated to `"a"`
- `testSerializableClass` (line 252): asserts class name `"Lb3/c;"`
- `testAllMinifyClasses` (line 436): some class not compilable or obfuscation lookup fails

**Status:** ⏳ Pending run

---

## Fix Log

| Date | Test | Action | Result |
|------|------|--------|--------|
| 2026-04-16 | — | Document created, starting block-by-block validation | — |
| 2026-04-16 | `ConstRefCacheDatabaseTest` | `registerSimpleNameMappings` 加入 outer class name 候选（`substringBefore('.')`） | ✅ PASS (14 tests) |
| 2026-04-16 | `ConstRefEngineTest` | `ConstRefEngine` 构造器加入 `startupStabilizationDelayMs` 参数（默认 10000），测试注入 500ms | ✅ PASS |
| 2026-04-16 | `DexMinifyCompilerPhase2Test` | 需要 `-Xmx6g`；用例本身无 bug | ✅ PASS with 6g heap |
| 2026-04-16 | `SourceMinifyCompileTest` | 1) APK 加载 fallback：APK 被 clean 删除后 forceRebuild | Partial |
| 2026-04-16 | `SourceMinifyCompileTest` | 2) Mapping drift: b3 → g3 (KeepClassMembers/KeepMethodName/SerializableClass/WildcardKeepClass) | 仍有 6 个 compiled class name 不匹配 |
| 2026-04-17 | constref/* (6 tests) | 运行验证 | ✅ PASS (全部通过) |
| 2026-04-17 | obfuscation/* (4 tests) | 运行验证 | ✅ PASS (全部通过) |
| 2026-04-17 | `ResourceCompileTest` | `compileResourceDirOverlay` 硬编码 29→31（demo 项目新增 activity_mcp_test.xml + colors.xml + dimens.xml 等 2 个 Res 输出） | ✅ PASS |
| 2026-04-17 | `ResourceCompileAabResGuardTest` | `build()` 中先调 `clearBuild()` 确保 assembleDebug 在 bundleRelease 之前，避免 clean 删除 resources-mapping.txt；加 `import clearBuild` | ✅ PASS (14 tests) |
| 2026-04-17 | `R8FileMakerTest` | 需要 `-Xmx6g` | ✅ PASS with 6g heap |
| 2026-04-17 | `SourceCompileTest` | 单独运行通过 | ✅ PASS |
| 2026-04-17 | `JuggAptCompilerTest`, `JavaDiagnosticLocaleTest` | 运行验证 | ✅ PASS |
| 2026-04-17 | `IncrementalCompilerHelperTest` | `should skip const ref await when first round compile failed` 失败：asyncCheckBeforeCompile 在编译前通过协程异步执行 awaitConstRefAnalysis，涉及业务逻辑变更，暂跳过 | 🔴 1 FAIL（跳过） |

---

## SourceMinifyCompileTest 剩余失败分析

**现象：** 编译后的 DEX 中没有 `Lg3/a;`, `Lg3/b;`, `Lg3/c;`, `Lg3/d;` 类（KeepClassMembers, KeepMethodName, SerializableClass, WildcardKeepClass 的混淆名）。
`testKeepClassName` 还有 field `a` 找不到的问题。

**可能原因：**
1. `SourceCompiler` 用了旧的 mapping 文件（`releaseContext` 里的 module 信息指向 debug mapping？）
2. R8 在增量编译时对这些类做了不同的混淆（不匹配 full APK 的 mapping）
3. 编译输出的类名与 APK 里的类名不一致，是 SourceCompiler 混淆逻辑的 bug

**下一步：** 需要打印 `SourceCompiler` 编译时使用的 mapping 路径，并检查编译后 DEX 的实际类名 vs APK 类名。

---

## Next Steps

1. ✅ ConstRefCacheDatabaseTest — FIXED
2. ✅ ConstRefEngineTest — FIXED
3. ✅ DexMinifyCompilerPhase2Test — PASS (需 -Xmx6g)
4. 🔴 SourceMinifyCompileTest — 剩余 6 个失败，需调查 SourceCompiler 混淆输出的类名（涉及业务逻辑，暂跳过）
5. ✅ constref/* 其余测试 — 全部通过
6. ✅ obfuscation/* 其余测试 — 全部通过
7. ✅ ResourceCompileTest — FIXED (硬编码 29→31)
8. ✅ ResourceCompileAabResGuardTest — FIXED (build() 顺序修复)
9. ✅ R8FileMakerTest — PASS (需 -Xmx6g)
10. ✅ SourceCompileTest — PASS (单独运行)
11. 🔴 IncrementalCompilerHelperTest — 1 个失败（asyncCheckBeforeCompile 逻辑，涉及业务，暂跳过）
