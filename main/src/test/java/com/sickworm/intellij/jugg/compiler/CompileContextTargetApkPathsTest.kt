package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.ModuleApkBelongs
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CompileContextTargetApkPathsTest {

    @Test
    fun `module target apk paths use all belongs apk view`() {
        val testRoot = Files.createTempDirectory("jugg-target-apk-paths").toFile()
        val module = ModuleInfo.virtualModule.copy(
            name = "library1",
            projectRootDir = testRoot,
            moduleRootDir = File(testRoot, "library1"),
            buildPathInfo = ModuleBuildPathInfo(testRoot, File(testRoot, "library1"), "debug", buildDirRelativePath = ""),
        )
        val baseUnit = ApkFileUnit("com.example.app", "", true, File("/base.apk"))
        val testUnit = ApkFileUnit("com.example.library1.test", "", true, File("/test.apk"))
        val context = SimpleCompileContext(
            logger = TestGlobal.logger,
            tempCompileDir = File(testRoot, "compiled"),
            tempModuleDir = File(testRoot, "temp_module"),
            androidHome = TestGlobal.androidHome,
            androidJar = TestGlobal.androidJar,
            modules = mapOf(module.name to module),
            apkInfos = listOf(ApkInfo(listOf(baseUnit), "com.example.app")),
            projectDir = testRoot,
            deployedFiles = mutableListOf(),
            incrementalDataDir = File(testRoot, "incremental"),
            customModuleBelongsApkMap = ModuleApkBelongs(
                primaryApkMap = mapOf(module to baseUnit),
                allApkMap = mapOf(module to listOf(baseUnit, testUnit)),
            ),
        )

        assertEquals("/base.apk", context.getBelongsApkPath(module))
        assertEquals(listOf("/base.apk", "/test.apk"), context.getAllBelongsApkPaths(module))
    }
}
