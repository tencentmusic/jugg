package com.sickworm.intellij.jugg.deploy.instrument

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            gitInfoProvider = {
                GitProjectInfo(
                    projectName = "demo/project",
                    projectKey = "  git@example.com:team/demo.git  ",
                )
            },
        )

        val file = history.recordFile()

        assertEquals(recordDir, file.parentFile)
        assertTrue(file.name.startsWith("demo_project_hash"))
        assertTrue(file.name.endsWith(".json"))
        assertEquals(8, file.name.removeSuffix(".json").substringAfter("_hash").length)
    }

    @Test
    fun `upsert keeps one record per module and variant`() {
        val history = createHistory()

        history.record(record("library1.androidTest", "debugAndroidTest", 1000L, ":library1:assembleDebugAndroidTest"))
        history.record(record("library1.androidTest", "debugAndroidTest", 2000L, ":library1:assembleDebugAndroidTest"))
        history.record(record("library1.androidTest", "releaseAndroidTest", 1500L, ":library1:assembleReleaseAndroidTest"))

        val records = history.load().records

        assertEquals(2, records.size)
        assertEquals(":library1:assembleDebugAndroidTest", records.first { it.buildVariant == "debugAndroidTest" }.gradleTask)
        assertEquals(2000L, records.first { it.buildVariant == "debugAndroidTest" }.compiledAt)
    }

    @Test
    fun `concurrent history instances preserve all records`() {
        val recordDir = temp.newFolder("shared-records")
        val projectDir = temp.newFolder("shared-project")
        val gitInfoProvider = { GitProjectInfo(projectName = "demo", projectKey = "shared-project") }
        val first = LibraryTestApkBuildHistory(projectDir, recordDir, gitInfoProvider = gitInfoProvider)
        val second = LibraryTestApkBuildHistory(projectDir, recordDir, gitInfoProvider = gitInfoProvider)
        val start = CountDownLatch(1)
        val firstThread = Thread {
            start.await(5, TimeUnit.SECONDS)
            repeat(25) { index ->
                first.record(record("first$index.androidTest", "debugAndroidTest", index.toLong(), ":first$index:assembleDebugAndroidTest"))
            }
        }
        val secondThread = Thread {
            start.await(5, TimeUnit.SECONDS)
            repeat(25) { index ->
                second.record(record("second$index.androidTest", "debugAndroidTest", index.toLong(), ":second$index:assembleDebugAndroidTest"))
            }
        }

        firstThread.start()
        secondThread.start()
        start.countDown()
        firstThread.join(10_000)
        secondThread.join(10_000)

        assertFalse(firstThread.isAlive)
        assertFalse(secondThread.isAlive)
        assertEquals(50, first.load().records.size)
    }

    @Test
    fun `select recent records filters by target variant module existence requested tasks and max three`() {
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
            record("duplicate.androidTest", "debugAndroidTest", now - 5, ":library3:assembleDebugAndroidTest"),
            record("noTask.androidTest", "debugAndroidTest", now - 1, "assembleDebug"),
        ).forEach(history::record)
        val modules = listOf(
            "library1.androidTest",
            "library2.androidTest",
            "library3.androidTest",
            "library4.androidTest",
            "duplicate.androidTest",
            "noTask.androidTest",
        ).associateWith { ModuleInfo.virtualModule.copy(name = it) }

        val selected = history.selectRecentForAndroidTest(
            modules = modules,
            buildVariant = "debugAndroidTest",
            nowMillis = now,
            requestedTasks = setOf(":library2:assembleDebugAndroidTest"),
        )

        assertEquals(
            listOf(":library1:assembleDebugAndroidTest", ":library3:assembleDebugAndroidTest", ":library4:assembleDebugAndroidTest"),
            selected.map { it.gradleTask },
        )
    }

    @Test
    fun `resolve git project info prefers origin remote over first remote`() {
        val projectDir = temp.newFolder("project")
        val gitManager = FakeGitManager(
            rootDir = projectDir,
            hasInitGit = true,
            name = "demo",
            originRemoteUrl = "git@example.com:team/origin.git",
            remoteUrls = listOf("git@example.com:team/first.git", "git@example.com:team/origin.git"),
        )

        val info = LibraryTestApkBuildHistory.resolveGitProjectInfo(projectDir, gitManager)

        assertEquals("demo", info.projectName)
        assertEquals("git@example.com:team/origin.git", info.projectKey)
    }

    @Test
    fun `load normalizes missing and invalid json fields safely`() {
        val history = createHistory()
        history.recordFile().apply {
            parentFile.mkdirs()
            writeText(
                """
                {
                  "records": [
                    null,
                    {
                      "moduleName": "library1.androidTest",
                      "buildVariant": "debugAndroidTest",
                      "gradleTask": ":library1:assembleDebugAndroidTest",
                      "compiledAt": 1000,
                      "outputApkPattern": "library1/build/outputs/apk/androidTest/debug/*.apk"
                    },
                    {
                      "moduleName": "",
                      "buildVariant": "debugAndroidTest",
                      "gradleTask": ":blank:assembleDebugAndroidTest",
                      "compiledAt": 2000,
                      "outputApkPattern": "blank/build/outputs/apk/androidTest/debug/*.apk"
                    },
                    {
                      "moduleName": "missingTask.androidTest",
                      "buildVariant": "debugAndroidTest",
                      "compiledAt": 3000,
                      "outputApkPattern": "missing/build/outputs/apk/androidTest/debug/*.apk"
                    },
                    {
                      "moduleName": "defaultTime.androidTest",
                      "buildVariant": "debugAndroidTest",
                      "gradleTask": ":defaultTime:assembleDebugAndroidTest",
                      "outputApkPattern": "default/build/outputs/apk/androidTest/debug/*.apk"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val data = history.load()

        assertEquals(temp.root.absolutePath, data.projectKey)
        assertEquals(0L, data.updatedAt)
        assertEquals(listOf("library1.androidTest", "defaultTime.androidTest"), data.records.map { it.moduleName })
        assertEquals(0L, data.records.first { it.moduleName == "defaultTime.androidTest" }.compiledAt)
    }

    @Test
    fun `select recent records reads stored gradle task directly`() {
        val history = createHistory()
        val now = 40L * 24 * 60 * 60 * 1000
        history.record(record("library1.androidTest", "debugAndroidTest", now - 1, ":library1:assembleDebugAndroidTest"))
        history.record(
            record(
                "library2.androidTest",
                "debugAndroidTest",
                now - 2,
                ":library2:compileDebugAndroidTestKotlin :library2:assembleDebugAndroidTest",
            ),
        )
        history.record(record("library3.androidTest", "debugAndroidTest", now - 3, ":library3:testDebugAndroidTestUnitTest"))
        history.record(record("library4.androidTest", "debugAndroidTest", now - 4, ":library4:compileDebugAndroidTestKotlin"))
        val modules = listOf(
            "library1.androidTest",
            "library2.androidTest",
            "library3.androidTest",
            "library4.androidTest",
        ).associateWith { ModuleInfo.virtualModule.copy(name = it) }

        val selected = history.selectRecentForAndroidTest(
            modules = modules,
            buildVariant = "debugAndroidTest",
            nowMillis = now,
        )

        assertEquals(listOf(":library1:assembleDebugAndroidTest"), selected.map { it.gradleTask })
    }

    private fun createHistory(): LibraryTestApkBuildHistory {
        return LibraryTestApkBuildHistory(
            projectDir = temp.newFolder("project"),
            recordDir = temp.newFolder("records"),
            logger = Logger.getInstance(LibraryTestApkBuildHistoryTest::class.java),
            gitInfoProvider = { GitProjectInfo(projectName = "demo", projectKey = temp.root.absolutePath) },
        )
    }

    private fun record(
        moduleName: String,
        variant: String,
        time: Long,
        task: String,
    ): LibraryTestApkBuildRecord {
        return LibraryTestApkBuildRecord(
            moduleName = moduleName,
            buildVariant = variant,
            gradleTask = task,
            compiledAt = time,
            outputApkPattern = moduleName.substringBefore(".androidTest") + "/build/outputs/apk/androidTest/debug/*.apk",
        )
    }

    private class FakeGitManager(
        override val rootDir: File,
        override val hasInitGit: Boolean,
        override val name: String?,
        override val originRemoteUrl: String?,
        override val remoteUrls: List<String>,
    ) : IGitManager {
        override val userName: String? = null
        override fun getUncommittedFiles(): List<File> = emptyList()
        override fun getChangedFiles(oldCommit: String, newCommit: String): List<File> = emptyList()
        override fun getLastCommitHash(): String? = null
        override fun filterChangedFiles(commitHash: String, files: List<File>): List<File> = emptyList()
        override fun getLastCommitFileContent(commitId: String, file: File, outputFile: File): Boolean = false
    }
}
