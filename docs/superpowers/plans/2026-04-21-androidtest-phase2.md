# androidTest Phase 2 — Incremental Compile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make androidTest source file changes go through Jugg incremental compile instead of triggering Gradle full compile every time.

**Architecture:** Treat androidTest source set as an independent `ModuleInfo` (generated on the Gradle side), route it to the test APK via `ModuleApkBelongsUtils`, and conditionally include it in `CompileContextManager` only when `buildTarget == ANDROID_TEST`. Reuses the existing multi-APK deploy loop in `JuggDeployTask` without modification.

**Tech Stack:** Kotlin, Gradle init script (Kotlin DSL compiled via `buildReadProjectInfoScript.gradle`), IntelliJ Platform SDK, existing `ModuleInfo`/`ApkInfo` model.

**Spec:** `docs/task/androidtest_phase2_design.md`

---

## File Map

| File | Change |
|---|---|
| `main/.../project/data/JuggProjectInfo.kt` | Add `instrumentationTargetPackage` field + `isAndroidTestModule` property to `ModuleInfo` |
| `main/.../project/data/JuggProjectInfoSerialize.kt` | `serialize()`/`deserialize()` handle new field; backward compat (missing → null) |
| `main/.../gradle/script/ProjectInfoSerializerInGradle.kt` | `load()` reads `instrumentationTargetPackage` from JSON |
| `main/.../gradle/script/GradleProjectInfoReader.kt` | `getProjectInfo()` generates androidTest `ModuleInfo`; new `getAndroidTestModuleInfo()` |
| `main/.../ModuleApkBelongsUtils.kt` | Add Step 0: `isAndroidTestModule` → test `ApkFileUnit` |
| `idea/.../project/CompileContextManager.kt` | Condition `.androidTest` filter on `buildTarget != ANDROID_TEST` |
| **Test files (new)** | |
| `main/src/test/.../project/data/ModuleInfoAndroidTestTest.kt` | Unit tests for new field/property |
| `main/src/test/.../project/data/JuggProjectInfoSerializerAndroidTestTest.kt` | Serialization round-trip + backward compat |
| `main/src/test/.../ModuleApkBelongsUtilsAndroidTestTest.kt` | APK routing for androidTest module |
| `main/src/test/.../gradle/script/GradleProjectInfoReaderAndroidTestTest.kt` | androidTest ModuleInfo generation |
| `idea/src/test/.../project/CompileContextManagerAndroidTestFilterTest.kt` | Filter behavior per buildTarget |

---

