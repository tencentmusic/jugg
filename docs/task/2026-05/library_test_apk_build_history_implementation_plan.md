# Library Test APK Build History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist successful library Test APK backfill builds and replay the latest valid records during Jugg AndroidTest Gradle builds.

**Architecture:** Add an independent global history store under `~/.jugg/library_test_build_records`, write records after successful backfill, select recent records when `BuildTarget.ANDROID_TEST` Gradle builds run, pass selected tasks to the existing Gradle init script injection point, and pass selected output patterns to Gradle compile clients for non-blocking APK collection.

**Tech Stack:** Kotlin, Gson, JGit, Gradle init script Kotlin, JUnit4, Mockito.

---

## File Structure

- Create `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/LibraryTestApkBuildHistory.kt`
  - Owns JSON serialization, global record file resolution, record upsert, and AndroidTest replay filtering.
- Create `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/LibraryTestApkBuildHistoryTest.kt`
  - Covers file naming, upsert behavior, 30-day filtering, variant filtering, module existence filtering, and max-3 replay selection.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt`
  - Exposes `libraryTestBuildRecordDir`.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/git/IGitManager.kt`
  - Adds origin-first remote URL access for project key derivation.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/git/GitManager.kt`
  - Reads remote URLs from git config with origin-first selection available to history.
- Modify `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/LibraryTestApkBackfillHelper.kt`
  - Writes history only after successful compile, APK merge, install, and compile context update.
- Modify `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/LibraryTestApkBackfillHelperTest.kt`
  - Verifies success writes history and failure paths do not write.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggGradleCompileOptions.kt`
  - Carries selected library test tasks and output patterns for this run only.
- Modify `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt`
  - Applies AndroidTest-only history selection before launching Gradle compile.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/SshCommand.kt`
  - Passes selected library test tasks into Gradle as a property.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderManager.kt`
  - Injects selected library test tasks in `injectAndroidTestTaskIfNeeded()` beside application test APK injection.
- Modify `main/src/main/resources/gradle/readProjectInfo.gradle.kts`
  - Mirrors the Gradle init script source update.
- Modify `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/ReadProjectInfoScriptContentTest.kt`
  - Guards that generated init script contains library task injection at the same phase.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/LocalGradleCompileClient.kt`
  - Collects extra library Test APK patterns as optional outputs.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/RemoteGradleCompileClient.kt`
  - Collects extra remote library Test APK patterns as optional outputs.
- Modify `main/src/test/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriverTest.kt`
  - Adds focused tests for optional extra output collection helper behavior if helper functions are extracted.
- Modify `docs/ai_knowledge/06_android_test.md`
  - Documents history write, replay filters, and warning-only missing APK behavior.
- Modify `docs/ai_knowledge/98_code_map.md`
  - Adds `LibraryTestApkBuildHistory` to AndroidTest model entries.

---

