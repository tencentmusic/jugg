package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.data.ComposeResourceSupportStatus
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestTargetResolver
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleDependency
import net.bytebuddy.ByteBuddy
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.StubMethod
import net.bytebuddy.matcher.ElementMatchers
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginContainer
import org.gradle.api.tasks.TaskContainer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.security.CodeSource
import java.security.ProtectionDomain

class GradleProjectInfoReaderAndroidTestTest {

    // These tests exercise GradleProjectInfoReader.buildAndroidTestModuleInfo() directly
    // (a companion object function added in this task).

    private val projectDir = File("/project")
    private val appDir = File("/project/app")
    private val libraryDir = File("/project/library1")

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun appModule(
        appId: String = "com.example.app",
        buildVariant: String = "debug",
    ) = ModuleInfo.virtualModule.copy(
        name = "app",
        moduleType = ModuleInfo.Type.Application,
        moduleRootDir = appDir,
        projectRootDir = projectDir,
        applicationId = appId,
        buildVariant = buildVariant,
        buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, buildVariant, buildDirRelativePath = ""),
    )

    private fun libraryModule(namespace: String = "com.example.library1") = ModuleInfo.virtualModule.copy(
        name = "library1",
        moduleType = ModuleInfo.Type.Library,
        moduleRootDir = libraryDir,
        projectRootDir = projectDir,
        namespace = namespace,
        buildPathInfo = ModuleBuildPathInfo(projectDir, libraryDir, "debug", buildDirRelativePath = ""),
    )

    @Test
    fun `Compose task source validation is not exposed as public API`() {
        assertFalse(GradleProjectInfoReader.Companion::class.java.methods.any {
            it.name == "areComposeTaskSourcesSupported"
        })
    }

    @Test
    fun `project info preserves unsupported Compose detection reason`() {
        val missingSource = ProtectionDomain(null, null)
        val inconsistentSource = javaClass.protectionDomain

        val missingSourceInfo = readComposeResourceInfo(missingSource)
        val inconsistentSourceInfo = readComposeResourceInfo(inconsistentSource)

        assertEquals(ComposeResourceSupportStatus.Unsupported, missingSourceInfo?.supportStatus)
        assertTrue(missingSourceInfo?.unsupportedReason?.contains("task code source") == true)
        assertEquals(ComposeResourceSupportStatus.Unsupported, inconsistentSourceInfo?.supportStatus)
        assertTrue(inconsistentSourceInfo?.unsupportedReason?.contains("generator metadata") == true)
    }

    @Test
    fun `project info rejects incomplete Compose resource directory metadata`() {
        val pluginJar = temp.newFile("compose-gradle-plugin-1.7.3.jar")
        val codeSource = CodeSource(pluginJar.toURI().toURL(), null as Array<java.security.cert.Certificate>?)

        val info = readComposeResourceInfo(ProtectionDomain(codeSource, null))

        assertEquals(ComposeResourceSupportStatus.Unsupported, info?.supportStatus)
        assertTrue(info?.unsupportedReason, info?.unsupportedReason?.contains("directory metadata") == true)
    }

    private fun readComposeResourceInfo(protectionDomain: ProtectionDomain) =
        createReaderWithComposeTasks(protectionDomain).getProjectInfo(false)
            .modules["compose"]?.composeResourceInfo

    private fun createReaderWithComposeTasks(protectionDomain: ProtectionDomain): GradleProjectInfoReader {
        val tasks = mock<TaskContainer>()
        whenever(tasks.iterator()).thenReturn(createComposeTasks(protectionDomain).toMutableList().iterator())
        val plugins = mock<PluginContainer>()
        whenever(plugins.hasPlugin(any<String>())).thenAnswer {
            it.getArgument<String>(0) == "org.jetbrains.compose"
        }
        val buildDirectory = mock<DirectoryProperty>()
        val directory = mock<Directory>()
        whenever(directory.asFile).thenReturn(File("/project/compose/build"))
        whenever(buildDirectory.get()).thenReturn(directory)
        val layout = mock<ProjectLayout>()
        whenever(layout.buildDirectory).thenReturn(buildDirectory)
        val configurations = mock<ConfigurationContainer>()
        whenever(configurations.names).thenReturn(sortedSetOf())
        val composeProject = mock<Project>()
        whenever(composeProject.path).thenReturn(":compose")
        whenever(composeProject.name).thenReturn("compose")
        whenever(composeProject.projectDir).thenReturn(File("/project/compose"))
        whenever(composeProject.plugins).thenReturn(plugins)
        whenever(composeProject.tasks).thenReturn(tasks)
        whenever(composeProject.layout).thenReturn(layout)
        whenever(composeProject.configurations).thenReturn(configurations)

        val taskGraph = mock<TaskExecutionGraph>()
        whenever(taskGraph.allTasks).thenReturn(emptyList())
        val gradle = mock<Gradle>()
        whenever(gradle.taskGraph).thenReturn(taskGraph)
        val rootProject = mock<Project>()
        whenever(rootProject.gradle).thenReturn(gradle)
        whenever(rootProject.projectDir).thenReturn(projectDir)
        whenever(rootProject.subprojects).thenReturn(setOf(composeProject))
        return GradleProjectInfoReader(rootProject, null, projectDir)
    }

    private fun createComposeTasks(protectionDomain: ProtectionDomain): List<Task> {
        return listOf(
            "XmlValuesConverterTask",
            "GenerateResClassTask",
            "GenerateResourceAccessorsTask",
            "GenerateExpectResourceCollectorsTask",
            "GenerateActualResourceCollectorsTask",
        ).map { taskName ->
            ByteBuddy()
                .subclass(Any::class.java)
                .implement(Task::class.java)
                .name("com.example.$taskName")
                .method(ElementMatchers.isAbstract())
                .intercept(StubMethod.INSTANCE)
                .make()
                .load(javaClass.classLoader, ClassLoadingStrategy.Default.WRAPPER.with(protectionDomain))
                .loaded
                .getDeclaredConstructor()
                .newInstance() as Task
        }
    }

    @Test
    fun `buildAndroidTestModuleInfo returns null when sourceDirs is empty`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(),
            sourceDirs = emptyList(),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertNull(result)
    }

    @Test
    fun `buildAndroidTestModuleInfo sets buildVariant to debugAndroidTest`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("debugAndroidTest", result?.buildVariant)
    }

    @Test
    fun `buildAndroidTestModuleInfo keeps owner flavor when resolving buildVariant`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(buildVariant = "jooxDebug"),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("jooxDebugAndroidTest", result?.buildVariant)
        assertEquals("jooxDebugAndroidTest", result?.buildPathInfo?.buildVariant)
    }

    @Test
    fun `buildAndroidTestModuleInfo uses explicit testApplicationId when provided`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule("com.example.app"),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = "com.example.app.tests",
        )
        assertEquals("com.example.app.tests", result?.applicationId)
    }

    @Test
    fun `buildAndroidTestModuleInfo defaults applicationId to appId dot test`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule("com.example.app"),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("com.example.app.test", result?.applicationId)
    }

    @Test
    fun `buildAndroidTestModuleInfo sets instrumentationTargetPackage to app applicationId`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule("com.example.app"),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("com.example.app", result?.instrumentationTargetPackage)
    }

    @Test
    fun `buildAndroidTestModuleInfo name is appModuleName dot androidTest`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals("app.androidTest", result?.name)
    }

    @Test
    fun `buildAndroidTestModuleInfo declares dependency on app module`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = appModule(),
            sourceDirs = listOf(File("/project/app/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )
        assertEquals(listOf(ModuleDependency("app")), result?.moduleDependencies)
    }

    @Test
    fun `buildAndroidTestModuleInfo defaults library self targeting package to namespace dot test`() {
        val result = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = libraryModule("com.example.library1"),
            sourceDirs = listOf(File("/project/library1/src/androidTest/java")),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )

        assertEquals("library1.androidTest", result?.name)
        assertEquals("com.example.library1.test", result?.applicationId)
        assertEquals("com.example.library1.test", result?.instrumentationTargetPackage)
        assertEquals(listOf(ModuleDependency("library1")), result?.moduleDependencies)
    }

    @Test
    fun `library androidTest default module resolves Gradle produced self targeting apk`() {
        val e2eProjectDir = temp.newFolder("project")
        val sourceRoot = File(e2eProjectDir, "library1/src/androidTest/java")
        val sourceFile = File(sourceRoot, "com/example/library1/Library1LogicInstrumentedTest.kt").apply {
            parentFile.mkdirs()
            writeText("class Library1LogicInstrumentedTest")
        }
        val module = GradleProjectInfoReader.buildAndroidTestModuleInfo(
            appModuleInfo = libraryModule("com.example.library1").copy(
                moduleRootDir = File(e2eProjectDir, "library1"),
                projectRootDir = e2eProjectDir,
                buildPathInfo = ModuleBuildPathInfo(e2eProjectDir, File(e2eProjectDir, "library1"), "debug", buildDirRelativePath = ""),
            ),
            sourceDirs = listOf(sourceRoot),
            libraryDependencies = emptyList(),
            testApplicationId = null,
        )!!
        val testApk = ApkInfo(
            files = listOf(ApkFileUnit("com.example.library1.test", "", true, File("library1-debug-androidTest.apk"))),
            applicationId = "com.example.library1.test",
            instrumentationTargetPackage = "com.example.library1.test",
        )

        val result = AndroidTestTargetResolver.resolve(
            sourcePath = sourceFile.path,
            projectDir = e2eProjectDir,
            modules = listOf(module),
            apks = listOf(testApk),
        )

        assertEquals(module, result.module)
        assertEquals(testApk, result.testApk)
    }
}
