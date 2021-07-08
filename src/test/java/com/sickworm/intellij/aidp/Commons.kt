package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.lang.IllegalStateException

val logger = Logger.getInstance("AidpTest")

// build directory
val buildDir: String = File("src/test/build").absolutePath
val compileClassDir: String = File("src/test/build/compiled").absolutePath
val compileDexDir: String = File("src/test/build/dex").absolutePath
val compileOverlayDir: String = File("src/test/build/overlay").absolutePath
val classPathDir: String = File("src/test/build/classes").absolutePath

// source file
val assetsDir: String = File("src/test/assets").absolutePath
val assetsJavaDir = "$assetsDir/java"
val assetsKotlinDir = "$assetsDir/kotlin"
val assetsLibDir = "$assetsDir/lib"
val assetsClassDir = "$assetsDir/class"
val assetsAndroidDir = "$assetsDir/android"
val assetsAssetsDir = "$assetsDir/assets"

// dependency
val androidHome = System.getenv("ANDROID_HOME")
    ?: throw IllegalStateException("please specific ANDROID_HOME in env")
val androidJar = "$androidHome/platforms/android-30/android.jar".also {
    if (!File(it).exists()) {
        throw IllegalStateException("android.jar not found in: $it")
    }
}
val intellijLibraryDir = "$assetsAndroidDir/.idea/libraries"

fun clearBuild() = File(buildDir).listFiles()?.forEach { it.deleteRecursively() }

fun assertCompileResult(sourceDir: String, result: Result<CompileFile, CompileError>, isSuccess: Boolean,
                        errorCount: Int? = null,
                        isCheckClassExist: Boolean = true,
                        isCheckDexExist: Boolean = false
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
    if (isCheckClassExist) {
        val classFile = result.file.file.changeBaseDir(File(sourceDir), File(classPathDir), "class")
        if (isSuccess) {
            assert(classFile.exists() && classFile.length() > 0)
        } else {
            // only AidpCompiler will ensure failed file not exists, JavaCompiler will not
//            assert(!classFile.exists())
        }
    }
    if (isCheckDexExist) {
        val dexFile = result.file.file.changeBaseDir(File(sourceDir), File(compileDexDir), "dex")
        if (isSuccess) {
            assert(dexFile.exists() && dexFile.length() > 0)
        } else {
            assert(!dexFile.exists())
        }
    }
}

fun List<Result<CompileFile, CompileError>>.printCompileErrors() {
    forEach {
        it.printCompileError()
    }
}

fun Result<CompileFile, CompileError>.printCompileError() {
    if (isFailed) {
        println("assertCompileResult error count: ${getFailure().errors.size}")
        println("assertCompileResult error messages:\n ${getFailure().errorMessages}")
    }
}

fun CompileTask.Companion.singleJavaFile(filePath: String, outputDir: String, dependencies: List<String> = emptyList()) =
    CompileTask(listOf(CompileFile(File(filePath), CompileFile.Type.Java, File(assetsJavaDir), dependencyPaths = dependencies)), File(outputDir))
