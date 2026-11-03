package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Verifies deploy history CRC reuse for large dirty worktrees.
 */
class DeployHistoryDbCrcCacheTest {

    private lateinit var repositoryDir: File
    private lateinit var projectDir: File
    private lateinit var dbDir: File
    private lateinit var gitManager: GitManager
    private lateinit var logger: StdLogger

    @Before
    fun setUp() {
        repositoryDir = Files.createTempDirectory("jugg-deploy-history-cache").toFile()
        projectDir = File(repositoryDir, "android-project").apply { mkdirs() }
        dbDir = File(projectDir, "build/jugg/deploy-history")
        logger = StdLogger("DeployHistoryDbCrcCacheTest").apply {
            isEnableDebug = false
            isEnableInfo = false
        }

        File(projectDir, "settings.gradle").writeText("")
        File(repositoryDir, ".gitignore").writeText("build/\n")
        gitManager = GitManager(repositoryDir)
        gitManager.init()
        gitManager.addAllAndCommit("initial commit")
    }

    @After
    fun tearDown() {
        repositoryDir.deleteRecursively()
    }

    @Test
    fun shouldDeduplicateNormalizedHistoryAndGitPaths() {
        val externalFile = File(repositoryDir, "sibling/node_modules/package/index.js").apply {
            parentFile.mkdirs()
            writeText("baseline")
        }
        val historyDb = createHistoryDb()
        historyDb.resetHistoryAfterFullCompiled(emptyMap(), Long.MAX_VALUE)

        externalFile.writeText("changed content")
        Files.setLastModifiedTime(externalFile.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 2_000))

        val changedFiles = historyDb.getChangedFilesSinceLastFullCompiled(isOnInit = false)

        assertEquals(1, changedFiles?.size)
        assertEquals(externalFile.absoluteFile.normalize(), changedFiles?.single()?.absoluteFile?.normalize())
    }

    @Test
    fun shouldCompareEveryNestedRepositoryAgainstItsOwnBaseline() {
        val roots = listOf("first", "middle", "last").map { File(projectDir, it).apply { mkdirs() } }
        File(repositoryDir, ".gitignore").appendText(roots.joinToString("") { "android-project/${it.name}/\n" })
        gitManager.addAllAndCommit("ignore nested repositories")
        val sources = roots.map { File(it, "Source.kt").apply { writeText("baseline") } }
        val commits = roots.associate { root ->
            val git = GitManager(root)
            git.init()
            git.addAllAndCommit("baseline")
            root.absolutePath to git.getLastCommitHash()
        }
        DeployHistoryData(gitManager.getLastCommitHash(), commits, 0, emptyMap())
            .save(File(dbDir, "deploy_history.json"))
        sources.forEach { it.writeText("changed") }
        GitManager(roots[1]).addAllAndCommit("committed change")
        val added = File(roots.last(), "Added.kt").apply { writeText("new source") }
        val history = createHistoryDb()

        assertTrue(history.filterUnchangedFiles(sources + added).isEmpty())
        sources.forEach { it.writeText("baseline") }
        GitManager(roots[1]).addAllAndCommit("restore baseline")
        assertEquals(sources.toSet(), history.filterUnchangedFiles(sources + added).toSet())
        DeployHistoryData(gitManager.getLastCommitHash(), linkedMapOf<String, String?>(
            File(projectDir, "missing-baseline").absolutePath to null,
        ) + commits, 0, emptyMap()).save(File(dbDir, "deploy_history.json"))
        sources.forEach { it.writeText("changed again") }
        assertTrue(history.filterUnchangedFiles(sources + added).isEmpty())
    }

    @Test
    fun compareColdAndWarmCrcScanPerformance() {
        Assume.assumeTrue(System.getenv("JUGG_RUN_CRC_CACHE_PERFORMANCE_TEST").toBoolean())
        val content = ByteArray(PERFORMANCE_FILE_SIZE) { (it % 251).toByte() }
        repeat(PERFORMANCE_DIRECTORY_COUNT) { directoryIndex ->
            val directory = File(repositoryDir, "sibling/node_modules/package-$directoryIndex").apply { mkdirs() }
            repeat(PERFORMANCE_FILES_PER_DIRECTORY) { fileIndex ->
                File(directory, "file-$fileIndex.bin").writeBytes(content)
            }
        }

        createHistoryDb().resetHistoryAfterFullCompiled(emptyMap(), Long.MAX_VALUE)
        val coldHistoryDb = createHistoryDb()
        val firstScanStart = System.currentTimeMillis()
        val firstResult = coldHistoryDb.getChangedFilesSinceLastFullCompiled(isOnInit = false)
        val firstScanMs = System.currentTimeMillis() - firstScanStart
        val secondScanStart = System.currentTimeMillis()
        val secondResult = coldHistoryDb.getChangedFilesSinceLastFullCompiled(isOnInit = false)
        val secondScanMs = System.currentTimeMillis() - secondScanStart

        println("[PERF] Deploy history CRC scan: files=$PERFORMANCE_FILE_COUNT, " +
                "fileSize=$PERFORMANCE_FILE_SIZE, first=${firstScanMs}ms, second=${secondScanMs}ms")
        assertTrue(firstResult?.isEmpty() == true)
        assertEquals(firstResult, secondResult)
    }

    private fun createHistoryDb(): DeployHistoryDb {
        return DeployHistoryDb(projectDir, dbDir, NoopFileChangesHandler, logger)
    }

    private object NoopFileChangesHandler : IFileChangesHandler {
        override fun init(compileContext: ICompileContext) = Unit

        override fun filter(file: List<File>): List<ChangedFile> = emptyList()

        override fun updateBuildFileRules(rules: List<String>, doNotIgnoreModulePaths: List<String>) = Unit
    }

    private companion object {
        const val PERFORMANCE_DIRECTORY_COUNT = 100
        const val PERFORMANCE_FILES_PER_DIRECTORY = 100
        const val PERFORMANCE_FILE_COUNT = PERFORMANCE_DIRECTORY_COUNT * PERFORMANCE_FILES_PER_DIRECTORY
        const val PERFORMANCE_FILE_SIZE = 10_000
    }
}
