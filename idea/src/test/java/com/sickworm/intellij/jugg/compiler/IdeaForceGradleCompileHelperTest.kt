package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import org.junit.Assert
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Verifies Gradle fallback result mapping in [IdeaForceGradleCompileHelper].
 */
class IdeaForceGradleCompileHelperTest {

    @Test
    fun `gradle build should fail when deploy fails`() {
        val helper = createHelper(
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = true,
                    isCompileSuccess = true,
                    isDeploySuccess = false,
                    isCancel = false,
                ),
            )
        )

        val result = helper.executeGradleCompileBlocking(autoConfirm = true)

        Assert.assertEquals("failed", result.status)
        Assert.assertEquals(true, result.isCompileSuccess)
        Assert.assertEquals(false, result.isDeploySuccess)
    }

    @Test
    fun `gradle build should expose no device deploy failure`() {
        val helper = createHelper(
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = true,
                    isCompileSuccess = true,
                    isDeploySuccess = false,
                    isCancel = false,
                ),
                detail = """
                    Jugg compile started.
                    No device found. Stop installing.
                """.trimIndent(),
            )
        )

        val result = helper.executeGradleCompileBlocking(autoConfirm = true)

        Assert.assertEquals("failed", result.status)
        Assert.assertEquals("No device found. Stop installing.", result.message)
        Assert.assertEquals(true, result.isCompileSuccess)
        Assert.assertEquals(false, result.isDeploySuccess)
    }

    private fun createHelper(runInvocationResult: JuggRunInvocationResult): IdeaForceGradleCompileHelper {
        val runner = mock<IJuggConfigurationRunner>()
        whenever(runner.runFirstConfiguration(isRpcMode = true, isSkipDeploy = false, isAlwaysRestartApp = false))
            .thenReturn(runInvocationResult)

        return IdeaForceGradleCompileHelper(
            project = mock<Project>(),
            juggConfigurationRunner = runner,
            deployFileManager = mock<DeployFileManager>(),
            taskRunnerManager = mock<TaskRunnerManager>(),
            compileContextManager = mock<CompileContextManager>(),
            logger = mock<Logger>(),
        )
    }
}
