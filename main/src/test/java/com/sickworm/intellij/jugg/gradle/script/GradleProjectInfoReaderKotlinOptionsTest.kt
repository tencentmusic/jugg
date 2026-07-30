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
    fun `Kotlin 2 compiler options populate project info`() {
        val jvmTarget = mock<Provider<JvmTargetValue>>()
        whenever(jvmTarget.orNull).thenReturn(JvmTargetValue("11"))
        val freeCompilerArgs = mock<Provider<List<String>>>()
        whenever(freeCompilerArgs.orNull).thenReturn(listOf("-Xexpect-actual-classes"))
        val compilerOptions = CompilerOptions(jvmTarget, freeCompilerArgs)
        val kotlinTask = kotlinTask(compilerOptions)

        val module = createReader(kotlinTask).getProjectInfo(false).modules.getValue("app")

        assertEquals("11", module.kotlinJvmTarget)
        assertEquals(listOf("-Xexpect-actual-classes"), module.kotlinFreeCompilerArgs)
    }

    private fun createReader(kotlinTask: Task): GradleProjectInfoReader {
        val projectDir = File("/project")
        val appDir = File(projectDir, "app")
        val gradle = gradle()
        val appProject = mock<Project>()
        val plugins = mock<PluginContainer>()
        whenever(plugins.hasPlugin(any<String>())).thenAnswer {
            it.getArgument<String>(0) in setOf("com.android.application", "org.jetbrains.kotlin.multiplatform")
        }
        val tasks = mock<TaskContainer>()
        whenever(tasks.findByName("compileDebugKotlin")).thenReturn(null)
        whenever(tasks.findByName("compileDebugKotlinAndroid")).thenReturn(kotlinTask)
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

    private fun kotlinTask(compilerOptions: CompilerOptions): Task {
        val task = Mockito.mock(
            Task::class.java,
            Mockito.withSettings().extraInterfaces(KotlinTaskModel::class.java),
        )
        whenever((task as KotlinTaskModel).compilerOptions).thenReturn(compilerOptions)
        return task
    }

    interface KotlinTaskModel {
        val compilerOptions: CompilerOptions
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
