package com.sickworm.intellij.jugg.mcp.actions

import com.intellij.execution.ExecutionResult
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import org.junit.Assert
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class CompileAndDeployMcpToolActionTest {

    @Test
    fun testFailureDetailIsTruncatedAndFullLogIsArtifact() {
        val fullDetail = buildString {
            repeat(5200) { append('x') }
        }
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = false,
                errorMessage = "mock run failed",
                detail = fullDetail,
            )
        )

        val result = action.execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(result.message.contains("See data.detail and artifacts for logs."))
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        val detailPreview = data["detail"] as String
        Assert.assertTrue(detailPreview.contains("[truncated"))
        Assert.assertEquals(5200.0, (data["detailLength"] as Number).toDouble(), 0.0)
        Assert.assertEquals(true, data["detailTruncated"])
        Assert.assertFalse(result.artifacts.isEmpty())
        val logArtifact = result.artifacts.first()
        Assert.assertEquals("log", logArtifact.type)
        Assert.assertTrue(Files.exists(Paths.get(logArtifact.path)))
    }

    @Test
    fun testSuccessDoesNotCarryDetailPayload() {
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(isGradleCompile = false, isCompileSuccess = true, isDeploySuccess = true),
                detail = "compile logs that should not be returned on success",
            )
        )

        val result = action.execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertTrue(data.containsKey("runResult"))
        Assert.assertFalse(data.containsKey("detail"))
        Assert.assertTrue(result.artifacts.isEmpty())
    }

    private fun runtimeWithResult(result: JuggRunInvocationResult): IMcpRuntime {
        return object : IMcpRuntime {
            override val project: Project
                get() = throw UnsupportedOperationException("not used in this test")

            override val deployTargetManager: IDeployTargetManager
                get() = throw UnsupportedOperationException("not used in this test")

            override val forceGradleCompileHelper: ForceGradleCompileHelper
                get() = throw UnsupportedOperationException("not used in this test")

            override val juggConfigurationRunner: IJuggConfigurationRunner = object : IJuggConfigurationRunner {
                override val isCompiling: Boolean = false

                override fun runTask(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler): ExecutionResult {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun forceReInstallNextTime() {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean): JuggRunInvocationResult {
                    return result
                }
            }
        }
    }
}
