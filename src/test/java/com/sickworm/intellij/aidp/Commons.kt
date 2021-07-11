@file:Suppress("HasPlatformType")

package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.compiler.*
import java.io.File
import java.lang.IllegalStateException

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
val assetsLibDir = File(assetsDir, "lib")
val assetsClassDir = File(assetsDir, "class")
val assetsAndroidDir = File(assetsDir, "android")
val assetsAssetsDir = File(assetsDir, "assets")

// dependency
val androidHome = System.getenv("ANDROID_HOME")
    ?: throw IllegalStateException("please specific ANDROID_HOME in env")
val androidJar = "$androidHome/platforms/android-30/android.jar".also {
    if (!File(it).exists()) {
        throw IllegalStateException("android.jar not found in: $it")
    }
}
val intellijLibraryDir = "$assetsAndroidDir/.idea/libraries"

fun assertCompileResult(task: CompileTask,
                        result: CompileResult,
                        outputType: CompileOutput.Type,
                        outputSize: Int = task.files.size
) {
    result.printCompileErrors()

    assert(result.isAllSuccess)
    assert(result.details.size == task.files.size)
    assert(result.outputs.size == outputSize)

    result.details.forEach {
        assert(it.isSuccess)
        assert(it.file.file.exists() && it.file.file.length() > 0)
    }
    result.outputs.forEach {
        assert(it.file.exists() && it.file.length() > 0)
        if (outputType != null) {
            assert(it.type == outputType)
        }
    }
}

fun clearBuild() = buildDir.clearDir()

fun assertCompileResultFailed(task: CompileTask, result: CompileResult, errorList: Map<CompileFile, Int>) {
    result.printCompileErrors()

    assert(!result.isAllSuccess)
    assert(result.details.size == task.files.size)
    assert(result.outputs.size == 0)

    result.details.forEach {
        assert(it.isFailed)
        assert(it.file.file.exists() && it.file.file.length() > 0)
        val errorCount = errorList[it.file]?: 0
        assert(it.getFailure().errors.size == errorCount)
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
    CompileTask(listOf(CompileFile(filePath, CompileFile.Type.Java, assetsJavaDir, dependencyPaths = dependencies)), outputDir)
