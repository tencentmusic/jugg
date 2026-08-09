package com.sickworm.intellij.jugg.project.change

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.runtime.ExecutionLockOwner
import com.sickworm.intellij.jugg.project.runtime.IExecutionLockManager
import com.sickworm.intellij.jugg.project.runtime.IHostTaskExecutor
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.project.runtime.createImmediateTestTaskRunnerManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class FileChangeManagerTest {

    private val fileChangesHandler = mock<IFileChangesHandler>()
    private val deployFileManager = mock<DeployFileManager>()
    private val dependencyChangeManager = mock<IDependencyChangeManager>()
    private val gitFileChangesDetector = mock<GitFileChangesDetector>()
    private val deployStateManager = mock<IDeployStateManager>()
    private val taskRunnerManager = createImmediateTestTaskRunnerManager()
    private val manager = FileChangeManager(
        fileChangesHandler,
        deployFileManager,
        dependencyChangeManager,
        gitFileChangesDetector,
        deployStateManager,
        taskRunnerManager,
        mock(),
    )

    @Test
    fun `process monitor changes should update deploy dependency and git state`() {
        val sourceFile = File("/project/app/src/main/Foo.kt")
        val deletedFile = File("/project/app/src/main/Deleted.kt")
        val buildFile = File("/project/app/build.gradle")
        val sourceChangedFile = changedFile(CompileFile.Type.Kotlin, sourceFile)
        val buildChangedFile = changedFile(CompileFile.Type.BuildFile, buildFile)
        whenever(fileChangesHandler.filter(listOf(sourceFile, buildFile))).thenReturn(listOf(sourceChangedFile, buildChangedFile))
        whenever(deployFileManager.getUndeployedFiles()).thenReturn(listOf(sourceChangedFile, buildChangedFile))

        val result = manager.processFileChanges(
            listOf(sourceFile, buildFile),
            listOf(deletedFile),
            FileChangeSource.MONITOR,
        )

        assertEquals(listOf(sourceChangedFile, buildChangedFile), result.changedFiles)
        verify(deployFileManager).removeChangedFile(listOf(deletedFile))
        verify(deployFileManager).addChangedFile(listOf(sourceChangedFile, buildChangedFile))
        verify(dependencyChangeManager).onUpdateChangedBuildFiles(listOf(buildFile))
        verify(gitFileChangesDetector).onSourceFileChanged(listOf(sourceChangedFile, buildChangedFile))
    }

    @Test
    fun `monitor callback should keep pending barrier until file processing finishes`() {
        val monitor = RecordingFileChangeMonitor()
        val sourceFile = File("/project/app/src/main/Foo.kt")
        val sourceChangedFile = changedFile(CompileFile.Type.Kotlin, sourceFile)
        whenever(fileChangesHandler.filter(listOf(sourceFile))).thenReturn(listOf(sourceChangedFile))
        val callbacks = mutableListOf<FileChangeResult>()

        manager.start(monitor, callbacks::add)
        monitor.notifyChanges(listOf(sourceFile), emptyList())

        inOrder(deployStateManager, deployFileManager).apply {
            verify(deployStateManager).beginFileProcessing()
            verify(deployFileManager).addChangedFile(listOf(sourceChangedFile))
            verify(deployStateManager).endFileProcessing()
        }
        assertEquals(listOf(FileChangeResult(listOf(sourceChangedFile))), callbacks)
    }

    @Test
    fun `watch overflow should reconcile git changes inside pending barrier`() {
        val monitor = RecordingFileChangeMonitor()

        manager.start(monitor) {}
        monitor.notifyOverflow()

        inOrder(deployStateManager, gitFileChangesDetector).apply {
            verify(deployStateManager).beginFileProcessing()
            verify(gitFileChangesDetector).updateChangedFiles()
            verify(deployStateManager).endFileProcessing()
        }
    }

    @Test
    fun `cancelled monitor task should release pending barrier`() {
        val monitor = RecordingFileChangeMonitor()
        val taskRunnerManager = mock<com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager>()
        val job = Job()
        whenever(taskRunnerManager.runBackgroundSafe(any(), any(), any(), any(), any())).thenReturn(job)
        val manager = FileChangeManager(
            fileChangesHandler,
            deployFileManager,
            dependencyChangeManager,
            gitFileChangesDetector,
            deployStateManager,
            taskRunnerManager,
            mock(),
        )

        manager.start(monitor) {}
        monitor.notifyChanges(emptyList(), emptyList())
        job.cancel()

        verify(deployStateManager).beginFileProcessing()
        verify(deployStateManager).endFileProcessing()
    }

    @Test
    fun `monitor file scanning should not hold project lock`() {
        val sourceFile = File("/project/app/src/main/Foo.kt")
        val sourceChangedFile = changedFile(CompileFile.Type.Kotlin, sourceFile)
        val filterStarted = CountDownLatch(1)
        val releaseFilter = CountDownLatch(1)
        whenever(fileChangesHandler.filter(listOf(sourceFile))).thenAnswer {
            filterStarted.countDown()
            releaseFilter.await(5, TimeUnit.SECONDS)
            listOf(sourceChangedFile)
        }
        val taskRunnerManager = lockingTaskRunnerManager()
        val manager = FileChangeManager(
            fileChangesHandler,
            deployFileManager,
            dependencyChangeManager,
            gitFileChangesDetector,
            deployStateManager,
            taskRunnerManager,
            mock(),
        )
        val monitor = RecordingFileChangeMonitor()
        val projectWriteFinished = CountDownLatch(1)

        try {
            manager.start(monitor) {}
            monitor.notifyChanges(listOf(sourceFile), emptyList())
            assertTrue(filterStarted.await(1, TimeUnit.SECONDS))
            val projectWrite = thread {
                taskRunnerManager.runProjectWriteLocked("Run configuration sync") {
                    projectWriteFinished.countDown()
                }
            }

            assertTrue(projectWriteFinished.await(1, TimeUnit.SECONDS))
            releaseFilter.countDown()
            projectWrite.join(1_000)
        } finally {
            releaseFilter.countDown()
            taskRunnerManager.dispose()
        }
    }

    private fun lockingTaskRunnerManager(): TaskRunnerManager {
        val projectLock = ReentrantLock()
        return TaskRunnerManager(
            logger = mock(),
            deployStateManager = mock(),
            juggServer = mock<JuggServer>(),
            hostTaskExecutor = object : IHostTaskExecutor {
                override val isOnEdt: Boolean = false

                override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
                    action.run()
                }
            },
            executionLockManager = object : IExecutionLockManager {
                override fun <T> withProjectLock(command: String, action: () -> T): T = projectLock.withLock(action)

                override fun <T : Any> tryWithProjectLock(command: String, action: () -> T): T? {
                    if (!projectLock.tryLock()) return null
                    return try {
                        action()
                    } finally {
                        projectLock.unlock()
                    }
                }

                override fun readProjectLockOwner(): ExecutionLockOwner? = null
            },
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    private fun changedFile(type: CompileFile.Type, file: File): ChangedFile {
        return ChangedFile(type, file, file.parentFile, ModuleInfo.virtualModule)
    }

    private class RecordingFileChangeMonitor : IFileChangeMonitor {
        private lateinit var listener: FileChangesListener

        override fun startListen(listener: FileChangesListener) {
            this.listener = listener
        }

        fun notifyChanges(changedFiles: List<File>, deletedFiles: List<File>) {
            listener.onFileChanges(changedFiles, deletedFiles)
        }

        fun notifyOverflow() {
            listener.onOverflow()
        }
    }
}
