package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.progress.ProgressIndicator
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.project.runtime.LocalClasspathStoragePathManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class AndroidTestRunSpecPropagationTest {

    private val options = JuggGradleCompileOptions(
        projectRootPath = "/project",
        localClasspathStoragePath = LocalClasspathStoragePathManager(File("/project/build/jugg/classpath")),
        initGradleFilePath = "/project/.gradle/jugg/readProjectInfo.gradle.kts",
        compileCommand = "./gradlew :app:assembleDebug",
        outputApkName = "app/build/outputs/apk/debug/*.apk",
        isRemoteCompile = false,
        isSyncAllProjects = false,
        remoteSshUser = "",
        remoteSshPassword = "",
        remoteSshIp = "",
        remoteSshPort = 22,
        localToRemoteIftConfigName = "",
        localToRemoteSyncPath = "",
        remoteSyncPath = "",
        remoteToLocalIftConfigName = "",
        remoteToLocalSyncPath = "",
        httpProxyIp = "",
        httpProxyPort = 0,
        syncMode = SyncMode.IFT,
        environmentVariables = "",
    )

    @Test
    fun `configuration runner overload accepts androidTest spec and delegates by default`() {
        val spec = AndroidTestRunSpec("com.example.FooTest", "testBar")
        val result = object : ExecutionResult {}
        val runner = object : IJuggConfigurationRunner {
            var calls = 0

            override val isCompiling: Boolean = false

            override fun runTask(
                options: JuggGradleCompileOptions,
                compileUiHandler: CompileUiHandler,
                executor: Executor?,
                runProfile: RunProfile?,
                androidTestRunSpec: AndroidTestRunSpec?,
            ): ExecutionResult {
                calls++
                return result
            }

            override fun forceReInstallNextTime() = Unit

            override fun runFirstConfiguration(
                isRpcMode: Boolean,
                isSkipDeploy: Boolean,
                isAlwaysRestartApp: Boolean,
            ): JuggRunInvocationResult = JuggRunInvocationResult(isSuccess = true)
        }

        assertSame(result, runner.runTask(options, CompileUiHandler.DEFAULT, null, null, spec))
        assertEquals(1, runner.calls)
    }

    @Test
    fun `running task creator overload accepts androidTest spec and delegates by default`() {
        val spec = AndroidTestRunSpec("com.example.FooTest", null)
        val task = object : IJuggRunningTask {
            override val isRunning: Boolean = false
            override fun run(indicator: ProgressIndicator) = Unit
            override fun cancel(onFinishListener: () -> Unit) = onFinishListener()
        }
        val creator = object : IJuggRunningTaskCreator {
            var calls = 0

            override fun createAndRun(
                options: JuggGradleCompileOptions,
                compileUiHandler: CompileUiHandler,
            ): IJuggRunningTask {
                calls++
                return task
            }
        }

        assertSame(task, creator.createAndRun(options, CompileUiHandler.DEFAULT, spec))
        assertEquals(1, creator.calls)
    }
}
