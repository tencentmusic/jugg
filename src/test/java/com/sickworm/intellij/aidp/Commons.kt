package com.sickworm.intellij.aidp

import java.io.File

val buildDir: String = File("src/test/build").absolutePath
val assetsDir: String = File("src/test/assets").absolutePath
val assetsJavaDir = "$assetsDir/java"
val assetsKotlinDir = "$assetsDir/kotlin"
val assetsLibDir = "$assetsDir/lib"
val assetsClassDir = "$assetsDir/class"
val assetsAndroidDir = "$assetsDir/android"

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
    val className = result.file.file.let {
        it.name.replace(it.extension, "class")
    }
    val packagePath = result.file.file.let {
        it.absolutePath.substring(sourceDir.length, it.absolutePath.length - it.name.length)
    }
    val classFile = File(buildDir + packagePath + className)
    if (isSuccess) {
        assert(classFile.exists() && classFile.length() > 0)
    } else {
        // compiler doesn't know the generated class path so compiler won't delete generated files if
        // compilation failed in the middle
    }
}