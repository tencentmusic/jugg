@file:Suppress("MayBeConstant", "HasPlatformType")

package com.sickworm.intellij.jugg.mock

import com.google.gson.JsonSyntaxException
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File


typealias OutputFileMapper = (CompileFile) -> List<CompileOutput>

object TestGlobal {

    private val rootDir = File("../").absoluteFile.normalize()

    private val ideaDir = File(rootDir, "idea").absoluteFile
    val buildDir = File(rootDir, "main/src/test/build").absoluteFile
    private val tempCompileDir = File(buildDir, "compiled")
    val stagingDir = File(buildDir, "staging")

    val projectRootDir: File = File(rootDir, "android_demo_project").absoluteFile
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

    val mockModule get() = ModuleInfo(
        name = "mock_module",
        moduleType = ModuleInfo.Type.Unknown,
        moduleRootDir = appModuleDir,
        projectRootDir = projectRootDir,
        sourceDirs = listOf(File(appModuleDir, "src/main/java")),
        resourceDirs = listOf(File(appModuleDir, "src/main/res")),
        assetsDirs = listOf(File(appModuleDir, "src/main/assets")),
        manifestFile = File(appModuleDir, "src/main/AndroidManifest.xml"),
        manifestPlaceHolders = null,
        buildVariant = ModuleInfo.DEFAULT_BUILD_VARIANT,
        compileVersion = null,
        buildToolsVersion = null,
        buildPathInfo = ModuleBuildPathInfo(
            projectRootDir,
            appModuleDir,
            ModuleInfo.DEFAULT_BUILD_VARIANT
        ),
        kotlinJvmTarget = "1.8",
        kotlinFreeCompilerArgs = emptyList(),
        javaSourceCompatibility = "1.8",
        javaTargetCompatibility = "1.8",
        moduleDependencies = emptyList(),
        libraryDependencies = emptyList(),
        minSdkVersion = "21",
        runtimeLibraryDependencies = emptyList(),
        annotationProcessorDependencies = emptyList(),
        kaptDependencies = emptyList(),
    )

    val projectInfo = try {
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

    init {
        PlatformApi.impl = TestPlatformApi()
    }

    fun clearBuild() {
        AssembleAndroidProjectOnce.ensure()
        buildDir.clearDir()
    }
}
