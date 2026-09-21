package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class GitFileChangesDetectorTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun refreshChangedFilesIfHeadChanged_skipsRefreshUntilHeadChanges() {
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
            detector.refreshChangedFilesIfHeadChanged(),
        )
        verify(deployHistoryManager, never()).getChangedFilesSinceLastFullCompiled()

        changedFile.writeText("class Changed")
        GitManager(rootDir).addAllAndCommit("change source")
        whenever(deployHistoryManager.getChangedFilesSinceLastFullCompiled()).thenReturn(listOf(changedFile))

        assertEquals(
            GitFileChangesDetector.GitRefreshResult.REFRESHED,
            detector.refreshChangedFilesIfHeadChanged(),
        )
        assertEquals(listOf(changedFile), notifiedFiles)
        assertEquals(
            GitFileChangesDetector.GitRefreshResult.NO_HEAD_CHANGE,
            detector.refreshChangedFilesIfHeadChanged(),
        )
        verify(deployHistoryManager, times(1)).getChangedFilesSinceLastFullCompiled()
    }

    @Test
    fun refreshChangedFilesIfHeadChanged_retriesAfterRefreshFailure() {
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
            detector.refreshChangedFilesIfHeadChanged(),
        )
        assertEquals(
            GitFileChangesDetector.GitRefreshResult.REFRESHED,
            detector.refreshChangedFilesIfHeadChanged(),
        )
        verify(deployHistoryManager, times(2)).getChangedFilesSinceLastFullCompiled()
    }

    @Test
    fun refreshChangedFilesIfHeadChanged_olderRefreshCannotRollbackNewerHead() {
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
            detector.refreshChangedFilesIfHeadChanged()
        }
        assertTrue(firstRefreshStarted.await(5, TimeUnit.SECONDS))

        File(rootDir, "Second.kt").writeText("class Second")
        GitManager(rootDir).addAllAndCommit("second change")
        assertEquals(
            GitFileChangesDetector.GitRefreshResult.REFRESHED,
            detector.refreshChangedFilesIfHeadChanged(),
        )
        releaseFirstRefresh.countDown()
        firstRefresh.join(5_000)
        assertTrue(!firstRefresh.isAlive)

        assertEquals(
            GitFileChangesDetector.GitRefreshResult.NO_HEAD_CHANGE,
            detector.refreshChangedFilesIfHeadChanged(),
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
        gitManager.addAllAndCommit("initial")
        return rootDir
    }

    private fun createDetector(
        rootDir: File,
        deployHistoryManager: IDeployHistoryManager,
    ): GitFileChangesDetector {
        return GitFileChangesDetector(
            deployHistoryManager,
            mock(),
            mock(),
            TestGlobal.getLogger(),
        ).also {
            it.init(rootDir, emptyMap())
        }
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
        }
    }
}
