package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.gradle.compile.BaseSshCommand
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import org.junit.Test
import org.mockito.Mockito.after
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class GradleProjectInfoLocalFetchManagerTest {

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
}