### Task 1: Add Global History Store

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/git/IGitManager.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/git/GitManager.kt`
- Create: `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/LibraryTestApkBuildHistory.kt`
- Test: `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/LibraryTestApkBuildHistoryTest.kt`

- [ ] **Step 1: Write failing tests for store path, upsert, and replay selection**

Create `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/LibraryTestApkBuildHistoryTest.kt`:

```kotlin
package com.sickworm.intellij.jugg.deploy.instrument

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryTestApkBuildHistoryTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `record file uses project name and first eight hash chars`() {
        val recordDir = temp.newFolder("records")
        val history = LibraryTestApkBuildHistory(
            projectDir = temp.newFolder("project"),
            recordDir = recordDir,
            logger = Logger.getInstance(LibraryTestApkBuildHistoryTest::class.java),
            gitInfoProvider = { GitProjectInfo(projectName = "demo", projectKey = "git@example.com:team/demo.git") },
        )

        val file = history.recordFile()

        assertEquals(recordDir, file.parentFile)
        assertTrue(file.name.startsWith("demo_hash"))
        assertTrue(file.name.endsWith(".json"))
        assertEquals(8, file.name.removeSuffix(".json").substringAfter("_hash").length)
    }

    @Test
    fun `upsert keeps one record per module and variant`() {
        val history = createHistory()

        history.record(
            LibraryTestApkBuildRecord(
                moduleName = "library1.androidTest",
                buildVariant = "debugAndroidTest",
                compileCommand = "./gradlew :library1:assembleDebugAndroidTest",
                compiledAt = 1000L,
                apkPath = "/old.apk",
                outputApkPattern = "library1/build/outputs/apk/androidTest/debug/*.apk",
            )
        )
        history.record(
            LibraryTestApkBuildRecord(
                moduleName = "library1.androidTest",
                buildVariant = "debugAndroidTest",
                compileCommand = "./gradlew :library1:assembleDebugAndroidTest",
                compiledAt = 2000L,
                apkPath = "/new.apk",
                outputApkPattern = "library1/build/outputs/apk/androidTest/debug/*.apk",
            )
        )

        val records = history.load().records

        assertEquals(1, records.size)
        assertEquals("/new.apk", records.single().apkPath)
        assertEquals(2000L, records.single().compiledAt)
    }

    @Test
    fun `select recent records filters by target variant module existence and max three`() {
        val history = createHistory()
        val now = 40L * 24 * 60 * 60 * 1000
        listOf(
            record("library1.androidTest", "debugAndroidTest", now - 1, ":library1:assembleDebugAndroidTest"),
            record("library2.androidTest", "debugAndroidTest", now - 2, ":library2:assembleDebugAndroidTest"),
            record("library3.androidTest", "debugAndroidTest", now - 3, ":library3:assembleDebugAndroidTest"),
            record("library4.androidTest", "debugAndroidTest", now - 4, ":library4:assembleDebugAndroidTest"),
            record("old.androidTest", "debugAndroidTest", now - 31L * 24 * 60 * 60 * 1000, ":old:assembleDebugAndroidTest"),
            record("flavor.androidTest", "developmentDebugAndroidTest", now - 1, ":flavor:assembleDevelopmentDebugAndroidTest"),
            record("missing.androidTest", "debugAndroidTest", now - 1, ":missing:assembleDebugAndroidTest"),
        ).forEach(history::record)
        val modules = listOf("library1.androidTest", "library2.androidTest", "library3.androidTest", "library4.androidTest")
            .associateWith { ModuleInfo.virtualModule.copy(name = it) }

        val selected = history.selectRecentForAndroidTest(
            modules = modules,
            buildVariant = "debugAndroidTest",
            nowMillis = now,
            requestedTasks = setOf(":library2:assembleDebugAndroidTest"),
        )

        assertEquals(
            listOf(":library1:assembleDebugAndroidTest", ":library3:assembleDebugAndroidTest", ":library4:assembleDebugAndroidTest"),
            selected.map { it.gradleTask }
        )
    }

    private fun createHistory(): LibraryTestApkBuildHistory {
        return LibraryTestApkBuildHistory(
            projectDir = temp.newFolder("project"),
            recordDir = temp.newFolder("records"),
            logger = Logger.getInstance(LibraryTestApkBuildHistoryTest::class.java),
            gitInfoProvider = { GitProjectInfo(projectName = "demo", projectKey = temp.root.absolutePath) },
        )
    }

    private fun record(moduleName: String, variant: String, time: Long, task: String): LibraryTestApkBuildRecord {
        return LibraryTestApkBuildRecord(
            moduleName = moduleName,
            buildVariant = variant,
            compileCommand = "./gradlew $task",
            compiledAt = time,
            apkPath = "/$moduleName.apk",
            outputApkPattern = moduleName.substringBefore(".androidTest") + "/build/outputs/apk/androidTest/debug/*.apk",
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBuildHistoryTest"
```

Expected: FAIL because `LibraryTestApkBuildHistory`, `GitProjectInfo`, and `LibraryTestApkBuildRecord` do not exist.

- [ ] **Step 3: Add path and git remote access**

Update `main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt` constructor and property:

```kotlin
class JuggPathManager(
    val projectDir: File,
    val juggRootDir: File = File("$projectDir/build/jugg"),
    val globalJuggRootDir: File = File(System.getProperty("user.home"), ".jugg"),
) {
    val libraryTestBuildRecordDir: File = File(globalJuggRootDir, "library_test_build_records")
```

Update `main/src/main/java/com/sickworm/intellij/jugg/git/IGitManager.kt`:

```kotlin
    /**
     * origin remote url when available.
     */
    val originRemoteUrl: String?

    /**
     * Git remote urls in config order. Empty when the repository has no remote or git is unavailable.
     */
    val remoteUrls: List<String>
```

Update `main/src/main/java/com/sickworm/intellij/jugg/git/GitManager.kt`:

```kotlin
    override val originRemoteUrl: String? get() {
        return try {
            repository?.config?.getString("remote", "origin", "url")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    override val remoteUrls: List<String> get() {
        return try {
            val config = repository?.config ?: return emptyList()
            config.getSubsections("remote").mapNotNull { remote ->
                config.getString("remote", remote, "url")?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
```

- [ ] **Step 4: Implement history store**

Create `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/LibraryTestApkBuildHistory.kt`:

```kotlin
package com.sickworm.intellij.jugg.deploy.instrument

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.security.MessageDigest

/**
 * LibraryTestApkBuildHistory persists successful library Test APK backfill builds for later AndroidTest Gradle warmup.
 */
class LibraryTestApkBuildHistory(
    private val projectDir: File,
    private val recordDir: File = JuggPathManager(projectDir).libraryTestBuildRecordDir,
    private val logger: Logger,
    private val gitInfoProvider: () -> GitProjectInfo = { resolveGitProjectInfo(projectDir) },
) {
    fun recordFile(): File {
        val info = gitInfoProvider()
        val projectName = sanitizeFileName(info.projectName.ifBlank { projectDir.name })
        return File(recordDir, "${projectName}_hash${sha256(info.projectKey.trim()).take(8)}.json")
    }

    fun load(): LibraryTestApkBuildHistoryData {
        val file = recordFile()
        if (!file.exists()) {
            return LibraryTestApkBuildHistoryData(projectKey = gitInfoProvider().projectKey)
        }
        return try {
            Gson().fromJson(file.readText(Charsets.UTF_8), LibraryTestApkBuildHistoryData::class.java)
                ?: LibraryTestApkBuildHistoryData(projectKey = gitInfoProvider().projectKey)
        } catch (e: Exception) {
            logger.warn("Failed to load library Test APK build history from ${file.absolutePath}", e)
            LibraryTestApkBuildHistoryData(projectKey = gitInfoProvider().projectKey)
        }
    }

    fun record(record: LibraryTestApkBuildRecord) {
        val oldData = load()
        val records = oldData.records
            .filterNot { it.moduleName == record.moduleName && it.buildVariant == record.buildVariant }
            .plus(record)
            .sortedByDescending { it.compiledAt }
        save(oldData.copy(updatedAt = System.currentTimeMillis(), records = records))
    }

    fun selectRecentForAndroidTest(
        modules: Map<String, ModuleInfo>,
        buildVariant: String,
        nowMillis: Long = System.currentTimeMillis(),
        requestedTasks: Set<String> = emptySet(),
    ): List<LibraryTestApkBuildReplayRecord> {
        val minTime = nowMillis - THIRTY_DAYS_MS
        return load().records
            .asSequence()
            .filter { it.compiledAt >= minTime }
            .filter { it.buildVariant == buildVariant }
            .filter { modules.containsKey(it.moduleName) }
            .mapNotNull { record ->
                val task = record.gradleTask() ?: return@mapNotNull null
                if (task in requestedTasks) return@mapNotNull null
                LibraryTestApkBuildReplayRecord(task, record.outputApkPattern, record)
            }
            .distinctBy { it.gradleTask }
            .sortedByDescending { it.source.compiledAt }
            .take(3)
            .toList()
    }

    private fun save(data: LibraryTestApkBuildHistoryData) {
        val file = recordFile()
        file.parentFile?.mkdirs()
        file.writeText(Gson().toJson(data), Charsets.UTF_8)
    }

    private fun LibraryTestApkBuildRecord.gradleTask(): String? {
        return compileCommand.split(Regex("\\s+")).firstOrNull { it.startsWith(":") && it.contains("AndroidTest") }
    }

    companion object {
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

        fun resolveGitProjectInfo(projectDir: File): GitProjectInfo {
            val gitManager = GitManager.createGitManagerAndTrySearchParent(projectDir)
            if (!gitManager.hasInitGit) {
                return GitProjectInfo(projectDir.name, projectDir.absolutePath)
            }
            val remoteUrl = gitManager.originRemoteUrl ?: gitManager.remoteUrls.firstOrNull()
            val key = remoteUrl?.trim()?.takeIf { it.isNotEmpty() } ?: projectDir.absolutePath
            return GitProjectInfo(gitManager.name ?: projectDir.name, key)
        }

        private fun sha256(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun sanitizeFileName(value: String): String {
            return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
        }
    }
}

data class GitProjectInfo(
    val projectName: String,
    val projectKey: String,
)

data class LibraryTestApkBuildHistoryData(
    val version: Int = 1,
    val projectKey: String,
    val updatedAt: Long = 0L,
    val records: List<LibraryTestApkBuildRecord> = emptyList(),
)

data class LibraryTestApkBuildRecord(
    val moduleName: String,
    val buildVariant: String,
    val compileCommand: String,
    val compiledAt: Long,
    val apkPath: String,
    val outputApkPattern: String,
)

data class LibraryTestApkBuildReplayRecord(
    val gradleTask: String,
    val outputApkPattern: String,
    val source: LibraryTestApkBuildRecord,
)
```

- [ ] **Step 5: Run tests to verify they pass**

Run:

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBuildHistoryTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt \
  main/src/main/java/com/sickworm/intellij/jugg/git/IGitManager.kt \
  main/src/main/java/com/sickworm/intellij/jugg/git/GitManager.kt \
  main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/LibraryTestApkBuildHistory.kt \
  main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/LibraryTestApkBuildHistoryTest.kt
git commit -m "[feature] add library test apk build history store"
```

---

### Task 2: Record Backfill Success

**Files:**
- Modify: `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/LibraryTestApkBackfillHelper.kt`
- Modify: `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/LibraryTestApkBackfillHelperTest.kt`

- [ ] **Step 1: Write failing backfill history tests**

In `LibraryTestApkBackfillHelperTest.kt`, add to `missing self targeting library test apk runs only the owning androidTest task`:

```kotlin
        assertEquals(
            listOf("library1.androidTest:debugAndroidTest:./gradlew :library1:assembleDebugAndroidTest"),
            helperContext.recordedHistory
        )
```

Extend `HelperContext`:

```kotlin
        val recordedHistory = mutableListOf<String>()
```

In `createHelper`, pass a recorder callback:

```kotlin
                recordBuildHistory = { module, plan, resultApks ->
                    recordedHistory += "${module.name}:${module.buildVariant}:${compileClient.compileCommand}"
                },
```

Add a failure test:

```kotlin
    @Test
    fun `failed backfill does not record build history`() {
        val projectDir = temp.newFolder("project")
        val sourceRoot = File(projectDir, "library1/src/androidTest/kotlin").apply { mkdirs() }
        val sourceFile = File(sourceRoot, "FooTest.kt").apply { writeText("class FooTest") }
        val module = androidTestModule(projectDir, sourceRoot)
        val compileClient = RecordingCompileClient(File(projectDir, "missing.apk"), isSuccess = false)
        val helperContext = createHelper(projectDir, module, compileClient, onApksBackfilled = {})

        try {
            helperContext.helper.backfillIfNeeded(
                spec = AndroidTestRunSpec(null, null, sourcePath = sourceFile.path),
                data = JuggDeployData.forInstall(emptyList()),
                uiHandler = RecordingUiHandler(),
            )
        } catch (_: AndroidTestTargetResolveException) {
            // expected
        }

        assertEquals(emptyList<String>(), helperContext.recordedHistory)
    }
```

Update `RecordingCompileClient` constructor:

```kotlin
    private class RecordingCompileClient(
        private val apkFile: File,
        private val isSuccess: Boolean = true,
    ) : IGradleCompileClient {
        override fun compileAndFetchResult(isOnlyFetchResult: Boolean): GradleCompileResult {
            return if (isSuccess) GradleCompileResult.success(listOf(apkFile)) else GradleCompileResult.failed(false, "boom")
        }
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.LibraryTestApkBackfillHelperTest"
```

Expected: FAIL because `recordBuildHistory` does not exist.

- [ ] **Step 3: Add history recording hook**

Modify `LibraryTestApkBackfillHelper` constructor:

```kotlin
    private val recordBuildHistory: (
        module: ModuleInfo,
        plan: LibraryTestApkBackfillPlan,
        compileCommand: String,
        apks: List<ApkInfo>,
    ) -> Unit = { module, plan, compileCommand, apks ->
        LibraryTestApkBuildHistory(pathManager.projectDir, logger = logger).record(
            LibraryTestApkBuildRecord(
                moduleName = module.name,
                buildVariant = module.buildVariant,
                compileCommand = compileCommand,
                compiledAt = System.currentTimeMillis(),
                apkPath = apks.firstOrNull()?.files?.firstOrNull()?.apkFile?.absolutePath.orEmpty(),
                outputApkPattern = plan.outputApkPattern,
            )
        )
    },
```

Add imports:

```kotlin
import com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBuildHistory
import com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBuildRecord
```

After `compileContextManager.updateApkInfos(mergedApks)`, record success:

```kotlin
        recordBuildHistory(module, plan, createBackfillOptions(plan).compileCommand, newApks)
```

To avoid recomputing options, change the compile block to:

```kotlin
        val backfillOptions = createBackfillOptions(plan)
        val result = JuggGradleCompileTask(
            project = project,
            compileClient = compileClientFactory(),
            juggGradleCompileOptions = backfillOptions,
            uiHandler = uiHandler,
            isOnlyFetchResult = false,
            logger = logger,
        ).run()
```

Then use `backfillOptions.compileCommand` in the recorder call.

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.LibraryTestApkBackfillHelperTest"
```

Expected: PASS. If unrelated `JuggRunningTaskTest.kt` compile errors appear, record them as pre-existing and run `./gradlew :idea:compileKotlin` after implementation.

- [ ] **Step 5: Commit**

```bash
git add idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/LibraryTestApkBackfillHelper.kt \
  idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/LibraryTestApkBackfillHelperTest.kt
git commit -m "[feature] record library test apk backfill builds"
```

---

### Task 3: Select History Records for AndroidTest Gradle Builds

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggGradleCompileOptions.kt`
- Modify: `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt`
- Test: `main/src/test/java/com/sickworm/intellij/jugg/ide/bean/JuggGradleCompileOptionsTest.kt`

- [ ] **Step 1: Write failing compile options test**

In `JuggGradleCompileOptionsTest.kt`, add:

```kotlin
    @Test
    fun `copy keeps library test replay fields`() {
        val options = createOptions().copy(
            libraryTestApkGradleTasks = listOf(":library1:assembleDebugAndroidTest"),
            libraryTestApkOutputPatterns = listOf("library1/build/outputs/apk/androidTest/debug/*.apk"),
        )

        assertEquals(listOf(":library1:assembleDebugAndroidTest"), options.libraryTestApkGradleTasks)
        assertEquals(listOf("library1/build/outputs/apk/androidTest/debug/*.apk"), options.libraryTestApkOutputPatterns)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptionsTest"
```

Expected: FAIL because new properties do not exist.

- [ ] **Step 3: Add run-only replay fields**

Modify `JuggGradleCompileOptions`:

```kotlin
    /**
     * Library androidTest tasks replayed from recent successful backfill history.
     */
    val libraryTestApkGradleTasks: List<String> = emptyList(),
    /**
     * Optional APK lookup patterns for replayed library androidTest tasks.
     */
    val libraryTestApkOutputPatterns: List<String> = emptyList(),
```

- [ ] **Step 4: Apply selection in Gradle compile helper**

In `JuggCompileHelper.gradleCompile`, after `compileContextManager.ensureInitProjectInfo()`:

```kotlin
        val effectiveOptions = withLibraryTestApkHistory(options)
```

Use `effectiveOptions` for project info fetch, script command, client selection, and `JuggGradleCompileTask`. Keep the original `options` only where the caller's requested target is intentionally needed. Add helper:

```kotlin
    private fun withLibraryTestApkHistory(options: JuggGradleCompileOptions): JuggGradleCompileOptions {
        if (options.buildTarget != BuildTarget.ANDROID_TEST) {
            return options
        }
        val projectInfo = compileContextManager.getProjectInfo()
        val variant = inferApplicationAndroidTestVariant(projectInfo.modules.values) ?: return options
        val requestedTasks = options.compileCommand.split(Regex("\\s+")).filter { it.startsWith(":") }.toSet()
        val records = LibraryTestApkBuildHistory(pathManager.projectDir, logger = logger)
            .selectRecentForAndroidTest(projectInfo.modules, "${variant}AndroidTest", requestedTasks = requestedTasks)
        if (records.isEmpty()) {
            return options
        }
        logger.info("Build recent library Test APKs: ${records.joinToString { it.gradleTask }}")
        return options.copy(
            libraryTestApkGradleTasks = records.map { it.gradleTask },
            libraryTestApkOutputPatterns = records.map { it.outputApkPattern },
        )
    }

    private fun inferApplicationAndroidTestVariant(modules: Collection<ModuleInfo>): String? {
        return modules.firstOrNull { it.isAndroidTestModule && it.moduleType == ModuleInfo.Type.Library }
            ?.buildVariant
            ?.removeSuffix("AndroidTest")
    }
```

Add imports:

```kotlin
import com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBuildHistory
import com.sickworm.intellij.jugg.project.data.ModuleInfo
```

If `ModuleInfo.Type.Library` is too broad for app androidTest selection, use the same variant value that `CompileContextManager` has already loaded for `.androidTest` modules; the history selector will still filter by module existence and exact build variant.

- [ ] **Step 5: Run focused compile option tests**

Run:

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptionsTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggGradleCompileOptions.kt \
  idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt \
  main/src/test/java/com/sickworm/intellij/jugg/ide/bean/JuggGradleCompileOptionsTest.kt
git commit -m "[feature] select library test apk history for androidtest builds"
```

---

### Task 4: Inject Library Test Tasks in Gradle Init Script

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/SshCommand.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderManager.kt`
- Modify: `main/src/main/resources/gradle/readProjectInfo.gradle.kts`
- Modify: `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/ReadProjectInfoScriptContentTest.kt`
- Test: `idea/src/test/java/com/sickworm/intellij/jugg/gradle/IsNormalGradleCommandTest.kt`

- [ ] **Step 1: Write failing script content test**

In `ReadProjectInfoScriptContentTest.generatedScript_shouldInjectAndroidTestTaskBeforeTaskGraphReady`, add:

```kotlin
        assertTrue(scriptText.contains("PARAM_LIBRARY_TEST_TASKS = \"jugg.libraryTestTasks\""))
        assertTrue(scriptText.contains("readLibraryTestTasks()"))
        assertTrue(scriptText.contains("libraryTestTasks.forEach"))
```

- [ ] **Step 2: Write failing command property test**

In `IsNormalGradleCommandTest.kt`, add:

```kotlin
    @Test
    fun `android test command passes library test task property`() {
        val command = CompileProjectCommand(
            compileCommand = "./gradlew :app:assembleDebug",
            projectPath = "/project",
            initGradleFileRelativePath = "/project/.gradle/jugg/readProjectInfo.gradle.kts",
            buildTarget = BuildTarget.ANDROID_TEST,
            libraryTestApkGradleTasks = listOf(":library1:assembleDebugAndroidTest"),
        ).baseCommand

        assertEquals(true, command.contains("-Pjugg.libraryTestTasks=:library1:assembleDebugAndroidTest"), command)
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.script.ReadProjectInfoScriptContentTest" \
  && ./gradlew :idea:test --tests "com.sickworm.intellij.jugg.gradle.IsNormalGradleCommandTest"
```

Expected: FAIL because the property and script parser do not exist.

- [ ] **Step 4: Pass Gradle property from command**

Modify `CompileProjectCommand` constructor:

```kotlin
    private val libraryTestApkGradleTasks: List<String> = emptyList(),
```

Append after `buildTarget.gradlePropertyArgument()`:

```kotlin
            suffix += libraryTestApkGradleTasks.gradlePropertyArgument()
```

Add helper:

```kotlin
    private fun List<String>.gradlePropertyArgument(): String {
        if (isEmpty()) return ""
        val value = joinToString(";")
        return " -P${GradleProjectInfoReaderManager.PARAM_LIBRARY_TEST_TASKS}=$value"
    }
```

Update `LocalGradleCompileClient` and `RemoteGradleCompileClient` call sites that create `CompileProjectCommand` to pass `juggGradleCompileOptions.libraryTestApkGradleTasks`.

- [ ] **Step 5: Inject tasks in source init script manager**

Modify `GradleProjectInfoReaderManager.injectAndroidTestTaskIfNeeded()`:

```kotlin
            val libraryTestTasks = readLibraryTestTasks()
            val testTasks = listOfNotNull(testTask) + libraryTestTasks.mapNotNull { taskPath ->
                rootProject.allprojects.firstNotNullOfOrNull { candidateProject ->
                    candidateProject.tasks.findByPath(taskPath)
                } ?: run {
                    println("Jugg: library androidTest task $taskPath not found")
                    null
                }
            }
            targetTasks.forEach { task ->
                testTasks.forEach { testTaskToInject ->
                    if (task != testTaskToInject) {
                        task.dependsOn(testTaskToInject)
                        println("Jugg: inject ${testTaskToInject.path} before ${task.path}")
                    }
                }
            }
```

Add method:

```kotlin
    private fun readLibraryTestTasks(): List<String> {
        return rootProject.properties[PARAM_LIBRARY_TEST_TASKS]
            ?.toString()
            ?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }
```

Add companion const:

```kotlin
        const val PARAM_LIBRARY_TEST_TASKS = "jugg.libraryTestTasks"
```

Use `firstNotNullOfOrNull` only if it is already supported by the Gradle script compatibility level. If not, write a simple nested loop to avoid Kotlin 1.5 compatibility issues.

- [ ] **Step 6: Mirror changes into generated resource script**

Apply the same textual changes to `main/src/main/resources/gradle/readProjectInfo.gradle.kts`. Keep compatibility rules from `ReadProjectInfoScriptContentTest`: no top-level private functions, no unsupported Kotlin APIs, no new fragile APIs.

- [ ] **Step 7: Run tests to verify they pass**

Run:

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.script.ReadProjectInfoScriptContentTest" \
  && ./gradlew :idea:test --tests "com.sickworm.intellij.jugg.gradle.IsNormalGradleCommandTest"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/SshCommand.kt \
  main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderManager.kt \
  main/src/main/resources/gradle/readProjectInfo.gradle.kts \
  main/src/test/java/com/sickworm/intellij/jugg/gradle/script/ReadProjectInfoScriptContentTest.kt \
  idea/src/test/java/com/sickworm/intellij/jugg/gradle/IsNormalGradleCommandTest.kt
git commit -m "[feature] inject recent library test apk tasks"
```

---

### Task 5: Collect Optional Library Test APK Outputs

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/LocalGradleCompileClient.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/RemoteGradleCompileClient.kt`
- Test: add focused tests where existing compile client tests live, or extend `AndroidTestCommandDeriverTest.kt` only for pure helpers.

- [ ] **Step 1: Extract optional output helper and write failing pure tests**

In `AndroidTestCommandDeriverTest.kt`, add tests for a new pure helper if extracted into `LocalGradleCompileClient` companion:

```kotlin
    @Test
    fun `optional library test apk patterns are appended after app and application test apk indexes`() {
        val patterns = LocalGradleCompileClient.extraLibraryTestApkPatterns(
            listOf("library1/build/outputs/apk/androidTest/debug/*.apk")
        )

        assertEquals(listOf("library1/build/outputs/apk/androidTest/debug/*.apk"), patterns)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.compile.AndroidTestCommandDeriverTest"
```

Expected: FAIL if the helper does not exist.

- [ ] **Step 3: Collect optional local outputs without failing build**

In `LocalGradleCompileClient.compileAndFetchResult`, after `findAndroidTestApks(...)`, add:

```kotlin
        findOptionalLibraryTestApks(findApks.size, juggGradleCompileOptions).let { libraryTestApks ->
            findApks.addAll(libraryTestApks)
        }
```

Add method:

```kotlin
    private fun findOptionalLibraryTestApks(startIndex: Int, options: JuggGradleCompileOptions): List<FoundApk> {
        if (options.buildTarget != BuildTarget.ANDROID_TEST) {
            return emptyList()
        }
        return options.libraryTestApkOutputPatterns.mapIndexedNotNull { index, pattern ->
            val apk = findApk(pattern, options, startIndex + index)
            if (apk == null) {
                logger.warn("AndroidTest mode: cannot find optional library Test APK for $pattern")
            }
            apk
        }
    }
```

Do not add missing optional patterns to `failedApkPaths`.

- [ ] **Step 4: Collect optional remote outputs without failing build**

In `RemoteGradleCompileClient.compileAndFetchResult`, after `findAndroidTestApks(...)`, add optional collection with the same semantics:

```kotlin
        findOptionalLibraryTestApks(findApks.size, channel, gradleCompileSettings).let { libraryTestApks ->
            findApks.addAll(libraryTestApks)
        }
```

Add method:

```kotlin
    private fun findOptionalLibraryTestApks(
        startIndex: Int,
        channel: Channel,
        gradleCompileSettings: JuggGradleCompileOptions,
    ): List<RemoteApk> {
        if (gradleCompileSettings.buildTarget != BuildTarget.ANDROID_TEST) {
            return emptyList()
        }
        return gradleCompileSettings.libraryTestApkOutputPatterns.mapIndexedNotNull { index, pattern ->
            val apk = findApk(startIndex + index, pattern, channel, gradleCompileSettings)
            if (apk == null) {
                logger.warn("AndroidTest mode: cannot find optional library Test APK for $pattern")
            }
            apk
        }
    }
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.compile.AndroidTestCommandDeriverTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/LocalGradleCompileClient.kt \
  main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/RemoteGradleCompileClient.kt \
  main/src/test/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriverTest.kt
git commit -m "[feature] collect optional library test apk outputs"
```

---

### Task 6: Sync Knowledge Docs and Verify

**Files:**
- Modify: `docs/ai_knowledge/06_android_test.md`
- Modify: `docs/ai_knowledge/98_code_map.md`

- [ ] **Step 1: Update androidTest knowledge doc**

In `docs/ai_knowledge/06_android_test.md`, add concise notes:

```markdown
- library Test APK backfill 成功后会写入 `~/.jugg/library_test_build_records/{projectName}_hash{0:8}.json`。
- 后续 `BuildTarget.ANDROID_TEST` Gradle Build 会读取 30 天内、当前 variant 匹配、当前工程仍存在的最近 3 条 library Test APK 记录，并在 init script 的 androidTest 注入阶段追加对应 library `assemble<Variant>AndroidTest` task。
- 历史 library Test APK 输出缺失只打印 warning，不阻断 application androidTest baseline；Gradle task 本身失败仍按正常构建失败处理。
```

- [ ] **Step 2: Update code map**

In `docs/ai_knowledge/98_code_map.md`, update AndroidTest 运行模型 row to include `LibraryTestApkBuildHistory` and mention global history replay.

- [ ] **Step 3: Run focused verification**

Run focused tests from this plan:

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBuildHistoryTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.script.ReadProjectInfoScriptContentTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.compile.AndroidTestCommandDeriverTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptionsTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.LibraryTestApkBackfillHelperTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.gradle.IsNormalGradleCommandTest"
```

Expected: each targeted test command passes. Do not run unfiltered `:main:test` or `:idea:test`.

- [ ] **Step 4: Run compile verification**

Run:

```bash
./gradlew :idea:compileKotlin
```

Expected: PASS. If test-source compile is blocked by unrelated `JuggRunningTaskTest.kt` issues during `:idea:test`, record it as a pre-existing blocker and keep `:idea:compileKotlin` as compile verification.

- [ ] **Step 5: Commit docs**

```bash
git add docs/ai_knowledge/06_android_test.md docs/ai_knowledge/98_code_map.md
git commit -m "[docs] document library test apk build history"
```

---

## Self-Review

- Spec coverage: covered global history file naming, git URL priority, non-git fallback, upsert by module and variant, no history trimming, AndroidTest-only replay, recent-3 selection, 30-day filter, same Gradle init script injection phase, same log location, optional APK collection warning-only behavior, and docs sync.
- Placeholder scan: no placeholder markers or unspecified “add tests” steps remain; every task has concrete file paths, commands, and expected results.
- Type consistency: `LibraryTestApkBuildRecord`, `LibraryTestApkBuildReplayRecord`, `GitProjectInfo`, `libraryTestApkGradleTasks`, and `libraryTestApkOutputPatterns` names are used consistently across tasks.
