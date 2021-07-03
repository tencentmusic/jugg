package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import java.io.File

val logger = Logger.getInstance("AidpTest")

val buildDir: String = File("src/test/build").absolutePath
val compileClassDir: String = File("src/test/build/compiled").absolutePath
val compileDexDir: String = File("src/test/build/dex").absolutePath
val classPathDir: String = File("src/test/build/classes").absolutePath

val assetsDir: String = File("src/test/assets").absolutePath
val assetsJavaDir = "$assetsDir/java"
val assetsKotlinDir = "$assetsDir/kotlin"
val assetsLibDir = "$assetsDir/lib"
val assetsClassDir = "$assetsDir/class"
val assetsAndroidDir = "$assetsDir/android"

fun clearBuild() = File(buildDir).listFiles()?.forEach { it.deleteRecursively() }

fun assertCompileResult(sourceDir: String, result: Result<CompileFileInfo, CompileError>, isSuccess: Boolean,
                        errorCount: Int? = null,
                        isCheckClassExist: Boolean = true,
                        isCheckDexExist: Boolean = false
) {
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

    if (isSuccess) {
        // ensure file exists if build success
        if (isCheckClassExist) {
            val classFile = result.file.file.changeBaseDir(File(sourceDir), File(classPathDir), "class")
            assert(classFile.exists() && classFile.length() > 0)
        }
        if (isCheckDexExist) {
            val dexFile = result.file.file.changeBaseDir(File(sourceDir), File(compileDexDir), "dex")
            assert(dexFile.exists() && dexFile.length() > 0)
        }
    }
}