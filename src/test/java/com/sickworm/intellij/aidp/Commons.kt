@file:Suppress("HasPlatformType")

package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.lang.IllegalStateException

val logger = Logger.getInstance("AidpTest")

// build directory
val buildDir = File("src/test/build").absoluteFile
val compileClassDir = File("src/test/build/compiled").absoluteFile
val compileDexDir = File("src/test/build/dex").absoluteFile
val compileOverlayDir = File("src/test/build/overlay").absoluteFile
val classPathDir = File("src/test/build/classes").absoluteFile

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

fun clearBuild() = buildDir.listFiles()?.forEach { it.deleteRecursively() }

fun assertCompileResult(sourceDir: File, result: Result<CompileFile, CompileError>, isSuccess: Boolean,
                        errorCount: Int? = null
) {
    result.printCompileError()

    assert(result.isSuccess == isSuccess)
    assert(result.isFailed == !isSuccess)
    if (isSuccess) {
        assert(result.getFailureOrNull() == null)
    } else {
        if (errorCount != null) {
            assert(result.getFailure().errors.size == errorCount)
        }
    }

    // ensure file exists if build success
    val classFile = result.file.file.changeBaseDir(sourceDir, classPathDir, "class")
    if (isSuccess) {
        assert(classFile.exists() && classFile.length() > 0)
    } else {
        // only AidpCompiler will ensure failed file not exists, JavaCompiler will not
//            assert(!classFile.exists())
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
