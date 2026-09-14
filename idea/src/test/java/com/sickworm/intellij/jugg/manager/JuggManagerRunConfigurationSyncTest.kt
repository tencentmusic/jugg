package com.sickworm.intellij.jugg.manager

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.mock.MockProject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.compiler.context.IdeaCompileEnvironmentSource
import com.sickworm.intellij.jugg.compiler.context.IdeaProjectModelSource
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IdeVersion
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.deploy.run.SuggestRunConfiguration
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggControlPanelHost
import com.sickworm.intellij.jugg.ide.JuggRunConfiguration
import com.sickworm.intellij.jugg.ide.SyncEvent
import com.sickworm.intellij.jugg.ide.logic.IdeSyncProblemResolver
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.change.FileChangeManager
import com.sickworm.intellij.jugg.project.change.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.change.IFileChangeMonitor
import com.sickworm.intellij.jugg.project.change.IFileChangesHandler
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.ProjectCustomConfigManager
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.IdeaHotUpdateCoordinator
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import javax.swing.SwingUtilities

/**
 * Verifies the Jugg lifecycle entry points create and recover IDEA run configurations from Sync.
 */
class JuggManagerRunConfigurationSyncTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
        }
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val priorityImplField = AsDeployerCompat::class.java.getDeclaredField("priorityImpl").apply {
        isAccessible = true
    }
    private var originalPriorityImpl: Any? = null

    @After
    fun tearDown() {
        originalPriorityImpl?.let { priorityImplField.set(null, it) }
    }

    @Test
    fun `sync creates the suggestion configuration and makes the jugg tool window available`() {
        val fixture = createFixture()
        fixture.suggestions += suggestion("app", "debug")

        fixture.manager.onSyncEvent(SyncEvent.SUCCEEDED)
        SwingUtilities.invokeAndWait {}

        verify(fixture.toolWindow).setAvailable(true)
        assertEquals(1, fixture.settings.size)
        assertEquals("./gradlew :app:assembleDebug", fixture.settings.single().compileCommand())
        assertEquals("app debug", fixture.settings.single().name)
    }

    @Test
    fun `sync without suggestions falls back to a single project info configuration`() {
        val fixture = createFixture()

        fixture.manager.onSyncEvent(SyncEvent.SUCCEEDED)
        SwingUtilities.invokeAndWait {}

        verify(fixture.toolWindow).setAvailable(true)
        assertEquals(1, fixture.settings.size)
        assertEquals("./gradlew :app:assembleDebug", fixture.settings.single().compileCommand())
    }

    @Test
    fun `sync retry reads fresh suggestions and creates the configuration once`() {
        val fixture = createFixture(ModuleInfo.Type.Unknown)
        fixture.suggestions.clear()

        fixture.manager.onSyncEvent(SyncEvent.SUCCEEDED)

        assertTrue(fixture.settings.isEmpty())
        fixture.suggestions += suggestion("app", "release")
        fixture.scheduler.advanceTimeBy(2_001L)
        fixture.scheduler.runCurrent()

        assertEquals(1, fixture.settings.size)
        assertEquals("./gradlew :app:assembleRelease", fixture.settings.single().compileCommand())
        SwingUtilities.invokeAndWait {}
        verify(fixture.toolWindow).setAvailable(true)
    }

    @Test
    fun `repeated sync does not duplicate the suggestion configuration`() {
        val fixture = createFixture()
        fixture.suggestions += suggestion("app", "debug")

        fixture.manager.onSyncEvent(SyncEvent.SUCCEEDED)
        fixture.manager.onSyncEvent(SyncEvent.SUCCEEDED)

        assertEquals(1, fixture.settings.size)
    }

    private fun createFixture(moduleType: ModuleInfo.Type = ModuleInfo.Type.Application): Fixture {
        val projectDir = temporaryFolder.newFolder("sync_project")
        val runManager = mock<RunManager>()
        val settings = mutableListOf<RunnerAndConfigurationSettings>()
        var selectedConfiguration: RunnerAndConfigurationSettings? = null
        val templates = mutableMapOf<ConfigurationFactory, com.intellij.execution.configurations.RunConfiguration>()
        val toolWindow = mock<ToolWindow>()
        val toolWindowManager = mock<ToolWindowManager>()
        whenever(toolWindowManager.getToolWindow(JuggControlPanelHost.TOOL_WINDOW_ID)).thenReturn(toolWindow)
        val project = object : MockProject(null, {}) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getService(serviceClass: Class<T>): T? {
                if (serviceClass == RunManager::class.java) {
                    return runManager as T
                }
                if (serviceClass == ToolWindowManager::class.java) {
                    return toolWindowManager as T
                }
                return super.getService(serviceClass)
            }

            override fun getBasePath(): String = projectDir.absolutePath
        }
        whenever(runManager.getConfigurationSettingsList(JuggConfigurationType::class.java))
            .thenAnswer { settings.toList() }
        whenever(runManager.createConfiguration(any<String>(), any<ConfigurationFactory>())).thenAnswer { invocation ->
            val name = invocation.getArgument<String>(0)
            val factory = invocation.getArgument<ConfigurationFactory>(1)
            val template = templates.getOrPut(factory) { factory.createTemplateConfiguration(project) }
            wrapSettings(factory.createConfiguration(name, template) as JuggRunConfiguration)
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

        val taskRunnerManager = mock<TaskRunnerManager>()
        doAnswer { invocation -> (invocation.arguments[1] as () -> Any?).invoke() }
            .whenever(taskRunnerManager).runProjectWriteLocked<Any?>(any<String>(), any())
        doAnswer { (it.arguments[1] as Runnable).run() }
            .whenever(taskRunnerManager).runTaskSafe(any(), any(), any(), any(), any())

        val compileContextManager = mock<CompileContextManager>()
        whenever(compileContextManager.getProjectInfo()).thenReturn(projectInfo(projectDir, moduleType))
        val pathManager = JuggPathManager(projectDir)
        pathManager.projectInfosDir.mkdirs()
        pathManager.gradleProjectInfoFile.createNewFile()
        val dependencyChangeManager = mock<IDependencyChangeManager>()
        val gradleProjectInfoLocalFetchManager = GradleProjectInfoLocalFetchManager(
            pathManager,
            compileContextManager,
            taskRunnerManager,
            dependencyChangeManager,
            mock<IDeployHistoryManager>(),
            mock<IdeaCompileEnvironmentSource>(),
            mock<Logger>(),
        )
        val suggestions = mutableListOf<SuggestRunConfiguration>()
        val asDeployerCompat = mock<IAsDeployerCompat>()
        whenever(asDeployerCompat.getSuggestRunConfigurations(any(), any(), any(), any()))
            .thenAnswer { suggestions.toList() }
        originalPriorityImpl = priorityImplField.get(null)
        priorityImplField.set(
            null,
            Class.forName("com.sickworm.intellij.jugg.deploy.run.CompatImpl")
                .getDeclaredConstructor(IdeVersion::class.java, Lazy::class.java)
                .apply { isAccessible = true }
                .newInstance(AsDeployerCompat.ideVersion, lazyOf(asDeployerCompat)),
        )

        val scheduler = TestCoroutineScheduler()
        val manager = JuggManager(
            project = project,
            pathManager = pathManager,
            coroutineScope = CoroutineScope(StandardTestDispatcher(scheduler)),
            logger = mock<Logger>(),
            runtimeInfo = RuntimeInfo("idea", "test", "test", "0"),
            juggServer = mock<JuggServer>(),
            ideaHotUpdateCoordinator = mock<IdeaHotUpdateCoordinator>(),
            fileChangesHandler = mock<IFileChangesHandler>(),
            fileChangesDetector = mock<IFileChangeMonitor>(),
            deployHistoryManager = mock<IDeployHistoryManager>(),
            deployTargetManager = mock<IDeployTargetManager>(),
            deployStateManager = mock<DeployStateManager>(),
            taskRunnerManager = taskRunnerManager,
            deploymentService = mock<JuggDeploymentService>(),
            customCompilerManager = mock<CustomCompilerManager>(),
            deployFileManager = mock<DeployFileManager>(),
            compileEnvironmentSource = mock<IdeaCompileEnvironmentSource>(),
            projectModelSource = mock<IdeaProjectModelSource>(),
            compileContextManager = compileContextManager,
            juggRunningTaskStatusManager = mock<IJuggRunningTaskStatusManager>(),
            dependencyChangeManager = dependencyChangeManager,
            gradleProjectInfoLocalFetchManager = gradleProjectInfoLocalFetchManager,
            gitFileChangesDetector = mock<GitFileChangesDetector>(),
            fileChangeManager = mock<FileChangeManager>(),
            juggDeployerHelper = mock<JuggDeployerHelper>(),
            juggCompilerHelper = mock<JuggCompilerHelper>(),
            projectCustomConfigManager = mock<ProjectCustomConfigManager>(),
            ideSyncProblemResolver = mock<IdeSyncProblemResolver>(),
            runManager = runManager,
        )
        return Fixture(manager, settings, suggestions, scheduler, toolWindow)
    }

    private fun wrapSettings(configuration: JuggRunConfiguration): RunnerAndConfigurationSettings {
        val settings = mock<RunnerAndConfigurationSettings>()
        whenever(settings.name).thenReturn(configuration.name)
        whenever(settings.configuration).thenReturn(configuration)
        return settings
    }

    private fun projectInfo(projectDir: File, moduleType: ModuleInfo.Type): JuggProjectInfo {
        val moduleDir = File(projectDir, "app")
        val app = ModuleInfo.virtualModule.copy(
            name = "app",
            moduleType = moduleType,
            projectRootDir = projectDir,
            moduleRootDir = moduleDir,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir, "debug", buildDirRelativePath = ""),
        )
        return JuggProjectInfo(mapOf(app.name to app), agpR8Classpath = null)
    }

    private fun suggestion(moduleName: String, variant: String): SuggestRunConfiguration {
        val variantTask = variant.replaceFirstChar { it.uppercaseChar() }
        return SuggestRunConfiguration(
            moduleName = moduleName,
            compileCommand = "./gradlew :$moduleName:assemble$variantTask",
            outputApkPath = "$moduleName/build/outputs/apk/$variant/*.apk",
            variantName = variant,
        )
    }

    private fun RunnerAndConfigurationSettings.compileCommand(): String? {
        return (configuration as? JuggRunConfiguration)?.state?.compileCommand
    }

    private data class Fixture(
        val manager: JuggManager,
        val settings: MutableList<RunnerAndConfigurationSettings>,
        val suggestions: MutableList<SuggestRunConfiguration>,
        val scheduler: TestCoroutineScheduler,
        val toolWindow: ToolWindow,
    )
}
