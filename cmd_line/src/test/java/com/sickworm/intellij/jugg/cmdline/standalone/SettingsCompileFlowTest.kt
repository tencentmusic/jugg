package com.sickworm.intellij.jugg.cmdline.standalone

import com.sickworm.intellij.jugg.cmdline.SettingsFileRule
import org.junit.Rule
import com.sickworm.intellij.jugg.ide.bean.JuggSettingsEditor
import com.sickworm.intellij.jugg.project.runtime.*
import org.junit.Test
import java.io.File
import kotlin.test.*

/** Runs global edits through a live host with real Gradle and incremental compiler baselines. */
class SettingsCompileFlowTest {
    @get:Rule val settingsFileRule = SettingsFileRule()
    @Test fun `backup edit discards the live baseline and failed rebuild remains required`() {
        val project = File("../android_demo_project").canonicalFile
        val source = project.resolve("app/src/main/java/com/sickworm/jugg/demo/testcase/subclass/RootClass.java")
        val original = source.readText()
        val activity = StandaloneDaemonActivity()
        var services: StandaloneProjectServices? = null
        try {
            project.resolve("build/jugg").deleteRecursively()
            services = StandaloneProjectServices(project, RuntimeInfo("standalone", "test", "java-17", "test"),
                activity, StandaloneProjectResources())
            val initialized = services.initializeProject()
            assertTrue(initialized.isSuccess, initialized.message)
            val profile = CliRunConfigurationGenerator.generateForModuleIdentity("app", "app", "debug",
                "build/app/outputs/apk/debug/*.apk")
            services.configurationStore.save(profile)
            services.configurationStore.select(profile.id)
            val runner = StandaloneConfigurationRunner(services, activity)
            assertCompile(runner, gradle = true)
            source.writeText(original.replace("\"func1\"", "\"settings-first\""))
            assertCompile(runner, gradle = false)
            assertNotNull(services.compilerHelper.juggCompiler)
            JuggSettingsEditor().set("isEnableBackupClasspath", "true", confirm = true)
            services.runProjectWriteLocked("Observe setting effect") {
                assertNull(services.compilerHelper.juggCompiler)
                assertTrue(services.deployTargetManager.getApks().isEmpty())
            }
            services.configurationStore.save(profile.copy(compileCommand = "./gradlew :app:missingSettingsFlowTask"))
            val failed = runner.runFirstConfiguration(true, true, false)
            assertFalse(failed.isSuccess)
            assertTrue(failed.runResult?.isGradleCompile == true)
            assertNull(services.compilerHelper.juggCompiler)
            services.configurationStore.save(profile)
            assertCompile(runner, gradle = true)
            source.writeText(original.replace("\"func1\"", "\"settings-second\""))
            assertCompile(runner, gradle = false)
            assertTrue(services.pathManager.stagingDir.walkTopDown().any { it.extension == "dex" && it.length() > 0 })
        } finally {
            services?.close()
            source.writeText(original)
        }
    }

    private fun assertCompile(runner: StandaloneConfigurationRunner, gradle: Boolean) {
        val result = runner.runFirstConfiguration(true, true, false)
        assertTrue(result.isSuccess, result.errorMessage ?: result.detail)
        assertEquals(gradle, result.runResult?.isGradleCompile)
    }
}
