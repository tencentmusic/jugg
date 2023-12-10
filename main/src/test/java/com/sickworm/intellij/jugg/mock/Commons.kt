@file:Suppress("HasPlatformType")

package com.sickworm.intellij.jugg.mock

import com.android.tools.idea.gradle.dsl.api.GradleModelProvider
import com.android.tools.idea.gradle.dsl.model.GradleModelSource
import com.google.gson.JsonSyntaxException
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockApplication
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.ApplicationInfoImpl
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.ui.messages.MessagesService
import com.intellij.testFramework.registerExtension
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import org.mockito.Mockito
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val logger = StdLogger("JuggTest")

// build directory
val buildDir = File("src/test/build").absoluteFile
val tempCompileDir = File(buildDir, "compiled")
val stagingDir = File(buildDir, "staging")

// source file
val assetsDir = File("src/test/assets").absoluteFile
val assetsJavaDir = File(assetsDir, "java")
val assetsKotlinDir = File(assetsDir, "kotlin")
val assetsLibDir = File(assetsDir, "libs")
val assetsClassDir = File(assetsDir, "class")
val assetsFlatDir = File(assetsDir, "android/flatDir")
val assetsAssetsDir = File(assetsDir, "assets")

var projectInfo = try {
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

val assetsAndroidDir get() = projectInfo.projectRoot
val assetsAndroidModifySourceDir get() = projectInfo.modifiedSource
val androidApkPackage get() = projectInfo.packageName

// dependency
val androidHome = File(System.getenv("ANDROID_HOME")?: throw IllegalStateException("please specific ANDROID_HOME in env"))
val androidPlatform = File("$androidHome/platforms/android-30").also {
    if (!it.exists()) {
        throw IllegalStateException("android.jar not found in: $it")
    }
}
val androidJar = File("$androidPlatform/android.jar")

val androidBuildTools = File("$androidHome/build-tools/30.0.3").also {
    if (!it.exists()) {
        throw IllegalStateException("android.jar not found in: $it")
    }
}

val intellijLibraryDir = File("$assetsAndroidDir/.idea/libraries")

val context get() = SimpleCompileContext(
    logger = logger,
    tempCompileDir = tempCompileDir,
    tempModuleDir = File(buildDir, "temp_module"),
    androidHome = androidHome,
    androidJar = androidJar,
    modules = mapOf(mockModule.name to mockModule),
    apkInfos = projectInfo.apkInfos,
    projectDir = projectInfo.projectRoot,
    deployedFiles = emptyList(),
)

val mockParentDisposable = Disposable { }

private val appModuleDir = File(projectInfo.projectRoot, "app")

val mockModule = ModuleInfo(
    name = "mock_module",
    moduleRootDir = appModuleDir,
    projectRootDir = projectInfo.projectRoot,
    sourceDirs = listOf(File(appModuleDir, "src/main/java")),
    resourceDirs = listOf(File(appModuleDir, "src/main/res")),
    assetsDirs = listOf(File(appModuleDir, "src/main/assets")),
    manifestFile = File(appModuleDir, "src/main/AndroidManifest.xml"),
    buildVariant = ModuleInfo.DEFAULT_BUILD_VARIANT,
    compileVersion = null,
    buildToolsVersion = null,
    buildPathInfo = ModuleBuildPathInfo(
        projectInfo.projectRoot,
        appModuleDir,
        ModuleInfo.DEFAULT_BUILD_VARIANT
    ),
    kotlinJvmTarget = "1.8",
    kotlinFreeCompilerArgs = emptyList(),
    javaSourceCompatibility = "1.8",
    javaTargetCompatibility = "1.8",
    moduleDependencies = emptyList(),
    libraryDependencies = emptyList(),
)

typealias OutputFileMapper = (CompileFile) -> List<CompileOutput>

fun assertCompileResult(task: CompileTask,
                        result: CompileResult,
                        outputFileMapper: OutputFileMapper
) {
    result.printCompileErrors()

    assertEquals(result.task, task)
    assertTrue(result.isAllSuccess)
    assertEquals(result.details.size, task.files.size)

    val exceptsOutput = mutableSetOf<CompileOutput>()
    result.details.forEach { detail ->
        assertTrue(detail.isSuccess)
        assertTrue(detail.file.file.exists() && detail.file.file.length() > 0)
        val expectOutput = outputFileMapper(detail.file)
        expectOutput.forEach { relativeOutput ->
            val output = result.outputs.find { it.file.absolutePath == relativeOutput.file.absolutePath }
            assertEquals(relativeOutput, output)
            assertTrue(output!!.file.exists())
            assertTrue(output.file.length() > 0)
        }
        exceptsOutput.addAll(expectOutput)
    }

    // TODO check compiled xml outputs size
    val exceptedOutputWithoutXml = result.outputs.filter { !it.relativeFile.startsWith("res") }
    val outputWithoutXml = result.outputs.filter { !it.relativeFile.startsWith("res") }
    assertEquals(exceptedOutputWithoutXml.size, outputWithoutXml.size)
}

fun clearBuild() {
    AssembleAndroidProjectOnce.ensure()
    buildDir.clearDir()
}

fun assertCompileResultFailed(task: CompileTask, result: CompileResult, errorList: Map<CompileFile, Int>) {
    result.printCompileErrors()

    assertTrue(!result.isAllSuccess)
    assertEquals(result.details.size, task.files.size)
    assertTrue(result.outputs.isEmpty())

    result.details.forEach {
        assertTrue(it.isFailed)
        assertTrue(it.file.file.exists() && it.file.file.length() > 0)
        val errorCount = errorList[it.file]?: 0
        assertEquals(it.getFailure().errors.size, errorCount)
    }
}

fun CompileResult.printCompileErrors() {
    details.forEach {
        it.printCompileError()
    }
}

fun Result<CompileFile, CompileError>.printCompileError() {
    if (isFailed) {
        println("assertCompileResult error count: ${getFailure().errors.size}")
        println("assertCompileResult error messages:\n ${getFailure().errorMessages}")
    }
}

fun CompileTask.Companion.singleJavaFile(filePath: File, outputDir: File, dependencies: List<String> = emptyList()) =
    CompileTask(listOf(CompileFile(CompileFile.Type.Java, filePath, assetsJavaDir, mockModule, dependencyPaths = dependencies)), outputDir)

val String.systemBasedPath get() = File(this).path


val application = MockApplication {}

@Suppress("unused", "UnstableApiUsage")
val init = run {
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
}

@Suppress("TestFunctionName")
fun CompileTask(files: List<CompileFile>, outputDir: File) = CompileTask(files, outputDir) { false }

/**
 * Need an Android device for this test.
 */
annotation class RequiresDevice