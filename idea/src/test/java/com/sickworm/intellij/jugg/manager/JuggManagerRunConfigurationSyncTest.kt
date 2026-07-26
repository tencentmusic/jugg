package com.sickworm.intellij.jugg.manager

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.mock.MockProject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IdeVersion
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.SuggestRunConfiguration
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggRunConfiguration
import com.sickworm.intellij.jugg.ide.SyncEvent
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.IFileChangesDetector
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import com.sickworm.intellij.jugg.project.CustomConfigManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.ide.logic.IdeSyncProblemResolver
import com.sickworm.intellij.jugg.server.JuggHotUpdateDownloader
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertSame

class JuggManagerRunConfigurationSyncTest {

    private val priorityImplField = AsDeployerCompat::class.java.getDeclaredField("priorityImpl").apply {
        isAccessible = true
    }
    private var originalPriorityImpl: Any? = null

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
        }
    }

    @After
    fun tearDown() {
        originalPriorityImpl?.let { priorityImplField.set(null, it) }
    }

    @Test
    fun sync_firstGeneratedConfiguration_usesLegacyModuleName() {
        val fixture = createFixture()
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleDevDebug",
                    outputApkPath = "app/build/outputs/apk/dev/debug/*.apk",
                    variantName = "devDebug",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(listOf("jugg:app"), fixture.settings.map { it.name })
        assertSame(fixture.settings.single(), fixture.selectedConfiguration)
    }

    @Test
    fun sync_selectedNonJuggConfiguration_createsWithoutChangingSelection() {
        val fixture = createFixture()
        fixture.addConfiguration(
            name = "jugg:app",
            compileCommand = "./gradlew :app:assembleDevDebug",
            outputApkName = "app/build/outputs/apk/dev/debug/*.apk",
        )
        val androidConfiguration = mock<RunnerAndConfigurationSettings>()
        fixture.selectedConfiguration = androidConfiguration
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleRelease",
                    outputApkPath = "app/build/outputs/apk/release/*.apk",
                    variantName = "release",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(listOf("jugg:app", "jugg:app:release"), fixture.settings.map { it.name })
        assertSame(androidConfiguration, fixture.selectedConfiguration)
    }

    @Test
    fun sync_activeBuildVariantChanged_createsAndSelectsVariantConfiguration() {
        val fixture = createFixture()
        val debug = fixture.addConfiguration(
            name = "jugg:app",
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        fixture.selectedConfiguration = debug
        whenever(fixture.deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.APP, 1L),
        )
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleRelease",
                    outputApkPath = "app/build/outputs/apk/release/*.apk",
                    variantName = "release",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(2, fixture.settings.size)
        assertEquals("jugg:app:release", fixture.settings.last().name)
        assertEquals("./gradlew :app:assembleRelease", fixture.selectedConfiguration?.compileCommand())
    }

    @Test
    fun sync_activeBuildVariantUnchanged_keepsSelectedConfiguration() {
        val fixture = createFixture()
        val debug = fixture.addConfiguration(
            name = "jugg:app",
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        fixture.selectedConfiguration = debug
        whenever(fixture.deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.APP, 1L),
        )
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleDebug",
                    outputApkPath = "app/build/outputs/apk/debug/*.apk",
                    variantName = "debug",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(1, fixture.settings.size)
        assertSame(debug, fixture.selectedConfiguration)
    }

    @Test
    fun sync_activeBuildVariantUnchanged_preservesCustomGradleArguments() {
        val fixture = createFixture()
        val debug = fixture.addConfiguration(
            name = "jugg:app:debug",
            compileCommand = "./gradlew :app:assembleDebug --offline -Pchannel=dev",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        fixture.selectedConfiguration = debug
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleDebug",
                    outputApkPath = "app/build/outputs/apk/debug/*.apk",
                    variantName = "debug",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(1, fixture.settings.size)
        assertSame(debug, fixture.selectedConfiguration)
        assertEquals("./gradlew :app:assembleDebug --offline -Pchannel=dev", debug.compileCommand())
    }

    @Test
    fun sync_customGradleTaskWithArguments_preservesConfiguration() {
        val fixture = createFixture()
        val debug = fixture.addConfiguration(
            name = "jugg:app:debug",
            compileCommand = "./gradlew :app:packageDebug --offline",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        fixture.selectedConfiguration = debug
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:packageDebug",
                    outputApkPath = "app/build/outputs/apk/debug/*.apk",
                    variantName = "debug",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(1, fixture.settings.size)
        assertSame(debug, fixture.selectedConfiguration)
        assertEquals("./gradlew :app:packageDebug --offline", debug.compileCommand())
    }

    @Test
    fun sync_existingConfiguration_preservesGeneratedStyleOutputApkPath() {
        val fixture = createFixture()
        val debug = fixture.addConfiguration(
            name = "jugg:app:debug",
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleDebug",
                    outputApkPath = "build/app/outputs/apk/debug/*.apk",
                    variantName = "debug",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals("app/build/outputs/apk/debug/*.apk", debug.outputApkName())
    }

    @Test
    fun sync_existingManualConfiguration_preservesOutputApkPath() {
        val fixture = createFixture()
        val debug = fixture.addConfiguration(
            name = "jugg:app:debug",
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "artifacts/custom-debug.apk",
        )
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleDebug",
                    outputApkPath = "build/app/outputs/apk/debug/*.apk",
                    variantName = "debug",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals("artifacts/custom-debug.apk", debug.outputApkName())
    }

    @Test
    fun sync_activeBuildVariantChanged_selectsExistingVariantConfiguration() {
        val fixture = createFixture()
        val release = fixture.addConfiguration(
            name = "jugg:app:release",
            compileCommand = "./gradlew :app:assembleRelease",
            outputApkName = "app/build/outputs/apk/release/*.apk",
        )
        val debug = fixture.addConfiguration(
            name = "jugg:app:debug",
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        fixture.selectedConfiguration = release
        whenever(fixture.deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew :app:assembleRelease", BuildTarget.APP, 1L),
        )
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleDebug",
                    outputApkPath = "app/build/outputs/apk/debug/*.apk",
                    variantName = "debug",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(2, fixture.settings.size)
        assertSame(debug, fixture.selectedConfiguration)
    }

    @Test
    fun sync_activeBuildVariantChanged_reusesExistingConfigurationWithCustomGradleArguments() {
        val fixture = createFixture()
        val release = fixture.addConfiguration(
            name = "jugg:app:release",
            compileCommand = "./gradlew :app:assembleRelease",
            outputApkName = "app/build/outputs/apk/release/*.apk",
        )
        val debug = fixture.addConfiguration(
            name = "jugg:app:debug",
            compileCommand = "./gradlew :app:assembleDebug --offline",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        fixture.selectedConfiguration = release
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleDebug",
                    outputApkPath = "app/build/outputs/apk/debug/*.apk",
                    variantName = "debug",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(2, fixture.settings.size)
        assertSame(debug, fixture.selectedConfiguration)
        assertEquals("./gradlew :app:assembleDebug --offline", debug.compileCommand())
    }

    @Test
    fun sync_variantSwitch_reusesExistingCustomGradleTaskConfiguration() {
        val fixture = createFixture()
        val debug = fixture.addConfiguration(
            name = "jugg:app:debug",
            compileCommand = "./gradlew :app:packageDebug",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        val release = fixture.addConfiguration(
            name = "jugg:app:release",
            compileCommand = "./gradlew :app:packageRelease --offline",
            outputApkName = "app/build/outputs/apk/release/*.apk",
        )
        fixture.selectedConfiguration = debug
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:packageRelease",
                    outputApkPath = "app/build/outputs/apk/release/*.apk",
                    variantName = "release",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(2, fixture.settings.size)
        assertSame(release, fixture.selectedConfiguration)
        assertEquals("./gradlew :app:packageRelease --offline", release.compileCommand())
    }

    @Test
    fun sync_switchingBackToPersistedVariant_selectsExistingConfiguration() {
        val fixture = createFixture()
        val prodDebug = fixture.addConfiguration(
            name = "jugg:app:prodDebug",
            compileCommand = "./gradlew :app:assembleProdDebug",
            outputApkName = "app/build/outputs/apk/prod/debug/*.apk",
        )
        val devStaging = fixture.addConfiguration(
            name = "jugg:app:devStaging",
            compileCommand = "./gradlew :app:assembleDevStaging",
            outputApkName = "app/build/outputs/apk/dev/staging/*.apk",
        )
        fixture.selectedConfiguration = prodDebug
        whenever(fixture.deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew :app:assembleProdDebug", BuildTarget.APP, 1L),
        )
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any()))
            .thenReturn(
                listOf(
                    SuggestRunConfiguration(
                        moduleName = "app",
                        compileCommand = "./gradlew :app:assembleDevStaging",
                        outputApkPath = "app/build/outputs/apk/dev/staging/*.apk",
                        variantName = "devStaging",
                    ),
                ),
            )
            .thenReturn(
                listOf(
                    SuggestRunConfiguration(
                        moduleName = "app",
                        compileCommand = "./gradlew :app:assembleProdDebug",
                        outputApkPath = "app/build/outputs/apk/prod/debug/*.apk",
                        variantName = "prodDebug",
                    ),
                ),
            )

        fixture.invokeSync()
        fixture.invokeSync()

        assertSame(prodDebug, fixture.selectedConfiguration)
        assertEquals(2, fixture.settings.size)
        assertSame(devStaging, fixture.settings.last())
    }

    @Test
    fun sync_selectedJuggConfigurationWithoutFullBuildHistory_switchesConfiguration() {
        val fixture = createFixture()
        val debug = fixture.addConfiguration(
            name = "jugg:app:debug",
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        fixture.selectedConfiguration = debug
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleRelease",
                    outputApkPath = "app/build/outputs/apk/release/*.apk",
                    variantName = "release",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(2, fixture.settings.size)
        assertEquals("./gradlew :app:assembleRelease", fixture.selectedConfiguration?.compileCommand())
    }

    @Test
    fun sync_multiTaskSuggestion_preservesSelectedConfiguration() {
        val fixture = createFixture()
        val debug = fixture.addConfiguration(
            name = "jugg:app:debug",
            compileCommand = "./gradlew :app:packageDebug --offline",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        fixture.selectedConfiguration = debug
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew clean :app:packageRelease",
                    outputApkPath = "app/build/outputs/apk/release/*.apk",
                    variantName = "release",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(2, fixture.settings.size)
        assertSame(debug, fixture.selectedConfiguration)
    }

    @Test
    fun skippedSync_activeBuildVariantChanged_selectsExistingVariantConfiguration() {
        val fixture = createFixture()
        val release = fixture.addConfiguration(
            name = "jugg:app:release",
            compileCommand = "./gradlew :app:assembleRelease",
            outputApkName = "app/build/outputs/apk/release/*.apk",
        )
        val debug = fixture.addConfiguration(
            name = "jugg:app:debug",
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        fixture.selectedConfiguration = release
        whenever(fixture.deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew :app:assembleRelease", BuildTarget.APP, 1L),
        )
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleDebug",
                    outputApkPath = "app/build/outputs/apk/debug/*.apk",
                    variantName = "debug",
                ),
            ),
        )

        fixture.manager.onSyncEvent(SyncEvent.SKIPPED)

        assertSame(debug, fixture.selectedConfiguration)
    }

    @Test
    fun sync_duplicateCompileCommands_createsSingleConfiguration() {
        val fixture = createFixture()
        val debug = fixture.addConfiguration(
            name = "jugg:app:debug",
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        fixture.selectedConfiguration = debug
        whenever(fixture.deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.APP, 1L),
        )
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleRelease",
                    outputApkPath = "app/build/outputs/apk/release/*.apk",
                    variantName = "release",
                ),
                SuggestRunConfiguration(
                    moduleName = "app",
                    compileCommand = "./gradlew :app:assembleRelease",
                    outputApkPath = "SMCommon/app/build/outputs/apk/release/*.apk",
                    variantName = "release",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals(2, fixture.settings.size)
        assertEquals("app/build/outputs/apk/release/*.apk", fixture.selectedConfiguration?.outputApkName())
    }

    @Test
    fun suggestRunConfiguration_compositeBuildUsesUniqueGradleModulePath() {
        val rootModule = SuggestRunConfiguration.resolveModuleName(
            ideModuleName = "JuggDuplicateAppModules.app",
            rootProjectName = "JuggDuplicateAppModules",
        )
        val includedModule = SuggestRunConfiguration.resolveModuleName(
            ideModuleName = "SMCommon.app",
            rootProjectName = "JuggDuplicateAppModules",
        )
        val newIdeRootModule = SuggestRunConfiguration.resolveModuleName(
            ideModuleName = "JuggDuplicateAppModules.app.main",
            rootProjectName = "JuggDuplicateAppModules",
        )
        val newIdeIncludedModule = SuggestRunConfiguration.resolveModuleName(
            ideModuleName = "SMCommon.app.main",
            rootProjectName = "JuggDuplicateAppModules",
        )
        val sameNamedRootModule = SuggestRunConfiguration.resolveModuleName(
            ideModuleName = "app.main",
            rootProjectName = "app",
        )

        assertEquals("app", rootModule)
        assertEquals("SMCommon.app", includedModule)
        assertEquals("app", newIdeRootModule)
        assertEquals("SMCommon.app", newIdeIncludedModule)
        assertEquals("app", sameNamedRootModule)
        assertEquals(
            "./gradlew :app:assembleDevDebug",
            SuggestRunConfiguration.createCompileCommand(rootModule, "assembleDevDebug"),
        )
        assertEquals(
            "./gradlew :SMCommon:app:assembleDevDebug",
            SuggestRunConfiguration.createCompileCommand(includedModule, "assembleDevDebug"),
        )
    }

    @Test
    fun suggestRunConfiguration_gradleIdentityIgnoresIdeSourceSetSuffix() {
        assertEquals(
            "app",
            SuggestRunConfiguration.resolveGradleModuleName(
                gradleProjectPath = ":app",
                gradleBuildRoot = "/repo",
                projectDir = File("/repo"),
                externalProjectId = "Root:app:debug",
            ),
        )
        assertEquals(
            "SMCommon.app",
            SuggestRunConfiguration.resolveGradleModuleName(
                gradleProjectPath = ":app",
                gradleBuildRoot = "/repo/SMCommon",
                projectDir = File("/repo"),
                externalProjectId = "SMCommon:app:main~",
            ),
        )
    }

    @Test
    fun suggestRunConfiguration_gradleIdentityFailureFallsBackToLegacyModuleName() {
        val module = mock<Module>()
        val project = mock<Project>()
        whenever(module.name).thenReturn("Root.app.debug")
        whenever(project.name).thenReturn("Root")
        whenever(project.basePath).thenReturn("/repo")

        assertEquals("app.debug", SuggestRunConfiguration.resolveModuleName(module, project))
    }

    @Test
    fun sync_newIdeMainSourceSet_selectsNormalizedVariantConfiguration() {
        val fixture = createFixture()
        val debug = fixture.addConfiguration(
            name = "jugg:app",
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
        )
        fixture.selectedConfiguration = debug
        whenever(fixture.deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.APP, 1L),
        )
        val moduleName = SuggestRunConfiguration.resolveModuleName(
            ideModuleName = "JuggDuplicateAppModules.app.main",
            rootProjectName = "JuggDuplicateAppModules",
        )
        val compileCommand = SuggestRunConfiguration.createCompileCommand(moduleName, "assembleDevDebug")
        whenever(fixture.asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any())).thenReturn(
            listOf(
                SuggestRunConfiguration(
                    moduleName = moduleName,
                    compileCommand = compileCommand,
                    outputApkPath = "app/build/outputs/apk/dev/debug/*.apk",
                    variantName = "devDebug",
                ),
            ),
        )

        fixture.invokeSync()

        assertEquals("app", moduleName)
        assertEquals("./gradlew :app:assembleDevDebug", compileCommand)
        assertEquals("jugg:app:devDebug", fixture.selectedConfiguration?.name)
    }

    private fun createFixture(): Fixture {
        val runManager = mock<RunManager>()
        val project = object : MockProject(null, {}) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getService(serviceClass: Class<T>): T? {
                if (serviceClass == RunManager::class.java) {
                    return runManager as T
                }
                return super.getService(serviceClass)
            }

            override fun getBasePath(): String = "/tmp/jugg-run-configuration-sync-test"
        }
        val settings = mutableListOf<RunnerAndConfigurationSettings>()
        var selectedConfiguration: RunnerAndConfigurationSettings? = null
        whenever(runManager.getConfigurationSettingsList(JuggConfigurationType::class.java))
            .thenAnswer { settings.toList() }
        whenever(runManager.createConfiguration(any<String>(), any<ConfigurationFactory>())).thenAnswer { invocation ->
            createSettings(
                project = project,
                name = invocation.getArgument(0),
                factory = invocation.getArgument(1),
            )
        }
        doAnswer { invocation ->
            settings.add(invocation.getArgument(0))
            null
        }.whenever(runManager).addConfiguration(any())
        whenever(runManager.selectedConfiguration).thenAnswer { selectedConfiguration }
        doAnswer { invocation ->
            selectedConfiguration = invocation.getArgument(0)
            null
        }.whenever(runManager).selectedConfiguration = any()

        val pathManager = mock<JuggPathManager>()
        whenever(pathManager.projectDir).thenReturn(File(project.basePath))
        val deployHistoryManager = mock<IDeployHistoryManager>()
        val asDeployerCompat = mock<IAsDeployerCompat>()
        replaceAsDeployerCompat(asDeployerCompat)
        val manager = JuggManager(
            project = project,
            pathManager = pathManager,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            logger = mock<Logger>(),
            juggServer = mock<JuggServer>(),
            juggHotUpdateDownloader = mock<JuggHotUpdateDownloader>(),
            fileChangesHandler = mock<IFileChangesHandler>(),
            fileChangesDetector = mock<IFileChangesDetector>(),
            deployHistoryManager = deployHistoryManager,
            deployTargetManager = mock<IDeployTargetManager>(),
            deployStateManager = mock<DeployStateManager>(),
            taskRunnerManager = mock<TaskRunnerManager>(),
            customCompilerManager = mock<CustomCompilerManager>(),
            deployFileManager = mock<DeployFileManager>(),
            compileContextManager = mock<CompileContextManager>(),
            juggRunningTaskStatusManager = mock<IJuggRunningTaskStatusManager>(),
            dependencyChangeManager = mock<IDependencyChangeManager>(),
            gradleProjectInfoLocalFetchManager = mock<GradleProjectInfoLocalFetchManager>(),
            gitFileChangesDetector = mock<GitFileChangesDetector>(),
            juggDeployerHelper = mock<JuggDeployerHelper>(),
            juggCompilerHelper = mock<JuggCompilerHelper>(),
            customConfigManager = mock<CustomConfigManager>(),
            ideSyncProblemResolver = mock<IdeSyncProblemResolver>(),
        )
        return Fixture(
            project = project,
            manager = manager,
            settings = settings,
            deployHistoryManager = deployHistoryManager,
            asDeployerCompat = asDeployerCompat,
            getSelectedConfiguration = { selectedConfiguration },
            setSelectedConfiguration = { selectedConfiguration = it },
        )
    }

    private fun createSettings(
        project: Project,
        name: String,
        factory: ConfigurationFactory = JuggConfigurationType.getInstance().configurationFactories[0],
    ): RunnerAndConfigurationSettings {
        val configuration = JuggRunConfiguration(project, factory, name)
        return mock {
            whenever(it.name).thenReturn(name)
            whenever(it.configuration).thenReturn(configuration)
            whenever(it.type).thenReturn(JuggConfigurationType.getInstance())
        }
    }

    private inner class Fixture(
        val project: Project,
        val manager: JuggManager,
        val settings: MutableList<RunnerAndConfigurationSettings>,
        val deployHistoryManager: IDeployHistoryManager,
        val asDeployerCompat: IAsDeployerCompat,
        private val getSelectedConfiguration: () -> RunnerAndConfigurationSettings?,
        private val setSelectedConfiguration: (RunnerAndConfigurationSettings?) -> Unit,
    ) {
        var selectedConfiguration: RunnerAndConfigurationSettings?
            get() = getSelectedConfiguration()
            set(value) = setSelectedConfiguration(value)

        fun addConfiguration(name: String, compileCommand: String, outputApkName: String): RunnerAndConfigurationSettings {
            val setting = createSettings(project, name)
            (setting.configuration as JuggRunConfiguration).state!!.apply {
                this.compileCommand = compileCommand
                this.outputApkName = outputApkName
            }
            settings.add(setting)
            return setting
        }

        fun invokeSync() {
            val method = JuggManager::class.java.getDeclaredMethod(
                "tryCreateRunConfigurations",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(manager, true, 0)
        }
    }

    private fun replaceAsDeployerCompat(asDeployerCompat: IAsDeployerCompat) {
        originalPriorityImpl = priorityImplField.get(null)
        val compatImpl = Class.forName("com.sickworm.intellij.jugg.deploy.run.CompatImpl")
            .getDeclaredConstructor(IdeVersion::class.java, Lazy::class.java)
            .apply { isAccessible = true }
            .newInstance(AsDeployerCompat.ideVersion, lazyOf(asDeployerCompat))
        priorityImplField.set(null, compatImpl)
    }

    private fun RunnerAndConfigurationSettings.compileCommand(): String? {
        return (configuration as JuggRunConfiguration).state?.compileCommand
    }

    private fun RunnerAndConfigurationSettings.outputApkName(): String? {
        return (configuration as JuggRunConfiguration).state?.outputApkName
    }
}
