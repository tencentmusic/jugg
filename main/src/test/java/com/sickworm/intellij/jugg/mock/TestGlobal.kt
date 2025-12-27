@file:Suppress("MayBeConstant", "HasPlatformType")

package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileError
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.file
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue


typealias OutputFileMapper = (CompileFile) -> List<CompileOutput>

object TestGlobal {

    private val rootDir = File("../").absoluteFile.normalize()

    private val ideaDir = File(rootDir, "idea").absoluteFile
    val buildDir = File(rootDir, "main/src/test/build").absoluteFile
    private val tempCompileDir = File(buildDir, "compiled")
    val stagingDir = File(buildDir, "staging")

    val projectRootDir: File = File(ideaDir, "src/test/assets/android/MyApplicationIntellij").absoluteFile
    private val appModuleDir = File(projectRootDir, "app")
    val assetsAndroidDir = projectRootDir
    val modifySourceDir = File(ideaDir, "src/test/assets/android/modify_source")

    val androidHome = File(System.getenv("ANDROID_HOME")?: throw IllegalStateException("please specific ANDROID_HOME in env"))
    val javaHome = File(System.getProperty("java.home")?: throw IllegalStateException("please specific java home in env"))
    val androidPlatform = File("${androidHome}/platforms/android-30").also {
        if (!it.exists()) {
            throw IllegalStateException("android.jar not found in: $it")
        }
    }
    val androidJar = File("${androidPlatform}/android.jar")


    val mockParentDisposable = object : Disposable {
        override fun dispose() {
        }
    }

    val logger = StdLogger("JuggTest")

    val packageName = "com.example.myapplication"
    val apkFile = File(projectRootDir, "app/build/outputs/apk/debug/app-debug.apk")
    val context get() = SimpleCompileContext(
        logger = logger,
        tempCompileDir = tempCompileDir,
        tempModuleDir = File(buildDir, "temp_module"),
        androidHome = androidHome,
        androidJar = androidJar,
        modules = AssembleAndroidProjectOnce.getProjectInfo().modules,
        apkInfos = listOf(ApkInfo(apkFile, packageName)),
        projectDir = projectRootDir,
        incrementalDataDir = File(buildDir, "incremental"),
        deployedFiles = mutableListOf(),
    )
    val applicationModule get() = context.modules["app"]!!

    init {
        PlatformApi.impl = TestPlatformApi()
    }

    fun clearBuild() {
        AssembleAndroidProjectOnce.ensure()
        buildDir.clearDir()
    }
}
