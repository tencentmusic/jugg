@file:Suppress("HasPlatformType")

package com.sickworm.intellij.jugg

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.clearDir
import com.sickworm.intellij.jugg.compiler.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val logger = Logger.getInstance("AidpTest")

// build directory
val buildDir = File("src/test/build").absoluteFile
val tempCompileDir = File(buildDir, "compiled")
val classPathDir = File(buildDir, "classpath")
val stagingDir = File(buildDir, "staging")

// source file
val assetsDir = File("src/test/assets").absoluteFile
val assetsJavaDir = File(assetsDir, "java")
val assetsKotlinDir = File(assetsDir, "kotlin")
val assetsLibDir = File(assetsDir, "libs")
val assetsClassDir = File(assetsDir, "class")
val assetsAndroidDir = File(assetsDir, "android/MyApplicationIntellij")
val assetsApkFile = File(assetsDir, "android/app-debug.apk")
val assetsFlatDir = File(assetsDir, "android/flatDir")
val assetsAssetsDir = File(assetsDir, "assets")

// dependency
val androidHome = System.getenv("ANDROID_HOME")
    ?: throw IllegalStateException("please specific ANDROID_HOME in env")
val androidJar = File("$androidHome/platforms/android-30/android.jar").also {
    if (!it.exists()) {
        throw IllegalStateException("android.jar not found in: $it")
    }
}
val androidApkPackage = "com.example.myapplication"

val androidBuildTools = File("$androidHome/build-tools/30.0.3").also {
    if (!it.exists()) {
        throw IllegalStateException("android.jar not found in: $it")
    }
}

val intellijLibraryDir = File("$assetsAndroidDir/.idea/libraries")

val context = BaseCompileContext(
    logger = logger,
    tempCompileDir = tempCompileDir,
    androidBuildTools = androidBuildTools,
    androidJar = androidJar,
    classPathDir = classPathDir,
    apks = listOf(ApkInfo(assetsApkFile, androidApkPackage))
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
    val exceptedOutputWithoutXml = result.outputs.filter { !it.file.relativeTo(it.baseDir).startsWith("res") }
    val outputWithoutXml = result.outputs.filter { !it.file.relativeTo(it.baseDir).startsWith("res") }
    assertEquals(exceptedOutputWithoutXml.size, outputWithoutXml.size)
}

fun clearBuild() = buildDir.clearDir()

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

fun com.sickworm.intellij.jugg.compiler.Result<CompileFile, CompileError>.printCompileError() {
    if (isFailed) {
        println("assertCompileResult error count: ${getFailure().errors.size}")
        println("assertCompileResult error messages:\n ${getFailure().errorMessages}")
    }
}

fun CompileTask.Companion.singleJavaFile(filePath: File, outputDir: File, dependencies: List<String> = emptyList()) =
    CompileTask(listOf(CompileFile(CompileFile.Type.Java, filePath, assetsJavaDir, dependencyPaths = dependencies)), outputDir)
