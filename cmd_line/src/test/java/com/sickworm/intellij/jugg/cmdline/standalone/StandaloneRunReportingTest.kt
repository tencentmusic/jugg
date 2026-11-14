package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.LastChangedDeployRegistry
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.run.DeployTaskResult
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.runtime.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.Answers
import java.io.File
import kotlin.test.*

/** Protects file reporting across the standalone compile/deploy boundary. */
class StandaloneRunReportingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `compile reports actual successful inputs without deployment`() {
        val fixture = Fixture()
        val result = fixture.runner.runFirstConfiguration(true, true, false)
        assertTrue(result.isSuccess, result.errorMessage)
        assertEquals(listOf("src/Changed.kt"), result.runResult?.compiledFiles)
        assertEquals(emptyList(), result.runResult?.deployedFiles)
    }

    @Test fun `deploy reports newly committed files`() {
        val fixture = Fixture()
        fixture.recordDeploy = true
        val result = fixture.runner.runFirstConfiguration(true, false, false)
        assertTrue(result.isSuccess, result.errorMessage)
        assertEquals(listOf("src/Changed.kt"), result.runResult?.compiledFiles)
        assertEquals(listOf("src/Changed.kt"), result.runResult?.deployedFiles)
    }

    @Test fun `empty round does not report the previous deployment`() {
        val fixture = Fixture()
        LastChangedDeployRegistry.INSTANCE.record(fixture.project.path, listOf(fixture.source))
        fixture.compileResult = CompileTaskResult.incrementalSuccess(CompileResult(
            CompileTask(emptyList(), fixture.project, CompileStatusHolder.DEFAULT), emptyList(), emptyList(),
        ))
        val result = fixture.runner.runFirstConfiguration(true, false, false)
        assertTrue(result.isSuccess, result.errorMessage)
        assertEquals(emptyList(), result.runResult?.compiledFiles)
        assertEquals(emptyList(), result.runResult?.deployedFiles)
    }

    @Test fun `failed deployment preserves compilation details without claiming deployed files`() {
        val fixture = Fixture()
        fixture.recordDeploy = true
        fixture.deploySuccess = false
        val result = fixture.runner.runFirstConfiguration(true, false, false)
        assertFalse(result.isSuccess)
        assertEquals(listOf("src/Changed.kt"), result.runResult?.compiledFiles)
        assertEquals(emptyList(), result.runResult?.deployedFiles)
    }

    @Test fun `device exception preserves successful compile and reports final failure`() {
        val fixture = Fixture()
        fixture.deviceFailure = IllegalStateException("device lookup failed")
        val result = fixture.runner.runFirstConfiguration(true, false, false)
        assertFalse(result.isSuccess)
        assertEquals(true, result.runResult?.isCompileSuccess)
        assertEquals(listOf("src/Changed.kt"), result.runResult?.compiledFiles)
        assertEquals(emptyList(), result.runResult?.deployedFiles)
        assertEquals("device lookup failed", result.runResult?.failedReason)
        assertEquals(result.runResult, fixture.finishedResult)
        assertEquals(result.runResult, fixture.reportedResult)
    }

    @Test fun `failed deploy reaches failure reporting boundary`() {
        val fixture = Fixture()
        fixture.deploySuccess = false
        val result = fixture.runner.runFirstConfiguration(true, false, false)
        assertEquals(result.runResult, fixture.reportedResult)
        assertEquals(true, fixture.reportEligible)
    }

    @Test fun `diagnostic reporting failure preserves successful run`() {
        val fixture = Fixture()
        fixture.reportingFailure = IllegalStateException("config unavailable")
        val result = fixture.runner.runFirstConfiguration(true, true, false)
        assertTrue(result.isSuccess)
        assertEquals(listOf("src/Changed.kt"), result.runResult?.compiledFiles)
        assertEquals(result.runResult, fixture.finishedResult)
    }

    private inner class Fixture {
        val project: File = temporary.newFolder()
        val source = project.resolve("src/Changed.kt")
        private val input = CompileFile(CompileFile.Type.Kotlin, source, project, Mockito.mock(ModuleInfo::class.java))
        var compileResult = CompileTaskResult.incrementalSuccess(CompileResult(
            CompileTask(listOf(input), project, CompileStatusHolder.DEFAULT), listOf(Result.success(input)), emptyList(),
        ))
        var reportingFailure: Exception? = null
        var deviceFailure: Exception? = null
        var finishedResult: com.sickworm.intellij.jugg.compiler.ui.RunResult? = null
        var reportedResult: com.sickworm.intellij.jugg.compiler.ui.RunResult? = null
        var reportEligible: Boolean? = null
        var recordDeploy = false
        var deploySuccess = true
        private val paths = JuggPathManager(project)
        private val store = CliRunConfigurationStore(paths).also {
            val profile = CliRunConfigurationGenerator.generateForModuleIdentity("app", "app", "debug", "app.apk")
            it.save(profile)
            it.select(profile.id)
        }
        private val compiler = Mockito.mock(JuggCompilerHelper::class.java) { call ->
            if (call.method.name == "compile") compileResult else Answers.RETURNS_DEFAULTS.answer(call)
        }
        private val deployer = Mockito.mock(JuggDeployerHelper::class.java) { call ->
            if (call.method.name == "deploy") {
                if (recordDeploy) LastChangedDeployRegistry.INSTANCE.record(project.path, listOf(source))
                DeployTaskResult(deploySuccess, 0, failedReason = if (deploySuccess) null else "Deployment failed")
            } else Answers.RETURNS_DEFAULTS.answer(call)
        }
        private val targets = Mockito.mock(StandaloneDeployTargetManager::class.java) { call ->
            if (call.method.name == "getTargetDevices") {
                deviceFailure?.let { throw it }
                listOf(Mockito.mock(IDevice::class.java))
            }
            else Answers.RETURNS_DEFAULTS.answer(call)
        }
        private val services = Mockito.mock(StandaloneProjectServices::class.java) { call ->
            when {
                call.method.name.startsWith("initializeProject") -> StandaloneProjectInitializationResult(true, "Ready")
                call.method.name == "runProjectWriteLocked" -> (call.arguments[1] as Function0<*>).invoke()
                call.method.name == "getConfigurationStore" -> store
                call.method.name == "getPathManager" -> paths
                call.method.name == "getProjectDir" -> project
                call.method.name == "getCompilerHelper" -> compiler
                call.method.name == "getDeployerHelper" -> deployer
                call.method.name == "getDeployTargetManager" -> targets
                call.method.name == "reportRunFailure" -> {
                    reportingFailure?.let { throw it }
                    reportedResult = call.arguments[0] as com.sickworm.intellij.jugg.compiler.ui.RunResult
                    reportEligible = call.arguments[1] as Boolean
                    null
                }
                call.method.name == "onBuildFinished" -> {
                    finishedResult = call.arguments[0] as? com.sickworm.intellij.jugg.compiler.ui.RunResult
                    null
                }
                call.method.name == "getLogger" -> Mockito.mock(Logger::class.java)
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        val runner = StandaloneConfigurationRunner(services, StandaloneDaemonActivity())
    }
}
