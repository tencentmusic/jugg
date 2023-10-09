@file:Suppress("HasPlatformType")

package com.sickworm.intellij.jugg.mock

import com.google.gson.JsonSyntaxException
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.ide.JuggSettings
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
    tempClasspathDir = File(buildDir, "classpath"),
    androidHome = androidHome,
    androidBuildTools = androidBuildTools,
    androidJar = androidJar,
    modules = mapOf(mockModule.name to mockModule),
    apkInfos = projectInfo.apkInfos,
    minApi = JuggSettings.minApi,
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
    ),
    kotlinJvmTarget = "1.8",
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
