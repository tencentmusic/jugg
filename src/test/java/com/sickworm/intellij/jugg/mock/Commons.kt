@file:Suppress("HasPlatformType")

package com.sickworm.intellij.jugg.mock

import com.android.tools.idea.run.ApkInfo
import com.google.gson.JsonSyntaxException
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val logger = StdLogger("JuggTest")

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
val assetsApkFile get() = projectInfo.apk
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
    androidHome = androidHome,
    androidBuildTools = androidBuildTools,
    androidJar = androidJar,
    classPathDir = classPathDir,
    modules = emptyMap(),
    parsedApks = listOf(ParsedApk(projectInfo.apkInfo, emptyMap(), emptyMap())),
    variant = "debug"
)

typealias OutputFileMapper = (CompileFile) -> List<CompileOutput>

fun assertCompileResult(task: CompileTask,
                        result: CompileResult,
                        outputFileMapper: OutputFileMapper
) {
    result.printCompileErrors()

    assertEquals(result.task, task)
    if (!result.isAllSuccess) {
        println("???")
    }
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

fun Result<CompileFile, CompileError>.printCompileError() {
    if (isFailed) {
        println("assertCompileResult error count: ${getFailure().errors.size}")
        println("assertCompileResult error messages:\n ${getFailure().errorMessages}")
    }
}

fun CompileTask.Companion.singleJavaFile(filePath: File, outputDir: File, dependencies: List<String> = emptyList()) =
    CompileTask(listOf(CompileFile(CompileFile.Type.Java, filePath, assetsJavaDir, dependencyPaths = dependencies)), outputDir)
