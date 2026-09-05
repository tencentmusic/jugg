package com.sickworm.intellij.jugg.gradle.script

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.plugins.PluginContainer
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.StartParameter
import org.gradle.TaskExecutionRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class GradleProjectInfoReaderKotlinOptionsTest {

    @Test
    fun `built-in Kotlin compiler options populate project info without legacy plugin`() {
        val jvmTarget = mock<Provider<JvmTargetValue>>()
        whenever(jvmTarget.orNull).thenReturn(JvmTargetValue("11"))
        val freeCompilerArgs = mock<Provider<List<String>>>()
        whenever(freeCompilerArgs.orNull).thenReturn(listOf("-Xexpect-actual-classes"))
        val compilerOptions = CompilerOptions(jvmTarget, freeCompilerArgs)
        val kotlinTask = kotlinTask(compilerOptions)

        val module = createReader(
            kotlinTask = kotlinTask,
            kotlinTaskName = "compileDebugKotlin",
            hasLegacyKotlinPlugin = false,
        )
            .getProjectInfo(false).modules.getValue("app")

        assertEquals("11", module.kotlinJvmTarget)
        assertEquals(listOf("-Xexpect-actual-classes"), module.kotlinFreeCompilerArgs)
    }

    @Test
    fun `AGP 9 KMP Android compiler options populate project info`() {
        val jvmTarget = mock<Provider<JvmTargetValue>>()
        whenever(jvmTarget.orNull).thenReturn(JvmTargetValue("17"))
        val freeCompilerArgs = mock<Provider<List<String>>>()
        whenever(freeCompilerArgs.orNull).thenReturn(emptyList())

        val module = createReader(
            kotlinTask = kotlinTask(CompilerOptions(jvmTarget, freeCompilerArgs)),
            kotlinTaskName = "compileAndroidMain",
            hasLegacyKotlinPlugin = true,
            isAndroidApplication = false,
            isAndroidKmp = true,
        ).getProjectInfo(false).modules.getValue("app")

        assertEquals("17", module.kotlinJvmTarget)
    }

    @Test
    fun `legacy Kotlin options remain supported`() {
        val kotlinTask = kotlinTask(legacyOptions = LegacyKotlinOptions("1.8", listOf("-Xlegacy")))

        val module = createReader(
            kotlinTask = kotlinTask,
            kotlinTaskName = "compileDebugKotlinAndroid",
            hasLegacyKotlinPlugin = true,
        )
            .getProjectInfo(false).modules.getValue("app")

        assertEquals("1.8", module.kotlinJvmTarget)
        assertEquals(listOf("-Xlegacy"), module.kotlinFreeCompilerArgs)
    }

    @Test
    fun `compiler plugin options populate project info`() {
        val pluginOptions = listOf(
            "plugin:dev.zacsweers.moshix.compiler:enabled=true",
            "plugin:dev.zacsweers.moshix.compiler:enableSealed=true",
        )

        val module = createReader(
            kotlinTask = kotlinTask(pluginOptions = pluginOptions),
            kotlinTaskName = "compileDebugKotlin",
            hasLegacyKotlinPlugin = true,
        )
            .getProjectInfo(false).modules.getValue("app")

        assertEquals(pluginOptions, module.kotlinPluginOptions)
    }

    private fun createReader(
        kotlinTask: Task,
        kotlinTaskName: String,
        hasLegacyKotlinPlugin: Boolean,
        isAndroidApplication: Boolean = true,
        isAndroidKmp: Boolean = false,
    ): GradleProjectInfoReader {
        val projectDir = File("/project")
        val appDir = File(projectDir, "app")
        val gradle = gradle()
        val appProject = mock<Project>()
        val plugins = mock<PluginContainer>()
        whenever(plugins.hasPlugin(any<String>())).thenAnswer {
            when (it.getArgument<String>(0)) {
                "com.android.application" -> isAndroidApplication
                "com.android.kotlin.multiplatform.library" -> isAndroidKmp
                "org.jetbrains.kotlin.multiplatform" -> hasLegacyKotlinPlugin
                else -> false
            }
        }
        val tasks = mock<TaskContainer>()
        whenever(tasks.findByName(kotlinTaskName)).thenReturn(kotlinTask)
        whenever(tasks.iterator()).thenReturn(mutableListOf<Task>().iterator())
        val configurations = mock<ConfigurationContainer>()
        whenever(configurations.names).thenReturn(sortedSetOf())
        val extensions = mock<ExtensionContainer>()
        whenever(extensions.getByName("android")).thenReturn(AndroidExtension())
        whenever(extensions.findByName("kapt")).thenReturn(null)
        val appLayout = layout(File(appDir, "build"))

        whenever(appProject.path).thenReturn(":app")
        whenever(appProject.name).thenReturn("app")
        whenever(appProject.projectDir).thenReturn(appDir)
        whenever(appProject.plugins).thenReturn(plugins)
        whenever(appProject.tasks).thenReturn(tasks)
        whenever(appProject.configurations).thenReturn(configurations)
        whenever(appProject.extensions).thenReturn(extensions)
        whenever(appProject.layout).thenReturn(appLayout)
        whenever(appProject.gradle).thenReturn(gradle)

        val rootProject = mock<Project>()
        whenever(rootProject.gradle).thenReturn(gradle)
        whenever(rootProject.projectDir).thenReturn(projectDir)
        whenever(rootProject.subprojects).thenReturn(setOf(appProject))
        return GradleProjectInfoReader(rootProject, null, projectDir)
    }

    private fun gradle(): Gradle {
        val taskGraph = mock<TaskExecutionGraph>()
        whenever(taskGraph.allTasks).thenReturn(emptyList())
        val startParameter = mock<StartParameter>()
        whenever(startParameter.taskRequests).thenReturn(emptyList<TaskExecutionRequest>())
        val gradle = mock<Gradle>()
        whenever(gradle.taskGraph).thenReturn(taskGraph)
        whenever(gradle.startParameter).thenReturn(startParameter)
        return gradle
    }

    private fun layout(buildDir: File): ProjectLayout {
        val directory = mock<Directory>()
        whenever(directory.asFile).thenReturn(buildDir)
        val buildDirectory = mock<DirectoryProperty>()
        whenever(buildDirectory.get()).thenReturn(directory)
        val layout = mock<ProjectLayout>()
        whenever(layout.buildDirectory).thenReturn(buildDirectory)
        return layout
    }

    private fun kotlinTask(
        compilerOptions: CompilerOptions? = null,
        legacyOptions: LegacyKotlinOptions? = null,
        pluginOptions: List<String> = emptyList(),
    ): Task {
        val task = Mockito.mock(
            Task::class.java,
            Mockito.withSettings().extraInterfaces(KotlinTaskModel::class.java),
        )
        val model = task as KotlinTaskModel
        whenever(model.compilerOptions).thenReturn(compilerOptions)
        whenever(model.kotlinOptions).thenReturn(legacyOptions)
        val pluginData = mock<Provider<KotlinPluginData>>()
        whenever(pluginData.orNull).thenReturn(KotlinPluginData(KotlinPluginOptions(pluginOptions)))
        whenever(model.`getKotlinPluginData$kotlin_gradle_plugin_common`()).thenReturn(pluginData)
        return task
    }

    interface KotlinTaskModel {
        val compilerOptions: CompilerOptions?
        val kotlinOptions: LegacyKotlinOptions?
        fun `getKotlinPluginData$kotlin_gradle_plugin_common`(): Provider<KotlinPluginData>?
    }

    class KotlinPluginData(private val options: KotlinPluginOptions) {
        fun getOptions(): KotlinPluginOptions = options
    }

    class KotlinPluginOptions(private val arguments: List<String>) {
        fun getArguments(): List<String> = arguments
    }

    class CompilerOptions(
        private val jvmTarget: Provider<JvmTargetValue>,
        private val freeCompilerArgs: Provider<List<String>>,
    ) {
        fun getJvmTarget(): Provider<JvmTargetValue> = jvmTarget

        fun getFreeCompilerArgs(): Provider<List<String>> = freeCompilerArgs
    }

    class JvmTargetValue(private val target: String) {
        fun getTarget(): String = target
    }

    class LegacyKotlinOptions(
        private val jvmTarget: String,
        private val freeCompilerArgs: List<String>,
    ) {
        fun getJvmTarget(): String = jvmTarget

        fun getFreeCompilerArgs(): List<String> = freeCompilerArgs
    }

    private class AndroidExtension {
        fun getCompileSdkVersion(): String = "android-36"

        fun getBuildToolsVersion(): String = "35.0.0"

        fun getCompileOptions(): CompileOptions = CompileOptions()

        fun getDefaultConfig(): DefaultConfig = DefaultConfig()

        fun getExtensions(): LegacyExtensions = LegacyExtensions()

        fun getSourceSets(): SourceSets = SourceSets()

        fun getApplicationVariants(): List<Any> = listOf(AndroidVariant())

        fun getSigningConfigs(): List<Any> = emptyList()

        fun getNamespace(): String = "com.example.app"
    }

    private class CompileOptions {
        fun getSourceCompatibility(): String = "11"

        fun getTargetCompatibility(): String = "11"
    }

    private class AndroidVariant {
        fun getName(): String = "debug"

        fun getSigningConfig(): Any? = null
    }

    private class DefaultConfig {
        fun getMinSdkVersion(): MinSdkVersion = MinSdkVersion()

        fun getManifestPlaceholders(): Map<String, String> = emptyMap()

        fun getApplicationId(): String = "com.example.app"
    }

    private class MinSdkVersion {
        fun getApiLevel(): Int = 24
    }

    @Suppress("UNUSED_PARAMETER")
    private class LegacyExtensions {
        fun getByName(name: String): Any? = null
    }

    @Suppress("UNUSED_PARAMETER")
    private class SourceSets {
        fun findByName(name: String): Any? = null
    }
}
