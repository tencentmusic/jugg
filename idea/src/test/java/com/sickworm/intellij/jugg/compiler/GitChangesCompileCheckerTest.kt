package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.IBackgroundTaskRunner
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Git post-compile check must align with [ChangedFile.hasCompiledOnce] / stale-snapshot ignore (ac679d6).
 */
class GitChangesCompileCheckerTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun checkUndetectedFiles_doesNotTreatAlreadyCompiledAppearancesAsNew() {
        val deployFileManager = mock<DeployFileManager>()
        val gitDetector = mock<GitFileChangesDetector>()
        val userFile = createSourceFile("DressCenterPage.kt")
        val entryFile = createSourceFile("KuiklyCoreEntry.kt")
        val dressBefore = changedFile(userFile).apply { compiledTimes = 0 }
        val dressAfter = changedFile(userFile).apply { compiledTimes = 1 }
        val entryAfter = changedFile(entryFile).apply { compiledTimes = 1 }

        whenever(deployFileManager.getUndeployedFiles())
            .thenReturn(listOf(dressBefore))
            .thenReturn(listOf(dressAfter, entryAfter))

        val checker = createChecker(gitDetector, deployFileManager)
        val result = checker.checkUndetectedFiles(listOf(dressBefore))

        assertFalse(result.isFoundNewChangedFiles)
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun checkUndetectedFiles_reportsNewUncompiledFileAfterGitRefresh() {
        val deployFileManager = mock<DeployFileManager>()
        val gitDetector = mock<GitFileChangesDetector>()
        val missingFile = createSourceFile("AgentAdded.kt")
        val newUncompiled = changedFile(missingFile)

        whenever(deployFileManager.getUndeployedFiles())
            .thenReturn(emptyList())
            .thenReturn(listOf(newUncompiled))

        val checker = createChecker(gitDetector, deployFileManager)
        val result = checker.checkUndetectedFiles(emptyList())

        assertTrue(result.isFoundNewChangedFiles)
        assertEquals(listOf(newUncompiled), result.files)
    }

