@file:Suppress("HasPlatformType")

package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.compiler.*
import org.junit.Assume
import org.junit.rules.ExternalResource
import java.io.File


// build directory
val buildDir = TestGlobal.buildDir
val tempCompileDir = File(buildDir, "compiled")
val stagingDir = File(buildDir, "staging")

// source file
val assetsDir = File("../idea/src/test/assets").absoluteFile.normalize()
val assetsJavaDir = File(assetsDir, "java")
val assetsKotlinDir = File(assetsDir, "kotlin")
val assetsLibDir = File(assetsDir, "libs")
val assetsFlatDir = File(assetsDir, "android/flatDir")
val assetsAssetsDir = File(assetsDir, "assets")

val assetsAndroidDir get() = TestGlobal.projectRootDir
val assetsAndroidModifySourceDir get() = TestGlobal.modifySourceDir
val androidApkPackage get() = TestGlobal.packageName

var projectInfo = TestGlobal.projectInfo

// dependency
val androidHome = File(System.getenv("ANDROID_HOME")?: throw IllegalStateException("please specific ANDROID_HOME in env"))
val androidPlatform = File("$androidHome/platforms/android-30").also {
    if (!it.exists()) {
        throw IllegalStateException("android.jar not found in: $it")
    }
}
val androidJar = File("$androidPlatform/android.jar")

val context get() = TestGlobal.context

val mockParentDisposable = TestGlobal.mockParentDisposable

val mockModule get() = TestGlobal.mockModule

val String.systemBasedPath get() = File(this).path

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

fun clearBuild() {
    AssembleAndroidProjectOnce.ensure()
    buildDir.clearDir()
}