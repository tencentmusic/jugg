package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.gradle.compile.BaseSshCommand
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.after
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleProjectInfoLocalFetchManagerTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `background Gradle stderr is logged as debug`() {
        val logger = mock<Logger>()
        val manager = GradleProjectInfoLocalFetchManager(
            mock<Project>(),
            mock<JuggPathManager>(),
            mock<CompileContextManager>(),
            mock<TaskRunnerManager>(),
            mock<IDependencyChangeManager>(),
            mock<IDeployHistoryManager>(),
            logger,
        )
        val command = object : BaseSshCommand() {
            override val baseCommand = "echo background-gradle-error >&2; false"
        }

        try {
            val executor = manager.javaClass.getDeclaredField("cmdExecutor").run {
                isAccessible = true
                get(manager) as CmdExecutor
            }

            executor.invoke(command)

            verify(logger, timeout(1_000)).debug(eq("background-gradle-error"))
            verify(logger, after(200).never()).warn(any<String>(), any())
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `waitForRemoteInitUpdate only waits for flagged refresh`() {
        val logger = mock<Logger>()
        val taskRunnerManager = mock<TaskRunnerManager>()
        val pathManager = JuggPathManager(temporaryFolder.root)
        pathManager.gradleProjectInfoFile.parentFile.mkdirs()
        pathManager.gradleProjectInfoFile.writeText("{}")
        val deployHistoryManager = mock<IDeployHistoryManager>()
        whenever(deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew assembleDebug", BuildTarget.APP, 1L),
        )
        var updateAction: Runnable? = null
        doAnswer {
            updateAction = it.getArgument(1)
            null
        }.whenever(taskRunnerManager).runTaskSafe(any(), any(), any(), any())
        val manager = GradleProjectInfoLocalFetchManager(
            mock<Project>(),
            pathManager,
            mock<CompileContextManager>(),
            taskRunnerManager,
            mock<IDependencyChangeManager>(),
            deployHistoryManager,
            logger,
        )

        manager.runUpdateIfNeeded(isForce = true, specificCompileCommand = "regular-invalid-command")
        assertTrue(manager.isIncrementalCompileAvailable)
        val regularWaitFinished = CountDownLatch(1)
        thread(isDaemon = true) {
            manager.waitForRemoteInitUpdate()
            regularWaitFinished.countDown()
        }
        assertTrue(regularWaitFinished.await(1, TimeUnit.SECONDS))

        manager.runUpdateIfNeeded(
            isForce = true,
            specificCompileCommand = "remote-invalid-command",
            shouldWaitForRemoteInit = true,
        )

        val waitStarted = CountDownLatch(1)
        val waitFinished = CountDownLatch(1)
        val waiter = thread(isDaemon = true) {
            waitStarted.countDown()
            manager.waitForRemoteInitUpdate()
            waitFinished.countDown()
        }
        try {
            assertTrue(waitStarted.await(1, TimeUnit.SECONDS))
            assertFalse(waitFinished.await(100, TimeUnit.MILLISECONDS))

            updateAction!!.run()
            assertTrue(waitFinished.await(1, TimeUnit.SECONDS))
            verify(taskRunnerManager).runTaskSafe(any(), any(), any(), eq(false))
            verify(logger, never()).debug("finalCompileCommand: regular-invalid-command is not normal gradle command, can not update")
            verify(logger).debug("finalCompileCommand: remote-invalid-command is not normal gradle command, can not update")
        } finally {
            if (waitFinished.count > 0) {
                updateAction?.run()
            }
            waiter.join(1_000)
            manager.dispose()
        }
    }

    @Test
    fun `missing project info remains unavailable for incremental compile until refresh finishes`() {
        val taskRunnerManager = mock<TaskRunnerManager>()
        val pathManager = JuggPathManager(temporaryFolder.root)
        val deployHistoryManager = mock<IDeployHistoryManager>()
        whenever(deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew assembleDebug", BuildTarget.APP, 1L),
        )
        var updateAction: Runnable? = null
        doAnswer {
            updateAction = it.getArgument(1)
            null
        }.whenever(taskRunnerManager).runTaskSafe(any(), any(), any(), any())
        val manager = GradleProjectInfoLocalFetchManager(
            mock<Project>(),
            pathManager,
            mock<CompileContextManager>(),
            taskRunnerManager,
            mock<IDependencyChangeManager>(),
            deployHistoryManager,
            mock<Logger>(),
        )

        try {
            manager.runUpdateIfNeeded(isForce = true, specificCompileCommand = "regular-invalid-command")
            pathManager.gradleProjectInfoFile.parentFile.mkdirs()
            pathManager.gradleProjectInfoFile.writeText("{}")

            assertTrue(manager.isProjectInfoAvailable)
            assertTrue(manager.isRebuildingMissingProjectInfo)
            assertFalse(manager.isIncrementalCompileAvailable)
            verify(taskRunnerManager).runTaskSafe(
                eq("Update project info from gradle"),
                any(),
                eq(true),
                eq(false),
            )

            updateAction!!.run()

            assertFalse(manager.isRebuildingMissingProjectInfo)
            assertTrue(manager.isIncrementalCompileAvailable)
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `existing snapshot without full build does not mark missing rebuild`() {
        val taskRunnerManager = mock<TaskRunnerManager>()
        val pathManager = JuggPathManager(temporaryFolder.root)
        pathManager.gradleProjectInfoFile.parentFile.mkdirs()
        pathManager.gradleProjectInfoFile.writeText("{}")
        val deployHistoryManager = mock<IDeployHistoryManager>()
        whenever(deployHistoryManager.getFullBuildInfo()).thenReturn(null)
        val manager = GradleProjectInfoLocalFetchManager(
            mock<Project>(),
            pathManager,
            mock<CompileContextManager>(),
            taskRunnerManager,
            mock<IDependencyChangeManager>(),
            deployHistoryManager,
            mock<Logger>(),
        )

        try {
            manager.runUpdateIfNeeded(isForce = true, specificCompileCommand = "regular-invalid-command")

            assertTrue(manager.isProjectInfoAvailable)
            assertFalse(manager.isRebuildingMissingProjectInfo)
            assertFalse(manager.isIncrementalCompileAvailable)
        } finally {
            manager.dispose()
        }
    }
}