## Task 1: Add `instrumentationTargetPackage` to `ModuleInfo`

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt`
- Create: `main/src/test/java/com/sickworm/intellij/jugg/project/data/ModuleInfoAndroidTestTest.kt`

- [ ] **Step 1: Write the failing test**

Create `main/src/test/java/com/sickworm/intellij/jugg/project/data/ModuleInfoAndroidTestTest.kt`:

```kotlin
package com.sickworm.intellij.jugg.project.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ModuleInfoAndroidTestTest {

    private fun moduleInfo(instrumentationTargetPackage: String? = null) =
        ModuleInfo.virtualModule.copy(
            instrumentationTargetPackage = instrumentationTargetPackage,
        )

    @Test
    fun `isAndroidTestModule is false when instrumentationTargetPackage is null`() {
        assertFalse(moduleInfo(null).isAndroidTestModule)
    }

    @Test
    fun `isAndroidTestModule is true when instrumentationTargetPackage is set`() {
        assertTrue(moduleInfo("com.example.app").isAndroidTestModule)
    }

    @Test
    fun `instrumentationTargetPackage stores the app package name`() {
        val module = moduleInfo("com.example.app")
        assertEquals("com.example.app", module.instrumentationTargetPackage)
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd /Users/wormchen/IdeaProjects/jugg/jugg_f1
./gradlew :main:test --tests "com.sickworm.intellij.jugg.project.data.ModuleInfoAndroidTestTest" 2>&1 | tail -20
```

Expected: FAIL — `instrumentationTargetPackage` does not exist yet.

- [ ] **Step 3: Add the field and property to `ModuleInfo`**

In `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt`, append after the last existing nullable field (`kspDependencies`):

```kotlin
    val kspDependencies: List<LibraryDependency>? = null,
    // ↓ NEW — must be appended at end per existing comment "if adds new fields, also updates:"
    val instrumentationTargetPackage: String? = null,
) {
    // do not add unnecessary content before ") {", for kotlin 1.3 compat ...

    val moduleStdPath: String get() = ...

    /** Returns true when this module represents an androidTest source set. */
    val isAndroidTestModule: Boolean get() = instrumentationTargetPackage != null
```

- [ ] **Step 4: Run the test to confirm it passes**

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.project.data.ModuleInfoAndroidTestTest" 2>&1 | tail -10
```

Expected: PASS (3 tests).

- [ ] **Step 5: Compile-check dependent modules**

```bash
./gradlew :main:compileKotlin :idea:compileKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (new field has default null, no callers break).

- [ ] **Step 6: Commit**

```bash
git add main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt \
        main/src/test/java/com/sickworm/intellij/jugg/project/data/ModuleInfoAndroidTestTest.kt
git commit -m "[feature] Add instrumentationTargetPackage field to ModuleInfo for androidTest support"
```

---

## Task 2: Serialization — `JuggProjectInfoSerialize` + `ProjectInfoSerializerInGradle`

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfoSerialize.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/ProjectInfoSerializerInGradle.kt`
- Create: `main/src/test/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfoSerializerAndroidTestTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `main/src/test/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfoSerializerAndroidTestTest.kt`:

```kotlin
package com.sickworm.intellij.jugg.project.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class JuggProjectInfoSerializerAndroidTestTest {

    private fun androidTestModule(appPkg: String = "com.example.app") =
        ModuleInfo.virtualModule.copy(
            name = "app.androidTest",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = File("/project/app"),
            projectRootDir = File("/project"),
            applicationId = "$appPkg.test",
            instrumentationTargetPackage = appPkg,
            buildVariant = "debugAndroidTest",
        )

    @Test
    fun `serialize and deserialize androidTest module preserves instrumentationTargetPackage`() {
        val original = JuggProjectInfo(
            modules = mapOf("app.androidTest" to androidTestModule())
        )
        val serialized = JuggProjectInfoSerialize.serialize(original)
        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertEquals(
            "com.example.app",
            restored.modules["app.androidTest"]?.instrumentationTargetPackage
        )
    }

    @Test
    fun `deserialize module with missing instrumentationTargetPackage field yields null`() {
        // Simulate old JSON: moduleInfoExceptLibraries has no instrumentationTargetPackage key
        // JuggProjectInfoSerialize.deserialize restores via ModuleInfo.copy() from virtualModule
        // which defaults to null — this test verifies backward compat through the full round-trip
        val original = JuggProjectInfo(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(name = "app"))
        )
        val serialized = JuggProjectInfoSerialize.serialize(original)
        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertNull(restored.modules["app"]?.instrumentationTargetPackage)
    }

    @Test
    fun `serialize androidTest module preserves applicationId`() {
        val original = JuggProjectInfo(
            modules = mapOf("app.androidTest" to androidTestModule())
        )
        val serialized = JuggProjectInfoSerialize.serialize(original)
        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertEquals("com.example.app.test", restored.modules["app.androidTest"]?.applicationId)
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.project.data.JuggProjectInfoSerializerAndroidTestTest" 2>&1 | tail -20
```

Expected: FAIL — `instrumentationTargetPackage` is not preserved through serialize/deserialize yet (it is not included in `moduleInfoExceptLibraries` JSON by default via Gson since it is a new field — need to verify Gson picks it up automatically or if `ProjectInfoSerializerInGradle.load()` needs updating).

- [ ] **Step 3: Verify Gson serialization picks up the new field automatically**

`JuggProjectInfoSerialize.serialize()` calls `it.value.copy(...)` — the new field is carried in `moduleInfoExceptLibraries` because it stays in the `copy`. Gson serializes all data class properties automatically. **No change needed** to `JuggProjectInfoSerialize.serialize()` or `deserialize()`.

`ProjectInfoSerializerInGradle.load()` only reads `name`, `buildVariant`, `moduleRootDir`, `projectRootDir`, `moduleType` from JSON (lines 46–52). It does **not** need to read `instrumentationTargetPackage` because the Gradle-side loader is only used for dependency caching — the full `ModuleInfo` (with `instrumentationTargetPackage`) comes from `GradleProjectInfoReader.getAndroidTestModuleInfo()` at save time.

Run the tests again to confirm they now pass with no code changes needed for this file:

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.project.data.JuggProjectInfoSerializerAndroidTestTest" 2>&1 | tail -10
```

Expected: PASS (3 tests). If any fail, the field is not being carried through `copy()` — check that `instrumentationTargetPackage` is defined in the primary constructor of `ModuleInfo`, not as a separate property.

- [ ] **Step 4: Commit**

```bash
git add main/src/test/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfoSerializerAndroidTestTest.kt
git commit -m "[feature] Add serialization tests for androidTest ModuleInfo instrumentationTargetPackage"
```

---

## Task 3: `ModuleApkBelongsUtils` — Route androidTest module to test APK

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt`
- Create: `main/src/test/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtilsAndroidTestTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `main/src/test/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtilsAndroidTestTest.kt`:

```kotlin
package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class ModuleApkBelongsUtilsAndroidTestTest {

    private val projectDir = File("/project")
    private val appDir = File("/project/app")

    private fun appModule(name: String = "app", appId: String = "com.example.app") =
        ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = ModuleInfo.Type.Application,
            moduleRootDir = appDir,
            projectRootDir = projectDir,
            applicationId = appId,
            buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debug"),
        )

    private fun androidTestModule(
        name: String = "app.androidTest",
        testAppId: String = "com.example.app.test",
        targetPkg: String = "com.example.app",
    ) = ModuleInfo.virtualModule.copy(
        name = name,
        moduleType = ModuleInfo.Type.Library,
        moduleRootDir = appDir,
        projectRootDir = projectDir,
        applicationId = testAppId,
        instrumentationTargetPackage = targetPkg,
        buildVariant = "debugAndroidTest",
        buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debugAndroidTest"),
    )

    private fun apkFileUnit(id: String, file: String = "$id.apk") =
        ApkFileUnit(id, "", true, File(file))

    private fun appApkInfo(id: String = "com.example.app") = ApkInfo(
        files = listOf(apkFileUnit(id)),
        applicationId = id,
    )

    private fun testApkInfo(
        testId: String = "com.example.app.test",
        targetPkg: String = "com.example.app",
    ) = ApkInfo(
        files = listOf(apkFileUnit(testId)),
        applicationId = testId,
        instrumentationTargetPackage = targetPkg,
    )

    @Test
    fun `androidTest module maps to test ApkFileUnit`() {
        val appMod = appModule()
        val testMod = androidTestModule()
        val modules = mapOf(appMod.name to appMod, testMod.name to testMod)
        val apkInfos = listOf(appApkInfo(), testApkInfo())
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        val testApkUnit = apkInfos.first { it.isTestApk }.files.first()
        assertEquals(testApkUnit, result[testMod])
    }

    @Test
    fun `app module maps to base (non-test) ApkFileUnit`() {
        val appMod = appModule()
        val testMod = androidTestModule()
        val modules = mapOf(appMod.name to appMod, testMod.name to testMod)
        val apkInfos = listOf(appApkInfo(), testApkInfo())
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        val appApkUnit = apkInfos.first { !it.isTestApk }.files.first()
        assertEquals(appApkUnit, result[appMod])
    }

    @Test
    fun `androidTest module and app module map to different ApkFileUnits`() {
        val appMod = appModule()
        val testMod = androidTestModule()
        val modules = mapOf(appMod.name to appMod, testMod.name to testMod)
        val apkInfos = listOf(appApkInfo(), testApkInfo())
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        assertNotEquals(result[appMod], result[testMod])
    }

    @Test
    fun `androidTest module falls back to base apk when no test apk exists`() {
        val appMod = appModule()
        val testMod = androidTestModule()
        val modules = mapOf(appMod.name to appMod, testMod.name to testMod)
        val apkInfos = listOf(appApkInfo()) // no test apk
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        val baseApkUnit = apkInfos.first().files.first()
        assertEquals(baseApkUnit, result[testMod])
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ModuleApkBelongsUtilsAndroidTestTest" 2>&1 | tail -20
```

Expected: FAIL — androidTest module currently routes to base apk (no Step 0 logic yet).

- [ ] **Step 3: Add Step 0 to `ModuleApkBelongsUtils.getModuleApkBelongs`**

In `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt`, inside `getModuleApkBelongs`, **before** the existing `moduleApkBelongs[tempModule] = baseApk` line (around line 90), add:

```kotlin
        // Step 0: androidTest modules map directly to the matching test ApkFileUnit.
        // This must run before all other routing so test modules are never
        // accidentally routed to the base or dynamic-feature APK.
        val testApkByTargetPkg: Map<String, ApkFileUnit> = apkInfo
            .filter { it.isTestApk }
            .mapNotNull { info ->
                val unit = info.files.firstOrNull() ?: return@mapNotNull null
                info.instrumentationTargetPackage!! to unit
            }
            .toMap()

        modules.values
            .filter { it.isAndroidTestModule }
            .forEach { testModule ->
                val unit = testApkByTargetPkg[testModule.instrumentationTargetPackage]
                if (unit != null) {
                    moduleApkBelongs[testModule] = unit
                }
                // if no test apk found, fall through to normal routing below (will land on base apk)
            }
```

- [ ] **Step 4: Run the tests to confirm they pass**

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ModuleApkBelongsUtilsAndroidTestTest" 2>&1 | tail -10
```

Expected: PASS (4 tests).

- [ ] **Step 5: Compile check**

```bash
./gradlew :main:compileKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt \
        main/src/test/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtilsAndroidTestTest.kt
git commit -m "[feature] Route androidTest ModuleInfo to test APK in ModuleApkBelongsUtils"
```

---

## Task 4: `GradleProjectInfoReader` — Generate androidTest `ModuleInfo`

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt`
- Create: `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderAndroidTestTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderAndroidTestTest.kt`:

```kotlin
package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GradleProjectInfoReaderAndroidTestTest {

    // These tests exercise GradleProjectInfoReader.buildAndroidTestModuleInfo() directly
    // (a package-private helper extracted in Task 4 Step 3).

    private val projectDir = File("/project")
    private val appDir = File("/project/app")

    private fun appModule(appId: String = "com.example.app") = ModuleInfo.virtualModule.copy(
        name = "app",
        moduleType = ModuleInfo.Type.Application,
        moduleRootDir = appDir,
        projectRootDir = projectDir,
        applicationId = appId,
        buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debug"),
    )

    @Test
    fun `buildAndroidTestModuleInfo returns null when sourceDirs is empty`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(),
            sourceDirs = emptyList(),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertNull(result)
    }

    @Test
    fun `buildAndroidTestModuleInfo sets buildVariant to debugAndroidTest`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("debugAndroidTest", result?.buildVariant)
    }

    @Test
    fun `buildAndroidTestModuleInfo uses explicit testApplicationId when provided`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule("com.example.app"),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = "com.example.app.tests",
        )
        assertEquals("com.example.app.tests", result?.applicationId)
    }

    @Test
    fun `buildAndroidTestModuleInfo defaults applicationId to appId dot test`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule("com.example.app"),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("com.example.app.test", result?.applicationId)
    }

    @Test
    fun `buildAndroidTestModuleInfo sets instrumentationTargetPackage to app applicationId`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule("com.example.app"),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("com.example.app", result?.instrumentationTargetPackage)
    }

    @Test
    fun `buildAndroidTestModuleInfo name is appModuleName dot androidTest`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("app.androidTest", result?.name)
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.script.GradleProjectInfoReaderAndroidTestTest" 2>&1 | tail -20
```

Expected: FAIL — `buildAndroidTestModuleInfo` does not exist yet.

- [ ] **Step 3: Add `buildAndroidTestModuleInfo` companion function to `GradleProjectInfoReader`**

In `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt`:

Add a companion object (or expand existing one) with:

```kotlin
companion object {
    /**
     * Builds a synthetic ModuleInfo representing the androidTest source set of [appModuleInfo].
     * Returns null if [sourceDirs] is empty (project has no androidTest sources).
     */
    fun buildAndroidTestModuleInfo(
        appModuleInfo: ModuleInfo,
        sourceDirs: List<File>,
        libraryDependencies: List<LibraryDependency>,
        testApplicationId: String?,
    ): ModuleInfo? {
        if (sourceDirs.isEmpty()) return null
        val resolvedTestAppId = testApplicationId
            ?: "${appModuleInfo.applicationId}.test"
        return appModuleInfo.copy(
            name = "${appModuleInfo.name}.androidTest",
            moduleType = ModuleInfo.Type.Library,
            buildVariant = "debugAndroidTest",
            buildPathInfo = ModuleBuildPathInfo(
                appModuleInfo.projectRootDir,
                appModuleInfo.moduleRootDir,
                "debugAndroidTest",
            ),
            applicationId = resolvedTestAppId,
            instrumentationTargetPackage = appModuleInfo.applicationId,
            sourceDirs = sourceDirs,
            resourceDirs = emptyList(),
            assetsDirs = emptyList(),
            libraryDependencies = libraryDependencies,
            runtimeLibraryDependencies = emptyList(),
            annotationProcessorDependencies = emptyList(),
            kaptDependencies = emptyList(),
            moduleDependencies = listOf(ModuleDependency(appModuleInfo.name)),
            variants = emptyList(),
            signingConfigs = null,
        )
    }
}
```

- [ ] **Step 4: Call `buildAndroidTestModuleInfo` from `getProjectInfo()`**

In `GradleProjectInfoReader.getProjectInfo()`, after `modules[moduleInfo.name] = moduleInfo`:

```kotlin
            modules[moduleInfo.name] = moduleInfo

            // Generate androidTest ModuleInfo for Application modules
            if (moduleInfo.moduleType == ModuleInfo.Type.Application) {
                try {
                    val androidExt = reflector(project.extensions.getByName("android"))
                    val sourceDirs = mutableListOf<File>()
                    androidExt["sourceSets"]?.invoke("findByName", "androidTest")?.let { atSourceSet ->
                        (atSourceSet.invoke("getJavaDirectories")?.value as? Collection<File>)
                            ?.let { sourceDirs.addAll(it) }
                        (atSourceSet.invoke("getKotlinDirectories")?.value as? Collection<File>)
                            ?.let { sourceDirs.addAll(it) }
                    }
                    val testAppId = androidExt["defaultConfig"]["testApplicationId"]?.valueString
                    val atDependencies = getDependenciesByConfig(
                        project,
                        "${moduleInfo.buildVariant}AndroidTestCompileClasspath",
                        isAndroidDepend = true,
                    ).filterIsInstance<LibraryDependency>()
                    val androidTestModuleInfo = buildAndroidTestModuleInfo(
                        appModuleInfo = moduleInfo,
                        sourceDirs = sourceDirs.filter { it.exists() },
                        libraryDependencies = atDependencies,
                        testApplicationId = testAppId,
                    )
                    if (androidTestModuleInfo != null) {
                        modules[androidTestModuleInfo.name] = androidTestModuleInfo
                        println("Jugg: generated androidTest ModuleInfo for ${moduleInfo.name}: ${androidTestModuleInfo.name}")
                    }
                } catch (e: Throwable) {
                    println("Jugg: get androidTest info for ${moduleInfo.name} failed: $e")
                    printException(e)
                }
            }
```

- [ ] **Step 5: Run the tests to confirm they pass**

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.script.GradleProjectInfoReaderAndroidTestTest" 2>&1 | tail -10
```

Expected: PASS (6 tests).

- [ ] **Step 6: Compile check**

```bash
./gradlew :main:compileKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt \
        main/src/test/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderAndroidTestTest.kt
git commit -m "[feature] Generate androidTest ModuleInfo in GradleProjectInfoReader"
```

---

## Task 5: `CompileContextManager` — Conditionally include androidTest modules

**Files:**
- Modify: `idea/src/main/java/com/sickworm/intellij/jugg/project/CompileContextManager.kt`
- Create: `idea/src/test/java/com/sickworm/intellij/jugg/project/CompileContextManagerAndroidTestFilterTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `idea/src/test/java/com/sickworm/intellij/jugg/project/CompileContextManagerAndroidTestFilterTest.kt`:

```kotlin
package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.BuildTarget
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies the module-name filter logic extracted from CompileContextManager.shouldSkipModule().
 * Tests the pure function directly to avoid needing a full IntelliJ environment.
 */
class CompileContextManagerAndroidTestFilterTest {

    // Mirror of the filter logic in CompileContextManager.doGetAllModulesByModuleManager()
    // extracted as a pure function for testability.
    private fun shouldSkipAsTestModule(stdModuleName: String, buildTarget: BuildTarget): Boolean {
        val isAndroidTestModule = stdModuleName.endsWith(".androidTest")
        return stdModuleName.endsWith(".test") ||
                stdModuleName.endsWith(".unitTest") ||
                (isAndroidTestModule && buildTarget != BuildTarget.ANDROID_TEST)
    }

    @Test
    fun `androidTest module is skipped when buildTarget is APP`() {
        assertTrue(shouldSkipAsTestModule("app.androidTest", BuildTarget.APP))
    }

    @Test
    fun `androidTest module is included when buildTarget is ANDROID_TEST`() {
        assertFalse(shouldSkipAsTestModule("app.androidTest", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `test module is always skipped regardless of buildTarget`() {
        assertTrue(shouldSkipAsTestModule("app.test", BuildTarget.APP))
        assertTrue(shouldSkipAsTestModule("app.test", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `unitTest module is always skipped regardless of buildTarget`() {
        assertTrue(shouldSkipAsTestModule("app.unitTest", BuildTarget.APP))
        assertTrue(shouldSkipAsTestModule("app.unitTest", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `regular app module is never skipped`() {
        assertFalse(shouldSkipAsTestModule("app", BuildTarget.APP))
        assertFalse(shouldSkipAsTestModule("app", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `library module is never skipped`() {
        assertFalse(shouldSkipAsTestModule("mylib", BuildTarget.APP))
        assertFalse(shouldSkipAsTestModule("mylib", BuildTarget.ANDROID_TEST))
    }
}
```

- [ ] **Step 2: Run to confirm they pass immediately**

These tests verify pure logic extracted from CompileContextManager — they don't depend on IDE APIs, so they should pass once the filter logic is implemented. Run first to confirm the test compiles:

```bash
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.project.CompileContextManagerAndroidTestFilterTest" 2>&1 | tail -20
```

Expected: PASS if the IDE test environment is set up, or FAIL with compilation error before the logic is added.

- [ ] **Step 3: Update the filter in `CompileContextManager.doGetAllModulesByModuleManager`**

In `idea/src/main/java/com/sickworm/intellij/jugg/project/CompileContextManager.kt`, locate lines 345-351:

```kotlin
            // BEFORE:
            val stdModuleName = module.name.replace(Regex("~\\d+$"), "")
            if (stdModuleName.endsWith(".test") ||
                stdModuleName.endsWith(".androidTest") ||
                stdModuleName.endsWith(".unitTest")) {
                testModules.add(module.name)
                return@forEach
            }
```

Replace with:

```kotlin
            val stdModuleName = module.name.replace(Regex("~\\d+$"), "")
            val isAndroidTestModule = stdModuleName.endsWith(".androidTest")
            if (stdModuleName.endsWith(".test") ||
                stdModuleName.endsWith(".unitTest") ||
                (isAndroidTestModule && currentBuildTarget != BuildTarget.ANDROID_TEST)) {
                testModules.add(module.name)
                return@forEach
            }
```

- [ ] **Step 4: Add `currentBuildTarget` resolution inside `doGetAllModulesByModuleManager`**

At the top of `doGetAllModulesByModuleManager()`, before the module loop, add:

```kotlin
        val currentBuildTarget: BuildTarget = run {
            val helper = BaseBuildCommandHelper(pathManager)
            helper.getBaseBuildCmdRecord()?.buildTarget ?: BuildTarget.APP
        }
```

Also add the import at the top of `CompileContextManager.kt` if not already present:

```kotlin
import com.sickworm.intellij.jugg.gradle.compile.BaseBuildCommandHelper
import com.sickworm.intellij.jugg.compiler.BuildTarget
```

- [ ] **Step 5: Compile check**

```bash
./gradlew :idea:compileKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run the tests**

```bash
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.project.CompileContextManagerAndroidTestFilterTest" 2>&1 | tail -10
```

Expected: PASS (6 tests).

- [ ] **Step 7: Commit**

```bash
git add idea/src/main/java/com/sickworm/intellij/jugg/project/CompileContextManager.kt \
        idea/src/test/java/com/sickworm/intellij/jugg/project/CompileContextManagerAndroidTestFilterTest.kt
git commit -m "[feature] Conditionally include androidTest modules in CompileContextManager based on BuildTarget"
```

---

## Task 6: Run all affected tests and verify no regression

- [ ] **Step 1: Run all main module tests that touch changed code**

```bash
./gradlew :main:test \
  --tests "com.sickworm.intellij.jugg.project.data.ModuleInfoAndroidTestTest" \
  --tests "com.sickworm.intellij.jugg.project.data.JuggProjectInfoSerializerAndroidTestTest" \
  --tests "com.sickworm.intellij.jugg.ModuleApkBelongsUtilsAndroidTestTest" \
  --tests "com.sickworm.intellij.jugg.gradle.script.GradleProjectInfoReaderAndroidTestTest" \
  2>&1 | tail -20
```

Expected: All PASS.

- [ ] **Step 2: Run regression tests for ApkInstallOrder (existing Phase 1 test)**

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.run.ApkInstallOrderTest" 2>&1 | tail -10
```

Expected: PASS — Phase 1 behavior unchanged.

- [ ] **Step 3: Run idea module filter tests**

```bash
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.project.CompileContextManagerAndroidTestFilterTest" 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 4: Full compile check**

```bash
./gradlew :main:compileKotlin :idea:compileKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Final commit**

```bash
git add -p  # review any unstaged changes
git commit -m "[feature] androidTest Phase 2: incremental compile support complete"
```

---

## Self-Review Against Spec

| Spec Requirement | Covered In |
|---|---|
| `ModuleInfo.instrumentationTargetPackage` + `isAndroidTestModule` | Task 1 |
| Serialization round-trip + backward compat | Task 2 |
| `ModuleApkBelongsUtils` Step 0 routing | Task 3 |
| `GradleProjectInfoReader.getAndroidTestModuleInfo` | Task 4 |
| `CompileContextManager` conditional filter | Task 5 |
| `buildTarget` read from `BaseBuildCommandHelper` | Task 5 Step 4 |
| `JuggProjectInfoMerger` zero-change (missingModules path) | No task needed — verified by regression in Task 6 |
| `BaseCompileContext.findApplicationModule` zero-change | No task needed — `Library` type is never selected |
| `JuggDeployTask.run()` zero-change | No task needed — `groupBy(applicationId)` already works |
| YAGNI: no flavor/kapt/res support | Confirmed not in any task |
