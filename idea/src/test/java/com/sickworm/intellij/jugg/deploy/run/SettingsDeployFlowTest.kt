package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.DeployHistoryManager
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.run.applychanges.CustomApkInstallScriptRunner
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowMockBackend
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.bean.JuggSettingsEditor
import com.sickworm.intellij.jugg.mock.context
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.change.FileChangesHandler
import com.sickworm.intellij.jugg.project.runtime.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.nio.file.Files
import kotlin.test.*

/** Connects user edits and durable project effects to real deployment orchestration. */
class SettingsDeployFlowTest {
    @Test fun `compat edit requires reinstall after canceled and failed deployment`() {
        val previousRoot = JuggGlobalPathManager.rootDir
        val root = Files.createTempDirectory("settings-deploy-flow").toFile()
        JuggGlobalPathManager.rootDir = root.resolve("global")
        try {
            DeployFlowMockBackend.initSettings()
            val paths = JuggPathManager(root.resolve("project").apply { mkdirs() })
            fun history() = DeployHistoryManager(paths,
                FileChangesHandler(paths.projectDir, paths.juggRootDir, logger), logger)
            val history = history()
            history.reInitAfterFullCompiled(FullBuildInfo("./gradlew assembleDebug",
                com.sickworm.intellij.jugg.compiler.BuildTarget.APP, System.currentTimeMillis()),
                context.apkInfos, context.modules, System.currentTimeMillis())
            val fixture = DeployFlowMockBackend.buildMatchedReinstallFixture(history)
            JuggSettingsEditor().set("deviceCompatRecordJson", """[{"displayName":"Settings device"}]""")
            val tasks = com.sickworm.intellij.jugg.createTestTaskRunnerManager(paths)
            try { tasks.runProjectWriteLocked("Apply setting") { JuggSettingsProjectEffects(paths, history).apply() } }
            finally { tasks.dispose() }
            assertTrue(history().isCleanAndReinstall)
            assertCanceled(fixture)
            assertTrue(history().isCleanAndReinstall)
            Mockito.mockConstruction(CustomApkInstallScriptRunner::class.java) { runner, _ ->
                Mockito.doThrow(IllegalStateException("Settings flow install failed")).`when`(runner).run(any())
            }.use {
                val failed = fixture.helper.deploy(fixture.deployOptions.copy(customApkInstallScript = "./install.sh"))
                assertFalse(failed.isSuccess)
                assertTrue(history().isCleanAndReinstall)
                assertEquals(0, fixture.virtualDevice.installInvokeCount)
            }
            val result = fixture.helper.deploy(fixture.deployOptions)
            assertTrue(result.isSuccess, result.failedReason)
            assertTrue(fixture.virtualDevice.installInvokeCount > 0)
            assertFalse(history().isCleanAndReinstall)
        } finally {
            JuggGlobalPathManager.rootDir = previousRoot
            JuggSettings.reload()
            root.deleteRecursively()
        }
    }

    private fun assertCanceled(fixture: com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowFixture) {
        val process = Mockito.mock(com.sickworm.intellij.jugg.ide.bean.IProcessHandler::class.java)
        Mockito.`when`(process.isCanceled).thenReturn(true)
        assertFalse(fixture.helper.deploy(fixture.deployOptions.copy(processHandler = process)).isSuccess)
        assertEquals(0, fixture.virtualDevice.installInvokeCount)
    }
}
