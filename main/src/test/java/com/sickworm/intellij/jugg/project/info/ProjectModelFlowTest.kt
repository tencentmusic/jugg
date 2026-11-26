package com.sickworm.intellij.jugg.project.info

import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.compiler.context.CompileEnvironmentSource
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.runtime.IHostTaskExecutor
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.lang.Runnable

/** Verifies project model refresh keeps the shared task and lock boundary. */
class ProjectModelFlowTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun localFetch_submitsProjectTaskBeforeGradleExecution() {
        val pathManager = JuggPathManager(temporaryFolder.newFolder("project"))
        val hostTaskExecutor = RecordingHostTaskExecutor()
        val taskRunnerManager = TaskRunnerManager(
            TestGlobal.logger,
            mock<IDeployStateManager>(),
            mock<JuggServer>(),
            hostTaskExecutor,
            pathManager,
            "test",
            "test",
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val compileContextManager = mock<CompileContextManager>()
        val manager = GradleProjectInfoLocalFetchManager(
            pathManager,
            compileContextManager,
            taskRunnerManager,
            mock<IDependencyChangeManager>(),
            mock<IDeployHistoryManager>(),
            CompileEnvironmentSource(null, emptyList()),
            TestGlobal.logger,
        )

        manager.runUpdateIfNeeded(isForce = true)

        assertEquals("Jugg: Update project info from gradle", hostTaskExecutor.title)
        assertFalse(pathManager.initGradleFilePath.exists())
        verify(compileContextManager).ensureInitProjectInfo()
        manager.close()
        taskRunnerManager.dispose()
    }

    private class RecordingHostTaskExecutor : IHostTaskExecutor {
        override val isOnEdt: Boolean = false
        var title: String? = null

        override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
            this.title = title
        }
    }
}
