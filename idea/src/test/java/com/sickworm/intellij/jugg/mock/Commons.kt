@file:Suppress("HasPlatformType")

package com.sickworm.intellij.jugg.mock

import com.android.tools.idea.gradle.dsl.api.GradleModelProvider
import com.android.tools.idea.gradle.dsl.model.GradleModelSource
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
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.logic.IdeaPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.Assume
import org.junit.rules.ExternalResource
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
val assetsFlatDir = File(assetsDir, "android/flatDir")
val assetsAssetsDir = File(assetsDir, "assets")

var projectInfo = TestGlobal.projectInfo

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

val context get() = SimpleCompileContext(
    logger = logger,
    tempCompileDir = tempCompileDir,
    tempModuleDir = File(buildDir, "temp_module"),
    androidHome = androidHome,
    androidJar = androidJar,
    modules = AssembleAndroidProjectOnce.getProjectInfo().modules,
    apkInfos = projectInfo.apkInfos,
    projectDir = projectInfo.projectRoot,
    incrementalDataDir = File(buildDir, "incremental"),
    deployedFiles = mutableListOf(),
)

val mockParentDisposable = Disposable { }

val mockModule get() = TestGlobal.mockModule

typealias OutputFileMapper = (CompileFile) -> List<CompileOutput>

fun assertCompileResult(task: CompileTask,
                        result: CompileResult,
                        outputFileMapper: OutputFileMapper
) {
    result.printCompileErrors()

    assertEquals(task, result.task)
    assertTrue(result.isAllSuccess)
    assertEquals(task.files.size, result.details.size)

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

fun CompileTask.Companion.singleJavaFile(filePath: File, outputDir: File, baseDir: File = assetsJavaDir, dependencies: List<String> = emptyList()) =
    CompileTask(listOf(CompileFile(CompileFile.Type.Java, filePath, baseDir, mockModule, dependencyPaths = dependencies)), outputDir)

val String.systemBasedPath get() = File(this).path


@Suppress("unused", "UnstableApiUsage")
val init = run {
    TestGlobal.init()
}

@Suppress("TestFunctionName")
fun CompileTask(files: List<CompileFile>, outputDir: File) = CompileTask(files, outputDir, CompileStatusHolder.DEFAULT)


/**
 * JUnit 4 ClassRule that skips the entire test class when no Android device is connected.
 * Usage: companion object { @ClassRule @JvmField val deviceRule = RequiresDeviceRule() }
 */
class RequiresDeviceRule : ExternalResource() {
    override fun before() {
        val process = Runtime.getRuntime().exec(arrayOf("adb", "devices"))
        val output = String(process.inputStream.readAllBytes())
        process.waitFor()
        // "adb devices" always prints a header line; a connected device adds at least one more line
        val hasDevice = output.lines().drop(1).any { it.trim().isNotEmpty() }
        Assume.assumeTrue("No Android device connected, skipping @RequiresDevice test", hasDevice)
    }
}