    @Test
    fun getAsyncResultIfCompleted_skipsRecompileWhenAsyncCheckRacedWithCompile() {
        val deployFileManager = mock<DeployFileManager>()
        val gitDetector = mock<GitFileChangesDetector>()
        val dressFile = createSourceFile("DressCenterPage.kt")
        val entryFile = createSourceFile("KuiklyCoreEntry.kt")
        val dressUncompiled = changedFile(dressFile)
        val entryUncompiled = changedFile(entryFile)
        val dressCompiled = changedFile(dressFile).apply { compiledTimes = 1 }
        val entryCompiled = changedFile(entryFile).apply { compiledTimes = 1 }

        whenever(deployFileManager.getUndeployedFiles())
            .thenReturn(listOf(dressUncompiled))
            .thenReturn(listOf(dressUncompiled, entryUncompiled))
            .thenReturn(listOf(dressCompiled, entryCompiled))

        val checker = createChecker(gitDetector, deployFileManager, ImmediateBackgroundTaskRunner())
        checker.checkUndetectedFilesAsync(listOf(dressUncompiled))

        val result = checker.getAsyncResultIfCompleted()

        assertFalse(result!!.isFoundNewChangedFiles)
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun getAsyncResultIfCompleted_stillRecompilesWhenFileRemainsUncompiled() {
        val deployFileManager = mock<DeployFileManager>()
        val gitDetector = mock<GitFileChangesDetector>()
        val entryFile = createSourceFile("KuiklyCoreEntry.kt")
        val entryUncompiled = changedFile(entryFile)

        whenever(deployFileManager.getUndeployedFiles())
            .thenReturn(emptyList())
            .thenReturn(listOf(entryUncompiled))
            .thenReturn(listOf(entryUncompiled))

        val checker = createChecker(gitDetector, deployFileManager, ImmediateBackgroundTaskRunner())
        checker.checkUndetectedFilesAsync(emptyList())

        val result = checker.getAsyncResultIfCompleted()

        assertTrue(result!!.isFoundNewChangedFiles)
        assertEquals(listOf(entryUncompiled), result.files)
    }

    @Test
    fun checkUndetectedFiles_reportsFileReopenedAfterContentChange() {
        val deployFileManager = mock<DeployFileManager>()
        val gitDetector = mock<GitFileChangesDetector>()
        val sourceFile = createSourceFile("Main.kt")
        val compiled = changedFile(sourceFile).apply { compiledTimes = 1 }
        val reopened = changedFile(sourceFile).apply { compiledTimes = 0 }

        whenever(deployFileManager.getUndeployedFiles())
            .thenReturn(listOf(compiled))
            .thenReturn(listOf(reopened))

        val checker = createChecker(gitDetector, deployFileManager)
        val result = checker.checkUndetectedFiles(listOf(compiled))

        assertTrue(result.isFoundNewChangedFiles)
        assertEquals(listOf(reopened), result.files)
    }

    @Test
    fun getAsyncResultIfCompleted_ignoresAsyncCheckStillRunning() {
        val checker = createChecker(
            gitDetector = mock(),
            deployFileManager = mock(),
            backgroundTaskRunner = PendingBackgroundTaskRunner(),
        )
        checker.checkUndetectedFilesAsync(emptyList())

        assertNull(checker.getAsyncResultIfCompleted())
    }

    @Test
    fun getAsyncResultIfCompleted_doesNotReuseLateResult() {
        val deployFileManager = mock<DeployFileManager>()
        val gitDetector = mock<GitFileChangesDetector>()
        val lateFile = changedFile(createSourceFile("Late.kt"))
        whenever(deployFileManager.getUndeployedFiles())
            .thenReturn(emptyList())
            .thenReturn(listOf(lateFile))
            .thenReturn(emptyList())
            .thenReturn(emptyList())
        val backgroundTaskRunner = ManualBackgroundTaskRunner()
        val checker = createChecker(gitDetector, deployFileManager, backgroundTaskRunner)

        checker.checkUndetectedFilesAsync(emptyList())
        assertNull(checker.getAsyncResultIfCompleted())

        checker.checkUndetectedFilesAsync(emptyList())
        backgroundTaskRunner.complete(0)
        backgroundTaskRunner.complete(1)

        val result = checker.getAsyncResultIfCompleted()
        assertFalse(result!!.isFoundNewChangedFiles)
    }

    private fun createChecker(
        gitDetector: GitFileChangesDetector,
        deployFileManager: DeployFileManager,
        backgroundTaskRunner: IBackgroundTaskRunner = mock(),
    ): GitChangesCompileChecker {
        return GitChangesCompileChecker(
            gitFileChangesDetector = gitDetector,
            deployFileManager = deployFileManager,
            backgroundTaskRunner = backgroundTaskRunner,
            logger = mock(),
        )
    }

    private class PendingBackgroundTaskRunner : IBackgroundTaskRunner {
        private val job = Job()

        override fun runBackgroundSafe(jobName: String, isNeedLog: Boolean, action: Runnable): Job = job

        override fun runBackgroundSafe(
            jobName: String,
            delayMs: Long,
            isNeedLog: Boolean,
            action: Runnable,
        ): Job = job
    }

    private class ImmediateBackgroundTaskRunner : IBackgroundTaskRunner {
        override fun runBackgroundSafe(jobName: String, isNeedLog: Boolean, action: Runnable): Job {
            action.run()
            return Job().apply { complete() }
        }

        override fun runBackgroundSafe(
            jobName: String,
            delayMs: Long,
            isNeedLog: Boolean,
            action: Runnable,
        ): Job = runBackgroundSafe(jobName, isNeedLog, action)
    }

    private class ManualBackgroundTaskRunner : IBackgroundTaskRunner {
        private val tasks = mutableListOf<PendingTask>()

        override fun runBackgroundSafe(jobName: String, isNeedLog: Boolean, action: Runnable): Job {
            return PendingTask(action, Job()).also(tasks::add).job
        }

        override fun runBackgroundSafe(
            jobName: String,
            delayMs: Long,
            isNeedLog: Boolean,
            action: Runnable,
        ): Job = runBackgroundSafe(jobName, isNeedLog, action)

        fun complete(index: Int) {
            tasks[index].action.run()
            tasks[index].job.complete()
        }

        private data class PendingTask(
            val action: Runnable,
            val job: CompletableJob,
        )
    }

    private fun createSourceFile(name: String): File {
        return temporaryFolder.newFile(name).apply { writeText("content") }
    }

    private fun changedFile(file: File): ChangedFile {
        return ChangedFile(
            type = CompileFile.Type.Kotlin,
            file = file.absoluteFile,
            baseDir = temporaryFolder.root.absoluteFile,
            module = ModuleInfo.virtualModule,
        )
    }
}
