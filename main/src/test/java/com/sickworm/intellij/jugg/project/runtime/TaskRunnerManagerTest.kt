package com.sickworm.intellij.jugg.project.runtime

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.FileProcessingWaitResult
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.ReportEventData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TaskRunnerManagerTest {

    @Test
    fun `dispatcher reflects coroutine scope`() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val manager = newManager(
                lockManager = ImmediateExecutionLockManager(),
                coroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
            )

            assertSame(dispatcher, manager.dispatcher)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `incremental compile state starts only after project lock is acquired`() {
        val lockManager = BlockingExecutionLockManager()
        val deployStateManager = RecordingDeployStateManager()
        val hostTaskExecutor = ThreadHostTaskExecutor()
        val manager = newManager(lockManager, deployStateManager, hostTaskExecutor)
        val actionStarted = CountDownLatch(1)

        manager.runTaskSafe("project write", Runnable { actionStarted.countDown() })

        assertTrue(lockManager.projectLockRequested.await(1, TimeUnit.SECONDS))
        assertFalse(deployStateManager.isInitializingIncrementalCompile)
        lockManager.allowProjectLock.countDown()
        assertTrue(actionStarted.await(1, TimeUnit.SECONDS))
        hostTaskExecutor.join()
        assertTrue(deployStateManager.initializingStateEntered.get())
        assertFalse(deployStateManager.isInitializingIncrementalCompile)
    }

    @Test
    fun `project task exposes initializing state through deploy state contract`() {
        val deployStateManager = RecordingDeployStateManager()
        val manager = newManager(ImmediateExecutionLockManager(), deployStateManager)
        val observedInitializingState = AtomicBoolean()

        manager.runTaskSafe("project write", Runnable {
            observedInitializingState.set(deployStateManager.isInitializingIncrementalCompile)
        })

        assertTrue(observedInitializingState.get())
    }

    @Test
    fun `task without project write cannot block incremental compile`() {
        val manager = newManager(ImmediateExecutionLockManager())

        assertThrows(IllegalArgumentException::class.java) {
            manager.runTaskSafe(
                jobName = "invalid task",
                action = Runnable {},
                isProjectWrite = false,
                isBlockIncrementalCompile = true,
            )
        }
    }

    @Test
    fun `project write can keep incremental compile state unchanged`() {
        val lockManager = ImmediateExecutionLockManager()
        val deployStateManager = RecordingDeployStateManager()
        val manager = newManager(lockManager, deployStateManager)

        manager.runTaskSafe(
            jobName = "project checkpoint",
            action = Runnable {},
            isProjectWrite = true,
            isBlockIncrementalCompile = false,
        )

        assertTrue(lockManager.projectCommands.contains("project checkpoint"))
        assertFalse(deployStateManager.initializingStateEntered.get())
    }

    @Test
    fun `blocking project tasks serialize while non blocking project task can run`() {
        val hostTaskExecutor = ConcurrentHostTaskExecutor()
        val manager = newManager(ImmediateExecutionLockManager(), hostTaskExecutor = hostTaskExecutor)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val nonBlockingEntered = CountDownLatch(1)

        manager.runTaskSafe("first blocking task", Runnable {
            firstEntered.countDown()
            releaseFirst.await(5, TimeUnit.SECONDS)
        })
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

        manager.runTaskSafe("second blocking task", Runnable { secondEntered.countDown() })
        manager.runTaskSafe(
            jobName = "non blocking task",
            action = Runnable { nonBlockingEntered.countDown() },
            isProjectWrite = true,
            isBlockIncrementalCompile = false,
        )

        try {
            assertTrue(nonBlockingEntered.await(1, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
            hostTaskExecutor.joinAll()
        }
        assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `independent project transactions serialize in the same runtime`() {
        val manager = newManager(ImmediateExecutionLockManager())
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstThread = Thread {
            manager.runProjectWriteLocked("first transaction") {
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
        }
        val secondThread = Thread {
            manager.runProjectWriteLocked("second transaction") { secondEntered.countDown() }
        }

        firstThread.start()
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        secondThread.start()
        try {
            assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
            firstThread.join(5_000)
            secondThread.join(5_000)
        }
        assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `project transaction can wait for non blocking task in the same runtime`() {
        val projectDir = Files.createTempDirectory("jugg-task-runner-shared-lease").toFile()
        val lockManager = FileExecutionLockManager(JuggPathManager(projectDir), RuntimeIdentity("idea", "test"))
        val hostTaskExecutor = ConcurrentHostTaskExecutor()
        val manager = newManager(lockManager, hostTaskExecutor = hostTaskExecutor)
        val childCompleted = CountDownLatch(1)
        val childCompletedBeforeTimeout = AtomicBoolean()

        val outerThread = Thread {
            manager.runProjectWriteLocked("remote compile") {
                manager.runTaskSafe(
                    jobName = "project info",
                    action = Runnable { childCompleted.countDown() },
                    isProjectWrite = true,
                    isBlockIncrementalCompile = false,
                )
                childCompletedBeforeTimeout.set(childCompleted.await(500, TimeUnit.MILLISECONDS))
            }
        }
        outerThread.start()
        outerThread.join(2_000)
        hostTaskExecutor.joinAll()

        assertFalse(outerThread.isAlive)
        assertTrue(childCompletedBeforeTimeout.get())
    }

    @Test
    fun `project transaction can wait for blocking task in the same runtime`() {
        val projectDir = Files.createTempDirectory("jugg-task-runner-blocking-child").toFile()
        val lockManager = FileExecutionLockManager(JuggPathManager(projectDir), RuntimeIdentity("idea", "test"))
        val hostTaskExecutor = ConcurrentHostTaskExecutor()
        val manager = newManager(lockManager, hostTaskExecutor = hostTaskExecutor)
        val childCompleted = CountDownLatch(1)
        val childCompletedBeforeTimeout = AtomicBoolean()

        val outerThread = Thread {
            manager.runProjectWriteLocked("remote compile") {
                manager.runTaskSafe("blocking child", Runnable { childCompleted.countDown() })
                childCompletedBeforeTimeout.set(childCompleted.await(500, TimeUnit.MILLISECONDS))
            }
        }
        outerThread.start()
        outerThread.join(2_000)
        hostTaskExecutor.joinAll()

        assertFalse(outerThread.isAlive)
        assertTrue(childCompletedBeforeTimeout.get())
    }

    @Test
    fun `project transaction can wait for project background task in the same runtime`() {
        val projectDir = Files.createTempDirectory("jugg-task-runner-background-child").toFile()
        val lockManager = FileExecutionLockManager(JuggPathManager(projectDir), RuntimeIdentity("idea", "test"))
        val manager = newManager(lockManager)
        val childCompleted = CountDownLatch(1)
        val childCompletedBeforeTimeout = AtomicBoolean()

        val outerThread = Thread {
            manager.runProjectWriteLocked("remote compile") {
                manager.runBackgroundSafe(
                    jobName = "project background child",
                    isProjectWrite = true,
                    action = Runnable { childCompleted.countDown() },
                )
                childCompletedBeforeTimeout.set(childCompleted.await(500, TimeUnit.MILLISECONDS))
            }
        }
        outerThread.start()
        outerThread.join(2_000)
        manager.dispose()

        assertFalse(outerThread.isAlive)
        assertTrue(childCompletedBeforeTimeout.get())
    }

    @Test
    fun `project transaction can wait for async project transaction in the same runtime`() {
        val manager = newManager(ImmediateExecutionLockManager())
        val childCompleted = CountDownLatch(1)
        val childCompletedBeforeTimeout = AtomicBoolean()

        val outerThread = Thread {
            manager.runProjectWriteLocked("remote compile") {
                manager.runAsyncSafe("async child") {
                    manager.runProjectWriteLocked("async child transaction") { childCompleted.countDown() }
                }
                childCompletedBeforeTimeout.set(childCompleted.await(500, TimeUnit.MILLISECONDS))
            }
        }
        outerThread.start()
        outerThread.join(2_000)
        manager.dispose()

        assertFalse(outerThread.isAlive)
        assertTrue(childCompletedBeforeTimeout.get())
    }

    @Test
    fun `try project write returns immediately while project transaction is active`() {
        val manager = newManager(ImmediateExecutionLockManager())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ownerThread = Thread {
            manager.runProjectWriteLocked("blocking task") {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        ownerThread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            assertNull(manager.tryRunProjectWriteLocked("status") { "acquired" })
        } finally {
            release.countDown()
            ownerThread.join(5_000)
        }
    }

    @Test
    fun `incremental compile state is cleared when task fails`() {
        val deployStateManager = RecordingDeployStateManager()
        val manager = newManager(ImmediateExecutionLockManager(), deployStateManager)

        manager.runTaskSafe("failing project write", Runnable { throw IllegalStateException("failed") })

        assertTrue(deployStateManager.initializingStateEntered.get())
        assertFalse(deployStateManager.isInitializingIncrementalCompile)
    }

    @Test
    fun `project background task runs under project lock`() {
        val lockManager = ImmediateExecutionLockManager()
        val manager = newManager(lockManager)

        val job = manager.runBackgroundSafe(
            "project maintenance",
            isProjectWrite = true,
            action = Runnable {},
        )
        runBlocking { job.join() }

        assertTrue(lockManager.projectCommands.contains("project maintenance"))
    }

    @Test
    fun `background task does not report completion through jugg server`() {
        val juggServer = mock<JuggServer>()
        val manager = newManager(ImmediateExecutionLockManager(), juggServer = juggServer)

        val job = manager.runBackgroundSafe("background maintenance", action = Runnable {})
        runBlocking { job.join() }

        verify(juggServer, never()).report(any<ReportEventData>())
    }

    @Test
    fun `host task without project write does not change incremental compile state`() {
        val lockManager = ImmediateExecutionLockManager()
        val deployStateManager = RecordingDeployStateManager()
        val manager = newManager(lockManager, deployStateManager)

        manager.runTaskSafe(
            jobName = "install global tools",
            action = Runnable {},
            isProjectWrite = false,
            isBlockIncrementalCompile = false,
        )

        assertTrue(lockManager.projectCommands.isEmpty())
        assertFalse(deployStateManager.initializingStateEntered.get())
    }

    @Test
    fun `successful host task is not reported`() {
        val juggServer = mock<JuggServer>()
        val manager = newManager(ImmediateExecutionLockManager(), juggServer = juggServer)

        manager.runTaskSafe("report task", Runnable {})

        verify(juggServer, never()).report(any<ReportEventData>())
    }

    @Test
    fun `failed host task is reported`() {
        val juggServer = mock<JuggServer>()
        val manager = newManager(ImmediateExecutionLockManager(), juggServer = juggServer)

        manager.runTaskSafe("failed task", Runnable { throw IllegalStateException("boom") })

        val reportCaptor = argumentCaptor<ReportEventData>()
        verify(juggServer).report(reportCaptor.capture())
        assertEquals("failed task", reportCaptor.firstValue.action)
        assertFalse(reportCaptor.firstValue.isSuccess)
        assertEquals("boom", reportCaptor.firstValue.detail)
    }

    @Test
    fun `background task does not use write lock by default`() {
        val lockManager = ImmediateExecutionLockManager()
        val manager = newManager(lockManager)

        val job = manager.runBackgroundSafe("background maintenance", action = Runnable {})
        runBlocking { job.join() }

        assertTrue(lockManager.projectCommands.isEmpty())
    }

    @Test
    fun `dispose cancels delayed background task`() {
        val manager = newManager(ImmediateExecutionLockManager())
        val executed = CountDownLatch(1)

        val job = manager.runBackgroundSafe("delayed", delayMs = 10_000, action = Runnable {
            executed.countDown()
        })
        manager.dispose()

        assertTrue(job.isCancelled)
        assertFalse(executed.await(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `disposed manager does not start queued host task`() {
        val hostTaskExecutor = QueuedHostTaskExecutor()
        val manager = newManager(ImmediateExecutionLockManager(), hostTaskExecutor = hostTaskExecutor)
        val executed = AtomicBoolean()

        manager.runTaskSafe("queued", Runnable { executed.set(true) })
        manager.dispose()
        hostTaskExecutor.runQueuedTask()

        assertFalse(executed.get())
    }

    private fun newManager(
        lockManager: IExecutionLockManager,
        deployStateManager: IDeployStateManager = RecordingDeployStateManager(),
        hostTaskExecutor: IHostTaskExecutor = ImmediateHostTaskExecutor(),
        coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        juggServer: JuggServer = mock(),
    ): TaskRunnerManager {
        return TaskRunnerManager(
            logger = mock<Logger>(),
            deployStateManager = deployStateManager,
            juggServer = juggServer,
            hostTaskExecutor = hostTaskExecutor,
            executionLockManager = lockManager,
            coroutineScope = coroutineScope,
        )
    }

    private class RecordingDeployStateManager : IDeployStateManager {
        private val initializing = AtomicBoolean()
        val initializingStateEntered = AtomicBoolean()

        override val deployState: JuggDeployState = JuggDeployState.READY
        override var isBuildFileChanged: Boolean = false
        override var whatBuildFileChanged: String = ""

        override var isInitializingIncrementalCompile: Boolean
            get() = initializing.get()
            set(value) {
                initializing.set(value)
                if (value) initializingStateEntered.set(true)
            }

        override fun updateDeployState(): JuggDeployState = JuggDeployState.READY

        override fun getDeployState(device: IDevice): JuggDeployState = JuggDeployState.READY

        override fun beginFileProcessing() = Unit

        override fun endFileProcessing() = Unit

        override fun hasPendingFileProcessing(): Boolean = false

        override fun waitForPendingFileProcessing(timeoutMs: Long): FileProcessingWaitResult {
            return FileProcessingWaitResult(false, 0, 0L, 0)
        }
    }

    private class ImmediateHostTaskExecutor : IHostTaskExecutor {
        override val isOnEdt: Boolean = false

        override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
            action.run()
        }
    }

    private class ThreadHostTaskExecutor : IHostTaskExecutor {
        override val isOnEdt: Boolean = false
        private lateinit var thread: Thread

        override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
            thread = Thread(action, "task-runner-manager-test")
            thread.start()
        }

        fun join() {
            thread.join(1_000)
            assertFalse(thread.isAlive)
        }
    }

    private class ConcurrentHostTaskExecutor : IHostTaskExecutor {
        override val isOnEdt: Boolean = false
        private val threads = CopyOnWriteArrayList<Thread>()

        override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
            Thread(action, "task-runner-manager-test").also {
                threads += it
                it.start()
            }
        }

        fun joinAll() {
            threads.forEach {
                it.join(2_000)
                assertFalse(it.isAlive)
            }
        }
    }

    private class QueuedHostTaskExecutor : IHostTaskExecutor {
        override val isOnEdt: Boolean = false
        private lateinit var action: Runnable

        override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
            this.action = action
        }

        fun runQueuedTask() {
            action.run()
        }
    }

    private class BlockingExecutionLockManager : IExecutionLockManager {
        val projectLockRequested = CountDownLatch(1)
        val allowProjectLock = CountDownLatch(1)

        override fun <T> withProjectLock(command: String, action: () -> T): T {
            projectLockRequested.countDown()
            assertTrue(allowProjectLock.await(1, TimeUnit.SECONDS))
            return action()
        }

        override fun <T : Any> tryWithProjectLock(command: String, action: () -> T): T? {
            return if (allowProjectLock.count == 0L) action() else null
        }

        override fun readProjectLockOwner(): ExecutionLockOwner? = null
    }

    private class ImmediateExecutionLockManager : IExecutionLockManager {
        val projectCommands = CopyOnWriteArrayList<String>()

        override fun <T> withProjectLock(command: String, action: () -> T): T {
            projectCommands += command
            return action()
        }

        override fun <T : Any> tryWithProjectLock(command: String, action: () -> T): T? {
            projectCommands += command
            return action()
        }

        override fun readProjectLockOwner(): ExecutionLockOwner? = null
    }
}
