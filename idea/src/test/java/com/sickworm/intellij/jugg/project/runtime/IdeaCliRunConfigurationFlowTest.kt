package com.sickworm.intellij.jugg.project.runtime

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.ide.JuggRunConfiguration
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.info.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/** Verifies IDEA run configurations synchronize with the shared CLI configuration collection. */
class IdeaCliRunConfigurationFlowTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `idea import keeps stable id across rename and selected jugg configuration updates pointer`() {
        val fixture = fixture("idea_import")
        val options = ideaOptions("./gradlew :app:assembleDebug", "app/build/outputs/apk/debug/*.apk")
        val settings = juggSettings("old name", options)
        whenever(fixture.runManager.getConfigurationSettingsList(com.sickworm.intellij.jugg.ide.JuggConfigurationType::class.java)).thenReturn(listOf(settings))
        whenever(fixture.runManager.selectedConfiguration).thenReturn(settings)

        fixture.manager.syncExistingConfigurations()
        val first = fixture.store.loadCurrent()!!
        whenever(settings.name).thenReturn("new name")
        fixture.manager.syncExistingConfigurations()
        val renamed = fixture.store.loadCurrent()!!

        assertTrue(options.cliRunConfigurationId!!.isNotBlank())
        assertEquals(first.id, renamed.id)
        assertEquals("new name", renamed.name)
        assertEquals("idea", renamed.generatedBy)
    }

    @Test
    fun `startup refreshes an existing selected profile from idea state`() {
        val fixture = fixture("startup_refresh")
        val options = ideaOptions("./gradlew :app:assembleDebug", "app/build/outputs/apk/debug/*.apk")
        val settings = juggSettings("jugg:app_local", options)
        whenever(fixture.runManager.getConfigurationSettingsList(com.sickworm.intellij.jugg.ide.JuggConfigurationType::class.java))
            .thenReturn(listOf(settings))
        whenever(fixture.runManager.selectedConfiguration).thenReturn(settings)
        fixture.manager.syncExistingConfigurations()

        options.isRemoteCompile = true
        whenever(settings.name).thenReturn("jugg:app")

        assertTrue(fixture.manager.ensureConfiguration())
        val current = fixture.store.loadCurrent()!!
        assertEquals("jugg:app", current.name)
        assertTrue(current.isRemoteCompile)
    }

    @Test
    fun `successful gradle build updates actual task and apk output of current configuration`() {
        val fixture = fixture("gradle_success")
        val options = ideaOptions("./gradlew :app:assembleDebug", "app/build/outputs/apk/debug/*.apk")
        val settings = juggSettings("app debug", options)
        whenever(fixture.runManager.getConfigurationSettingsList(com.sickworm.intellij.jugg.ide.JuggConfigurationType::class.java)).thenReturn(listOf(settings))
        whenever(fixture.runManager.selectedConfiguration).thenReturn(settings)
        fixture.manager.syncExistingConfigurations()
        val originalId = fixture.store.loadCurrent()!!.id

        fixture.manager.updateAfterSuccessfulGradleBuild(compileOptions(fixture.pathManager, ":paid:assemblePaidRelease", "paid/build/outputs/apk/paid/release/*.apk"))
        val updated = fixture.store.loadCurrent()!!

        assertEquals(originalId, updated.id)
        assertEquals("./gradlew :paid:assemblePaidRelease", updated.compileCommand)
        assertEquals("paid/build/outputs/apk/paid/release/*.apk", updated.outputApkName)
        assertEquals("paid", updated.moduleName)
        assertEquals("paidRelease", updated.variant)
        assertNotEquals(0L, updated.generatedAt)
    }

    @Test
    fun `stale selection event does not replace latest current pointer`() {
        val fixture = fixture("stale_selection")
        val firstOptions = ideaOptions("./gradlew :app:assembleDebug", "app/build/outputs/apk/debug/*.apk")
        val secondOptions = ideaOptions("./gradlew :paid:assemblePaidRelease", "paid/build/outputs/apk/paid/release/*.apk")
        val first = juggSettings("app debug", firstOptions)
        val second = juggSettings("paid release", secondOptions)
        whenever(fixture.runManager.getConfigurationSettingsList(com.sickworm.intellij.jugg.ide.JuggConfigurationType::class.java)).thenReturn(listOf(first, second))
        whenever(fixture.runManager.selectedConfiguration).thenReturn(second)
        fixture.manager.syncExistingConfigurations()
        val expectedId = fixture.store.loadCurrent()!!.id

        fixture.manager.onRunConfigurationSelected(first)

        assertEquals(expectedId, fixture.store.loadCurrent()!!.id)
    }

    @Test
    fun `active variant reconciliation creates and selects matching jugg configuration`() {
        val fixture = fixture("active_variant", appVariant = "release", includePaid = false)
        val debug = juggSettings("app debug", ideaOptions("./gradlew :app:assembleDebug", "custom-debug.apk"))
        val releaseOptions = ideaOptions("", "")
        val release = juggSettings("app release", releaseOptions)
        val factory = debug.configuration.factory!!
        whenever(fixture.runManager.getConfigurationSettingsList(com.sickworm.intellij.jugg.ide.JuggConfigurationType::class.java)).thenReturn(listOf(debug))
        whenever(fixture.runManager.selectedConfiguration).thenReturn(debug)
        whenever(fixture.runManager.createConfiguration("app release", factory)).thenReturn(release)

        val configurations = fixture.manager.reconcileActiveBuildVariants()

        assertTrue(configurations.any { it.moduleName == "app" && it.variant == "release" })
        assertEquals("./gradlew :app:assembleRelease", releaseOptions.compileCommand)
        verify(fixture.runManager).addConfiguration(release)
        verify(fixture.runManager).selectedConfiguration = release
        assertEquals(releaseOptions.cliRunConfigurationId, fixture.store.loadCurrent()!!.id)
    }

    @Test
    fun `active variant reconciliation keeps selected custom command when it still encodes the active variant`() {
        val fixture = fixture("keep_custom_deploy", appVariant = "debug", includePaid = false)
        val assemble = juggSettings("jugg:app", ideaOptions("./gradlew :app:assembleDebug", "app/build/outputs/apk/debug/*.apk"))
        val deployOptions = ideaOptions("gradlew.bat :app:deployDebug --stacktrace", "app/build/deploy/*_arm64.apk")
        val deploy = juggSettings("jugg:musicApp", deployOptions)
        whenever(fixture.runManager.getConfigurationSettingsList(com.sickworm.intellij.jugg.ide.JuggConfigurationType::class.java))
            .thenReturn(listOf(assemble, deploy))
        whenever(fixture.runManager.selectedConfiguration).thenReturn(deploy)

        fixture.manager.reconcileActiveBuildVariants()

        verify(fixture.runManager, org.mockito.kotlin.never()).createConfiguration(
            org.mockito.kotlin.any<String>(),
            org.mockito.kotlin.any<ConfigurationFactory>(),
        )
        verify(fixture.runManager, org.mockito.kotlin.never()).selectedConfiguration = org.mockito.kotlin.any()
        assertEquals(deployOptions.cliRunConfigurationId, fixture.store.loadCurrent()!!.id)
        assertEquals("gradlew.bat :app:deployDebug --stacktrace", deployOptions.compileCommand)
    }

    @Test
    fun `active variant reconciliation keeps selected custom command when its suffix resembles another variant`() {
        val fixture = fixture("keep_custom_suffix", appVariant = "release", includePaid = false)
        val customOptions = ideaOptions("./gradlew :app:uploadDebug", "artifacts/custom.apk")
        val custom = juggSettings("custom debug", customOptions)
        val release = juggSettings("app release", ideaOptions("./gradlew :app:assembleRelease", "app/build/outputs/apk/release/*.apk"))
        whenever(fixture.runManager.getConfigurationSettingsList(com.sickworm.intellij.jugg.ide.JuggConfigurationType::class.java))
            .thenReturn(listOf(custom, release))
        whenever(fixture.runManager.selectedConfiguration).thenReturn(custom)

        fixture.manager.reconcileActiveBuildVariants()

        verify(fixture.runManager, org.mockito.kotlin.never()).selectedConfiguration = org.mockito.kotlin.any()
        assertEquals(customOptions.cliRunConfigurationId, fixture.store.loadCurrent()!!.id)
    }

    @Test
    fun `active variant reconciliation keeps selected assemble command with custom arguments`() {
        val fixture = fixture("keep_custom_arguments", appVariant = "release", includePaid = false)
        val customOptions = ideaOptions("./gradlew :app:assembleDebug --offline", "app/build/outputs/apk/debug/*.apk")
        val custom = juggSettings("custom debug", customOptions)
        val release = juggSettings("app release", ideaOptions("./gradlew :app:assembleRelease", "app/build/outputs/apk/release/*.apk"))
        whenever(fixture.runManager.getConfigurationSettingsList(com.sickworm.intellij.jugg.ide.JuggConfigurationType::class.java))
            .thenReturn(listOf(custom, release))
        whenever(fixture.runManager.selectedConfiguration).thenReturn(custom)

        fixture.manager.reconcileActiveBuildVariants()

        verify(fixture.runManager, org.mockito.kotlin.never()).selectedConfiguration = org.mockito.kotlin.any()
        assertEquals(customOptions.cliRunConfigurationId, fixture.store.loadCurrent()!!.id)
    }

    @Test
    fun `active variant reconciliation does not select a custom configuration as the active target`() {
        val fixture = fixture("keep_when_target_is_custom", appVariant = "release", includePaid = false)
        val debugOptions = ideaOptions("./gradlew :app:assembleDebug", "app/build/outputs/apk/debug/*.apk")
        val debug = juggSettings("app debug", debugOptions)
        val customRelease = juggSettings("custom release", ideaOptions("./gradlew :app:deployRelease", "artifacts/custom-release.apk"))
        whenever(fixture.runManager.getConfigurationSettingsList(com.sickworm.intellij.jugg.ide.JuggConfigurationType::class.java))
            .thenReturn(listOf(debug, customRelease))
        whenever(fixture.runManager.selectedConfiguration).thenReturn(debug)

        fixture.manager.reconcileActiveBuildVariants()

        verify(fixture.runManager, org.mockito.kotlin.never()).selectedConfiguration = org.mockito.kotlin.any()
        assertEquals(debugOptions.cliRunConfigurationId, fixture.store.loadCurrent()!!.id)
    }

    @Test
    fun `active variant reconciliation preserves an existing matching profile`() {
        val fixture = fixture("existing_active_variant", appVariant = "release", includePaid = false)
        val options = ideaOptions("./gradlew :app:assembleRelease", "custom-release.apk")
        options.environmentVariables = "CHANNEL=internal"
        val release = juggSettings("custom release", options)
        whenever(fixture.runManager.getConfigurationSettingsList(com.sickworm.intellij.jugg.ide.JuggConfigurationType::class.java)).thenReturn(listOf(release))
        whenever(fixture.runManager.selectedConfiguration).thenReturn(release)

        fixture.manager.reconcileActiveBuildVariants()

        assertEquals("custom-release.apk", options.outputApkName)
        assertEquals("CHANNEL=internal", options.environmentVariables)
        verify(fixture.runManager, org.mockito.kotlin.never()).createConfiguration(org.mockito.kotlin.any<String>(), org.mockito.kotlin.any<ConfigurationFactory>())
    }

    @Test
    fun `active variant reconciliation does not replace a non jugg selection`() {
        val fixture = fixture("non_jugg_selection", appVariant = "release", includePaid = false)
        val debug = juggSettings("app debug", ideaOptions("./gradlew :app:assembleDebug", "debug.apk"))
        val release = juggSettings("app release", ideaOptions("", ""))
        val other = mock<RunnerAndConfigurationSettings>()
        whenever(other.configuration).thenReturn(mock<RunConfiguration>())
        whenever(fixture.runManager.getConfigurationSettingsList(com.sickworm.intellij.jugg.ide.JuggConfigurationType::class.java)).thenReturn(listOf(debug))
        whenever(fixture.runManager.selectedConfiguration).thenReturn(other)
        whenever(fixture.runManager.createConfiguration("app release", debug.configuration.factory!!)).thenReturn(release)

        fixture.manager.reconcileActiveBuildVariants()

        verify(fixture.runManager).addConfiguration(release)
        verify(fixture.runManager, org.mockito.kotlin.never()).selectedConfiguration = org.mockito.kotlin.any()
        assertEquals(null, fixture.store.loadCurrent())
    }

    private fun fixture(name: String, appVariant: String = "debug", includePaid: Boolean = true): Fixture {
        val projectDir = temporaryFolder.newFolder(name)
        val pathManager = JuggPathManager(projectDir)
        val runManager = mock<RunManager>()
        val compileContextManager = mock<CompileContextManager>()
        whenever(compileContextManager.getProjectInfo()).thenReturn(projectInfo(projectDir, appVariant, includePaid))
        val store = CliRunConfigurationStore(pathManager)
        return Fixture(pathManager, runManager, store, IdeaCliRunConfigurationManager(runManager, compileContextManager, store))
    }

    private fun juggSettings(name: String, options: JuggRunConfigurationOptions): RunnerAndConfigurationSettings {
        val configuration = mock<JuggRunConfiguration>()
        whenever(configuration.state).thenReturn(options)
        whenever(configuration.factory).thenReturn(mock<ConfigurationFactory>())
        val settings = mock<RunnerAndConfigurationSettings>()
        whenever(settings.name).thenReturn(name)
        whenever(settings.configuration).thenReturn(configuration)
        return settings
    }

    private fun ideaOptions(command: String, output: String): JuggRunConfigurationOptions {
        return JuggRunConfigurationOptions().apply {
            compileCommand = command
            outputApkName = output
            remoteSshPassword = "secret-password"
        }
    }

    private fun compileOptions(pathManager: JuggPathManager, task: String, output: String): JuggGradleCompileOptions {
        return JuggGradleCompileOptions(
            projectRootPath = pathManager.projectDir.absolutePath,
            localClasspathStoragePath = pathManager.localClasspathStoragePathManager,
            initGradleFilePath = pathManager.initGradleFilePath.absolutePath,
            compileCommand = "./gradlew $task",
            outputApkName = output,
            isRemoteCompile = false,
            isSyncAllProjects = false,
            remoteSshUser = "",
            remoteSshPassword = "",
            remoteSshIp = "",
            remoteSshPort = 0,
            localToRemoteIftConfigName = "",
            localToRemoteSyncPath = "",
            remoteSyncPath = "",
            remoteToLocalIftConfigName = "",
            remoteToLocalSyncPath = "",
            httpProxyIp = "",
            httpProxyPort = 0,
            syncMode = SyncMode.IFT,
            environmentVariables = "",
            buildTarget = BuildTarget.APP,
        )
    }

    private fun projectInfo(projectDir: File, appVariant: String, includePaid: Boolean): JuggProjectInfo {
        val moduleVariants = listOf("app" to appVariant) + if (includePaid) listOf("paid" to "paidRelease") else emptyList()
        val modules = moduleVariants.associate { (name, variant) ->
            val moduleDir = File(projectDir, name)
            name to ModuleInfo.virtualModule.copy(
                name = name,
                moduleType = ModuleInfo.Type.Application,
                projectRootDir = projectDir,
                moduleRootDir = moduleDir,
                buildVariant = variant,
                variants = listOf(variant, "debug", "release").distinct().map { Variant(it, null) },
                buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir, variant, buildDirRelativePath = ""),
            )
        }
        return JuggProjectInfo(modules, agpR8Classpath = null)
    }

    private data class Fixture(
        val pathManager: JuggPathManager,
        val runManager: RunManager,
        val store: CliRunConfigurationStore,
        val manager: IdeaCliRunConfigurationManager,
    )
}
