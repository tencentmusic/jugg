# Module APK Belongs Multi-Mapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the raw module-to-APK map with a small ownership wrapper that preserves current single-APK behavior while allowing test APK multi-mapping to be added later without another structural rewrite.

**Architecture:** Introduce a dedicated ownership container with `getBelongsApk(module)` for the current primary APK view and `getAllBelongsApk(module)` for the future multi-target view. Keep existing consumers on the single-APK path unless they explicitly need test APK fan-out. Update compile and deploy call sites to read through the wrapper, then add targeted tests for the preserved semantics and the new multi-target accessors.

**Tech Stack:** Kotlin, JUnit4, Mockito, existing Jugg test fixtures.

---

### Task 1: Add ownership wrapper tests

**Files:**
- Modify: `main/src/test/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtilsAndroidTestTest.kt`
- Modify: `main/src/test/java/com/sickworm/intellij/jugg/compiler/BaseCompilerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `module belongs wrapper returns primary apk and all apks`() {
    // build a context with one app apk and one test apk
    // verify getBelongsApk(appModule) returns the base apk
    // verify getAllBelongsApk(appModule) contains both base and test apk
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :main:test --tests "com.sickworm.intellij.jugg.ModuleApkBelongsUtilsAndroidTestTest"`
Expected: fail because the wrapper API does not exist yet.

- [ ] **Step 3: Write minimal implementation**

No implementation in this task.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :main:test --tests "com.sickworm.intellij.jugg.ModuleApkBelongsUtilsAndroidTestTest"`
Expected: pass after the wrapper exists.

- [ ] **Step 5: Commit**

```bash
git add main/src/test/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtilsAndroidTestTest.kt
git commit -m "[feature] add apk belongs wrapper coverage"
```

### Task 2: Introduce module ownership wrapper

**Files:**
- Add: `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongs.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/project/BaseCompileContext.kt`
- Modify: `main/src/test/java/com/sickworm/intellij/jugg/mock/SimpleCompileContext.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// compile tests should still read a single primary APK through getBelongsApk()
// and should expose getAllBelongsApk() for future multi-target routing
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.BaseCompilerTest"`
Expected: fail because the new wrapper is not wired in yet.

- [ ] **Step 3: Write minimal implementation**

Add the wrapper class, keep current single-APK behavior as the default view, and expose the future multi-APK list accessor.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.BaseCompilerTest"`
Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongs.kt main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt main/src/main/java/com/sickworm/intellij/jugg/project/BaseCompileContext.kt main/src/test/java/com/sickworm/intellij/jugg/mock/SimpleCompileContext.kt
git commit -m "[refactor] wrap module apk ownership"
```

### Task 3: Rewire compile and deploy consumers

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompilerInvoker.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/StyleableFileGenerator.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/project/BaseCompileContext.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/deploy/CompileEffectAnalyzer.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/OverlayUpdateBuilder.kt`
- Modify: `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployTask.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// verify existing compile flow still uses the primary APK path
// verify deploy-side grouping still works for the current single-APK case
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.BaseCompilerTest"`
Expected: fail until all consumers use the wrapper API.

- [ ] **Step 3: Write minimal implementation**

Switch consumers from raw map access to `getBelongsApk(...)` and keep `getAllBelongsApk(...)` unused until test APK fan-out is added.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.BaseCompilerTest"`
Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompilerInvoker.kt main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/StyleableFileGenerator.kt main/src/main/java/com/sickworm/intellij/jugg/project/BaseCompileContext.kt main/src/main/java/com/sickworm/intellij/jugg/deploy/CompileEffectAnalyzer.kt main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt main/src/main/java/com/sickworm/intellij/jugg/deploy/run/OverlayUpdateBuilder.kt idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployTask.kt
git commit -m "[refactor] route compile deploy through ownership wrapper"
```

### Task 4: Add multi-target test apk routing

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt`
- Modify: `main/src/test/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtilsAndroidTestTest.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/OverlayUpdateBuilder.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `temp module and androidTest module can expose both base and test apks`() {
    // build app + test apk inputs
    // verify getAllBelongsApk(tempModule) includes both base and test apk
    // verify getBelongsApk(tempModule) still returns the base apk
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :main:test --tests "com.sickworm.intellij.jugg.ModuleApkBelongsUtilsAndroidTestTest"`
Expected: fail until multi-target routing is implemented.

- [ ] **Step 3: Write minimal implementation**

Populate the wrapper with both primary and related APKs for the test APK case, then use `getAllBelongsApk(...)` only where the fan-out is required.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :main:test --tests "com.sickworm.intellij.jugg.ModuleApkBelongsUtilsAndroidTestTest"`
Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt main/src/test/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtilsAndroidTestTest.kt main/src/main/java/com/sickworm/intellij/jugg/deploy/run/OverlayUpdateBuilder.kt
git commit -m "[feature] support multi apk module ownership"
```
