package com.sickworm.intellij.jugg.mock

import com.android.tools.idea.gradle.dsl.api.GradleModelProvider
import com.android.tools.idea.gradle.dsl.model.GradleModelSource
import com.google.gson.JsonSyntaxException
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilder
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockApplication
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.ApplicationInfoImpl
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.ui.messages.MessagesService
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.registerExtension
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.logic.IdeaPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.mockito.Mockito
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

object TestGlobal {

    val logger = StdLogger("JuggTest")

    fun getLogger(): Logger = logger

    val projectInfo = try {
        PlatformApi.impl = IdeaPlatformApi()
        val projectInfoFromEnv = System.getenv("JUGG_PROJECT_INFO_PATH")
        val json = if (projectInfoFromEnv != null) {
            File(projectInfoFromEnv).readText()
        } else {
            ProjectInfo.DEMO_JSON
        }
        ProjectInfo.parseJson(json)
    } catch (e: JsonSyntaxException) {
        throw IllegalArgumentException("parse project info failed", e)
    }

    private val application = MockApplication {}

    private val appModuleDir = File(projectInfo.projectRoot, "app")

    val mockModule get() = ModuleInfo(
        name = "mock_module",
        moduleType = ModuleInfo.Type.Unknown,
        moduleRootDir = appModuleDir,
        projectRootDir = projectInfo.projectRoot,
        sourceDirs = listOf(File(appModuleDir, "src/main/java")),
        resourceDirs = listOf(File(appModuleDir, "src/main/res")),
        assetsDirs = listOf(File(appModuleDir, "src/main/assets")),
        manifestFile = File(appModuleDir, "src/main/AndroidManifest.xml"),
        manifestPlaceHolders = null,
        buildVariant = ModuleInfo.DEFAULT_BUILD_VARIANT,
        compileVersion = null,
        buildToolsVersion = null,
        buildPathInfo = ModuleBuildPathInfo(
            projectInfo.projectRoot,
            appModuleDir,
            ModuleInfo.DEFAULT_BUILD_VARIANT,
            buildDirRelativePath = "",
        ),
        kotlinJvmTarget = "1.8",
        kotlinFreeCompilerArgs = emptyList(),
        javaSourceCompatibility = "1.8",
        javaTargetCompatibility = "1.8",
        moduleDependencies = emptyList(),
        libraryDependencies = emptyList(),
        minSdkVersion = "21",
        runtimeLibraryDependencies = emptyList(),
        annotationProcessorDependencies = emptyList(),
        kaptDependencies = emptyList(),
    )

    init {
        PlatformApi.impl = IdeaPlatformApi()

        // avoid AsDeployerCompat init failed
        ApplicationManager.setApplication(application) {}
        application.registerService(ApplicationInfo::class.java, ApplicationInfoImpl.getShadowInstance())
        // avoid JuggSettings init failed
        application.registerService(PropertiesComponent::class.java, DummyPropertiesComponent())

        val projectJdkTable = Mockito.mock(ProjectJdkTable::class.java)
        Mockito.doReturn(arrayOf(MockAndroid30Sdk())).`when`(projectJdkTable).allJdks
        application.registerService(ProjectJdkTable::class.java, projectJdkTable)

        application.registerService(GradleModelProvider::class.java, GradleModelSource())
        application.registerService(MessagesService::class.java, Mockito.mock(MessagesService::class.java))
        application.registerService(TextConsoleBuilderFactory::class.java, MockTextConsoleBuilderFactory())

        val mockProgressManager = Mockito.mock(ProgressManager::class.java)
        Mockito.doAnswer {
            (it.arguments[0] as Task).run(Mockito.mock(ProgressIndicator::class.java))
        }.`when`(mockProgressManager).run(Mockito.any<Task>())
        application.registerService(ProgressManager::class.java, mockProgressManager)

        val extensionPoint = ExtensionPointName.create<ConfigurationType>("com.intellij.configurationType")
        application.extensionArea.registerExtensionPoint(extensionPoint,
            ConfigurationType::class.java.name, ExtensionPoint.Kind.INTERFACE, application)
        application.registerExtension(extensionPoint, JuggConfigurationType(), application)

        AsDeployerCompat.init(logger)

        // in tests, we often add change file without really change, so disable checksum check
        JuggSettings.isCheckChecksumWhenFileChanges = false
        JuggSettings.isEnableWarmUp = false
    }

    fun init() {
        // already do in init block
    }

}

private class MockTextConsoleBuilderFactory : TextConsoleBuilderFactory() {
    override fun createBuilder(project: Project): TextConsoleBuilder {
        return MockTextConsoleBuilder()
    }

    override fun createBuilder(project: Project, scope: GlobalSearchScope): TextConsoleBuilder {
        return MockTextConsoleBuilder()
    }
}

private class MockTextConsoleBuilder : TextConsoleBuilder() {
    override fun getConsole(): ConsoleView {
        return MockConsoleView()
    }

    override fun addFilter(filter: Filter) {
    }

    override fun setViewer(isViewer: Boolean) {
    }
}

private class MockConsoleView : ConsoleView {
    private val component = JPanel()

    override fun print(text: String, contentType: ConsoleViewContentType) {
    }

    override fun clear() {
    }

    override fun scrollTo(offset: Int) {
    }

    override fun attachToProcess(processHandler: ProcessHandler) {
    }

    override fun setOutputPaused(value: Boolean) {
    }

    override fun isOutputPaused(): Boolean = false

    override fun hasDeferredOutput(): Boolean = false

    override fun performWhenNoDeferredOutput(runnable: Runnable) {
        runnable.run()
    }

    override fun setHelpId(helpId: String) {
    }

    override fun addMessageFilter(filter: Filter) {
    }

    override fun printHyperlink(hyperlinkText: String, info: HyperlinkInfo?) {
    }

    override fun getContentSize(): Int = 0

    override fun canPause(): Boolean = false

    override fun createConsoleActions(): Array<AnAction> = emptyArray()

    override fun allowHeavyFilters() {
    }

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusableComponent(): JComponent = component

    override fun dispose() {
    }
}
