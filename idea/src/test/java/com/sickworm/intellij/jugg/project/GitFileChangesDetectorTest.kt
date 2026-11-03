package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployHistoryDb
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.BeforeClass
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class GitFileChangesDetectorTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private val fileManagers = mutableListOf<DeployFileManager>()
    private var previousConstRefEnabled = false

    @Before
    fun disableConstRefTasks() {
        previousConstRefEnabled = JuggSettings.isEnableConstRefTasks
        JuggSettings.isEnableConstRefTasks = false
    }

    @After
    fun disposeFileManagers() {
        fileManagers.forEach { it.dispose() }
        JuggSettings.isEnableConstRefTasks = previousConstRefEnabled
    }

    @Test
    fun manualRefreshRemovesRestoredBuildFileWithoutHeadChangeAndKeepsManualInput() {
        val root = createGitRepository()
        val build = File(root, "build.gradle").apply { writeText("// baseline") }
        GitManager(root).addAllAndCommit("add build file")
        val db = createHistoryDb(root)
        val history = mock<IDeployHistoryManager>()
        whenever(history.getChangedFilesSinceLastFullCompiled()).thenAnswer {
            db.getChangedFilesSinceLastFullCompiled(isOnInit = false)
        }
        whenever(history.filterUnchangedFiles(any())).thenAnswer { db.filterUnchangedFiles(it.getArgument(0)) }
        val files = createFileManager(root)
        val detector = createDetector(root, history, files)
        build.appendText("\n// edit")
        files.addChangedFile(listOf(changedFile(build).copy(type = CompileFile.Type.BuildFile)))
        val manual = File(root, "Base.kt")
        files.addChangedFile(listOf(changedFile(manual).copy(isManuallySelected = true)))
        build.writeText("// baseline")

        assertTrue(detector.refreshFileChanges())

        assertEquals(listOf(manual), files.getUncompiledFiles().map { it.file })
        assertTrue(files.getFileChangeCandidates().any { it.file == build })
        assertEquals(GitFileChangesDetector.GitRefreshResult.NO_HEAD_CHANGE, detector.refreshFileChangesIfHeadChanged())
    }

    @Test
    fun rollbackKeepsManualFilesAndRemovesOrdinaryRestoredFiles() {
        val rootDir = createGitRepository()
        val history = mock<IDeployHistoryManager>()
        val files = createFileManager(rootDir)
        val detector = createDetector(rootDir, history, files)
        val manual = File(rootDir, "Base.kt")
        val ordinary = File(rootDir, "Other.kt").apply { writeText("class Other") }
        files.addChangedFile(listOf(changedFile(manual).copy(isManuallySelected = true), changedFile(ordinary)))
        whenever(history.filterUnchangedFiles(any())).thenAnswer { it.getArgument<List<File>>(0) }
        assertTrue(detector.rollbackUnchangedFiles(files.getUncompiledFiles()))
        assertEquals(listOf(manual), files.getUncompiledFiles().map { it.file })
        verify(history).filterUnchangedFiles(listOf(ordinary))
    }

    @Test
    fun headChangeRemovesRevertedFileAndPublishesMissedChange() {
        val rootDir = createGitRepository()
        val historyDb = createHistoryDb(rootDir)
        val historyManager = mock<IDeployHistoryManager>()
        whenever(historyManager.getChangedFilesSinceLastFullCompiled()).thenAnswer {
            historyDb.getChangedFilesSinceLastFullCompiled(isOnInit = false)
        }
        whenever(historyManager.filterUnchangedFiles(any())).thenAnswer {
            historyDb.filterUnchangedFiles(it.getArgument(0))
        }
        val fileManager = createFileManager(rootDir)
        val detector = createDetector(rootDir, historyManager, fileManager)
        val source = File(rootDir, "Base.kt")
        source.writeText("class Modified")
        fileManager.addChangedFile(listOf(changedFile(source)))
        source.writeText("class Base")
        val missedSource = File(rootDir, "Missed.kt").apply { writeText("class Missed") }
        GitManager(rootDir).addAllAndCommit("switch to restored source with missed change")
        detector.startListen(object : FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                fileManager.removeChangedFile(deletedFiles)
                fileManager.addChangedFile(changedFiles.map { changedFile(it) })
            }
        })

        assertEquals(GitFileChangesDetector.GitRefreshResult.REFRESHED, detector.refreshFileChangesIfHeadChanged())

        assertEquals(listOf(missedSource), fileManager.getUncompiledFiles().map { it.file })
        assertEquals("class Base", source.readText())
        assertEquals(GitFileChangesDetector.GitRefreshResult.NO_HEAD_CHANGE, detector.refreshFileChangesIfHeadChanged())
    }

    @Test
    fun rollbackFailureKeepsPendingFilesAndHeadBaselineForNextRefresh() {
        val rootDir = createGitRepository()
        val historyManager = mock<IDeployHistoryManager>()
        whenever(historyManager.getChangedFilesSinceLastFullCompiled()).thenReturn(emptyList())
        val source = File(rootDir, "Base.kt")
        whenever(historyManager.filterUnchangedFiles(listOf(source)))
            .thenThrow(IllegalStateException("Git comparison unavailable"))
            .thenReturn(listOf(source))
        val fileManager = createFileManager(rootDir)
        val detector = createDetector(rootDir, historyManager, fileManager)
        fileManager.addChangedFile(listOf(changedFile(source)))
        File(rootDir, "README.md").writeText("move HEAD")
        GitManager(rootDir).addAllAndCommit("change documentation")

        assertEquals(GitFileChangesDetector.GitRefreshResult.FAILED, detector.refreshFileChangesIfHeadChanged())
        assertEquals(listOf(source), fileManager.getUncompiledFiles().map { it.file })
        assertEquals(GitFileChangesDetector.GitRefreshResult.REFRESHED, detector.refreshFileChangesIfHeadChanged())
        assertTrue(fileManager.getUncompiledFiles().isEmpty())
        assertEquals(GitFileChangesDetector.GitRefreshResult.NO_HEAD_CHANGE, detector.refreshFileChangesIfHeadChanged())
    }

    @Test
    fun headChangeKeepsRestoredSourceWhenDeployedContentDiffers() {
        val rootDir = createGitRepository()
        val historyDb = createHistoryDb(rootDir)
        val source = File(rootDir, "Base.kt")
        source.writeText("class Deployed")
        historyDb.beforeIncrementalCompile(listOf(changedFile(source)))
        historyDb.updateHistoryAfterIncrementalCompile()
        val historyManager = mock<IDeployHistoryManager>()
        whenever(historyManager.getChangedFilesSinceLastFullCompiled()).thenAnswer {
            historyDb.getChangedFilesSinceLastFullCompiled(isOnInit = false)
        }
        whenever(historyManager.filterUnchangedFiles(any())).thenAnswer {
            historyDb.filterUnchangedFiles(it.getArgument(0))
        }
        val fileManager = createFileManager(rootDir)
        val detector = createDetector(rootDir, historyManager, fileManager)
        source.writeText("class Base")
        fileManager.addChangedFile(listOf(changedFile(source)))
        File(rootDir, "README.md").writeText("move HEAD")
        GitManager(rootDir).addAllAndCommit("restore deployed source")

        assertEquals(GitFileChangesDetector.GitRefreshResult.REFRESHED, detector.refreshFileChangesIfHeadChanged())

        assertEquals(listOf(source), fileManager.getUncompiledFiles().map { it.file })
    }

    @Test
    fun unchangedHeadKeepsPendingFilesWithoutRunningRollbackCheck() {
        val rootDir = createGitRepository()
        val historyManager = mock<IDeployHistoryManager>()
        val fileManager = createFileManager(rootDir)
        val detector = createDetector(rootDir, historyManager, fileManager)
        val source = File(rootDir, "Base.kt")
        fileManager.addChangedFile(listOf(changedFile(source)))

        assertEquals(GitFileChangesDetector.GitRefreshResult.NO_HEAD_CHANGE, detector.refreshFileChangesIfHeadChanged())

        assertEquals(listOf(source), fileManager.getUncompiledFiles().map { it.file })
        verify(historyManager, never()).filterUnchangedFiles(any())
    }

    @Test
    fun debouncedHeadRefreshRemovesRestoredFilesBeforeRun() {
        val rootDir = createGitRepository()
        val sources = listOf(File(rootDir, "Base.kt"), File(rootDir, "Second.kt").apply { writeText("class Second") })
        GitManager(rootDir).addAllAndCommit("add second source")
        val historyManager = mock<IDeployHistoryManager>()
        whenever(historyManager.getChangedFilesSinceLastFullCompiled()).thenReturn(emptyList())
        whenever(historyManager.filterUnchangedFiles(sources)).thenReturn(sources)
        val fileManager = createFileManager(rootDir)
        val taskRunner = mock<TaskRunnerManager>()
        whenever(taskRunner.runBackgroundSafe(any(), any<Long>(), any(), any())).thenReturn(Job())
        doAnswer {
            it.getArgument<Runnable>(1).run()
            null
        }.whenever(taskRunner).runTaskSafe(any(), any(), any(), any())
        val detector = GitFileChangesDetector(historyManager, fileManager, taskRunner, TestGlobal.getLogger())
        detector.init(rootDir, emptyMap())
        fileManager.addChangedFile(sources.map { changedFile(it) })
        File(rootDir, "README.md").writeText("move HEAD")
        GitManager(rootDir).addAllAndCommit("change documentation")

        detector.onSourceFileChanged(sources.map { changedFile(it) })
        val delayedRefresh = argumentCaptor<Runnable>()
        verify(taskRunner).runBackgroundSafe(eq("checkGitFileChanges"), eq(1_000L), eq(true), delayedRefresh.capture())
        assertEquals(sources, fileManager.getUncompiledFiles().map { it.file })
        delayedRefresh.firstValue.run()

        assertTrue(fileManager.getUncompiledFiles().isEmpty())
        assertEquals(GitFileChangesDetector.GitRefreshResult.NO_HEAD_CHANGE, detector.refreshFileChangesIfHeadChanged())
    }

    @Test
    fun refreshFileChangesIfHeadChanged_skipsRefreshUntilHeadChanges() {
        val rootDir = createGitRepository()
        val deployHistoryManager = mock<IDeployHistoryManager>()
        val detector = createDetector(rootDir, deployHistoryManager)
        val changedFile = File(rootDir, "Changed.kt")
        var notifiedFiles = emptyList<File>()
        detector.startListen(object : FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                notifiedFiles = changedFiles
            }
        })

        assertEquals(
            GitFileChangesDetector.GitRefreshResult.NO_HEAD_CHANGE,
            detector.refreshFileChangesIfHeadChanged(),
        )
        verify(deployHistoryManager, never()).getChangedFilesSinceLastFullCompiled()

        changedFile.writeText("class Changed")
        GitManager(rootDir).addAllAndCommit("change source")
        whenever(deployHistoryManager.getChangedFilesSinceLastFullCompiled()).thenReturn(listOf(changedFile))

        assertEquals(
            GitFileChangesDetector.GitRefreshResult.REFRESHED,
            detector.refreshFileChangesIfHeadChanged(),
        )
        assertEquals(listOf(changedFile), notifiedFiles)
        assertEquals(
            GitFileChangesDetector.GitRefreshResult.NO_HEAD_CHANGE,
            detector.refreshFileChangesIfHeadChanged(),
        )
        verify(deployHistoryManager, times(1)).getChangedFilesSinceLastFullCompiled()
    }

    @Test
    fun refreshFileChangesIfHeadChanged_retriesAfterRefreshFailure() {
        val rootDir = createGitRepository()
        val deployHistoryManager = mock<IDeployHistoryManager>()
        val detector = createDetector(rootDir, deployHistoryManager)
        val changedFile = File(rootDir, "Retry.kt").apply { writeText("class Retry") }
        GitManager(rootDir).addAllAndCommit("change source")
        whenever(deployHistoryManager.getChangedFilesSinceLastFullCompiled())
            .thenReturn(null)
            .thenReturn(listOf(changedFile))

        assertEquals(
            GitFileChangesDetector.GitRefreshResult.FAILED,
            detector.refreshFileChangesIfHeadChanged(),
        )
        assertEquals(
            GitFileChangesDetector.GitRefreshResult.REFRESHED,
            detector.refreshFileChangesIfHeadChanged(),
        )
        verify(deployHistoryManager, times(2)).getChangedFilesSinceLastFullCompiled()
    }

    @Test
    fun refreshFileChangesIfHeadChanged_olderRefreshCannotRollbackNewerHead() {
        val rootDir = createGitRepository()
        val deployHistoryManager = mock<IDeployHistoryManager>()
        val detector = createDetector(rootDir, deployHistoryManager)
        val firstRefreshStarted = CountDownLatch(1)
        val releaseFirstRefresh = CountDownLatch(1)
        val refreshCount = AtomicInteger()
        whenever(deployHistoryManager.getChangedFilesSinceLastFullCompiled()).thenAnswer {
            if (refreshCount.incrementAndGet() == 1) {
                firstRefreshStarted.countDown()
                assertTrue(releaseFirstRefresh.await(5, TimeUnit.SECONDS))
            }
            emptyList<File>()
        }

        File(rootDir, "First.kt").writeText("class First")
        GitManager(rootDir).addAllAndCommit("first change")
        val firstRefresh = thread {
            detector.refreshFileChangesIfHeadChanged()
        }
        assertTrue(firstRefreshStarted.await(5, TimeUnit.SECONDS))

        File(rootDir, "Second.kt").writeText("class Second")
        GitManager(rootDir).addAllAndCommit("second change")
        assertEquals(
            GitFileChangesDetector.GitRefreshResult.REFRESHED,
            detector.refreshFileChangesIfHeadChanged(),
        )
        releaseFirstRefresh.countDown()
        firstRefresh.join(5_000)
        assertTrue(!firstRefresh.isAlive)

        assertEquals(
            GitFileChangesDetector.GitRefreshResult.NO_HEAD_CHANGE,
            detector.refreshFileChangesIfHeadChanged(),
        )
        verify(deployHistoryManager, times(2)).getChangedFilesSinceLastFullCompiled()
    }

    @Test
    fun updateChangedFiles_notifiesDeletedMissingUndeployedFiles() {
        val deployHistoryManager = mock<IDeployHistoryManager>()
        val deployFileManager = mock<DeployFileManager>()
        val taskRunnerManager = mock<TaskRunnerManager>()
        val detector = GitFileChangesDetector(
            deployHistoryManager,
            deployFileManager,
            taskRunnerManager,
            TestGlobal.getLogger(),
        )

        val missingFile = File(temporaryFolder.root, "PayAlertDismissManager.kt")
        val newFile = temporaryFolder.newFile("GuideToExploreManager.kt")
        whenever(deployHistoryManager.getChangedFilesSinceLastFullCompiled()).thenReturn(listOf(newFile))
        whenever(deployFileManager.getUndeployedFiles()).thenReturn(
            listOf(
                ChangedFile(
                    CompileFile.Type.Kotlin,
                    missingFile,
                    temporaryFolder.root,
                    ModuleInfo.virtualModule,
                ),
            ),
        )

        var notifiedChanged: List<File> = emptyList()
        var notifiedDeleted: List<File> = emptyList()
        detector.startListen(object : FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                notifiedChanged = changedFiles
                notifiedDeleted = deletedFiles
            }
        })

        detector.updateChangedFiles()

        assertEquals(listOf(newFile), notifiedChanged)
        assertEquals(listOf(missingFile), notifiedDeleted)
        assertTrue(!missingFile.exists())
    }

    private fun createGitRepository(): File {
        val rootDir = temporaryFolder.newFolder()
        val gitManager = GitManager(rootDir)
        gitManager.init()
        File(rootDir, ".git/config").appendText(
            "\n[user]\n\tname = Jugg Test\n\temail = jugg@example.com\n"
        )
        File(rootDir, "Base.kt").writeText("class Base")
        File(rootDir, ".gitignore").writeText("build/\n")
        gitManager.addAllAndCommit("initial")
        return rootDir
    }

    private fun createDetector(
        rootDir: File,
        deployHistoryManager: IDeployHistoryManager,
        deployFileManager: DeployFileManager = mock(),
    ): GitFileChangesDetector {
        return GitFileChangesDetector(
            deployHistoryManager,
            deployFileManager,
            mock(),
            TestGlobal.getLogger(),
        ).also {
            it.init(rootDir, emptyMap())
        }
    }

    private fun createHistoryDb(rootDir: File): DeployHistoryDb {
        return DeployHistoryDb(rootDir, File(rootDir, "build/history"), mock(), TestGlobal.getLogger()).also {
            it.resetHistoryAfterFullCompiled(emptyMap(), System.currentTimeMillis())
        }
    }

    private fun createFileManager(rootDir: File): DeployFileManager {
        return DeployFileManager(
            JuggPathManager(rootDir, globalJuggRootDir = File(rootDir, "build/global")),
            mock(),
            TestGlobal.getLogger(),
        ).also { fileManagers.add(it) }
    }

    private fun changedFile(file: File): ChangedFile {
        return ChangedFile(CompileFile.Type.Kotlin, file, file.parentFile, ModuleInfo.virtualModule)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
        }
    }
}
