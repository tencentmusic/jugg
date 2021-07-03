package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import java.io.File

val logger = Logger.getInstance("AidpTest")

val buildDir: String = File("src/test/build").absolutePath
val compileClassDir: String = File("src/test/build/compiled").absolutePath
val classPathDir: String = File("src/test/build/classes").absolutePath
val compileDexDir: String = File("src/test/build/dex").absolutePath

val assetsDir: String = File("src/test/assets").absolutePath
val assetsJavaDir = "$assetsDir/java"
val assetsKotlinDir = "$assetsDir/kotlin"
val assetsLibDir = "$assetsDir/lib"
val assetsClassDir = "$assetsDir/class"
val assetsAndroidDir = "$assetsDir/android"

fun clearBuild() = File(buildDir).listFiles()?.forEach { it.deleteRecursively() }

fun assertCompileResult(sourceDir: String, result: Result<CompileFileInfo, CompileError>, isSuccess: Boolean, errorCount: Int? = null) {
    if (result.isFailed) {
        println("assertCompileResult error count: ${result.getFailure().errors.size}")
        println("assertCompileResult error messages:\n ${result.getFailure().errorMessages}")
    }

    assert(result.isSuccess == isSuccess)
    assert(result.isFailed == !isSuccess)
    if (isSuccess) {
        assert(result.getFailureOrNull() == null)
    } else {
        if (errorCount != null) {
            assert(result.getFailure().errors.size == errorCount)
        }
    }

    val classFile = sourceFileToClassFile(sourceDir, result.file.file, classPathDir)
    if (isSuccess) {
        assert(classFile.exists() && classFile.length() > 0)
    } else {
        // compiler doesn't know the generated class path so compiler won't delete generated files if
        // compilation failed in the middle
    }
}

fun sourceFileToClassFile(sourceDir: String, file: File, buildDir: String): File {
    val className = file.name.replace(file.extension, "class")
    val packagePath = guessClassFilePath(sourceDir, file)
    return File(buildDir + packagePath + className)
}

fun guessClassFilePath(baseDir: String, file: File): String {
    return file.absolutePath.substring(baseDir.length, file.absolutePath.length - file.name.length)
}