package com.sickworm.intellij.jugg.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.overlay.RDexForSubmoduleCompiler
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.mock.mockParentDisposable
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleDependency
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class RDexForSubmoduleCompilerTest {

    @Test
    fun `generated module R dex targets only its owning feature apk`() {
        val root = Files.createTempDirectory("jugg-r-dex-feature").toFile()
        try {
            val app = module(root, "app", ModuleInfo.Type.Application).copy(
                applicationId = "com.example.myapplication",
                runtimeModuleDependencies = emptyList(),
            )
            val feature = module(root, "feature", ModuleInfo.Type.DynamicFeature).copy(
                namespace = "com.example.feature",
                runtimeModuleDependencies = listOf(ModuleDependency("business_gift")),
            )
            feature.buildPathInfo.mergedManifest.apply {
                parentFile.mkdirs()
                writeText("<manifest featureSplit=\"feature\" />")
            }
            val businessGift = module(root, "business_gift", ModuleInfo.Type.Library).copy(
                namespace = "com.tme.rif.business.gift",
            )
            val baseApk = ApkFileUnit("com.example.myapplication", "", true, File(root, "base.apk"))
            val featureApk = ApkFileUnit("com.example.myapplication", "feature", true, File(root, "feature.apk"))
            val outputDir = File(root, "output")
            File(outputDir, "com/example/myapplication/R.dex").apply {
                parentFile.mkdirs()
                File("src/test/assets/dex/com/example/myapplication/R.dex").copyTo(this)
            }
            val modules = listOf(app, feature, businessGift).associateBy { it.name }
            val context = SimpleCompileContext(
                logger = Logger.getInstance("test"),
                tempCompileDir = File(root, "compile"),
                tempModuleDir = File(root, "temp"),
                androidHome = File(root, "android-home"),
                androidJar = File(root, "android.jar"),
                modules = modules,
                apkInfos = listOf(ApkInfo(listOf(baseApk, featureApk), "com.example.myapplication")),
                projectDir = root,
                deployedFiles = mutableListOf(),
                incrementalDataDir = File(root, "incremental"),
            )

            val result = RDexForSubmoduleCompiler(context, mockParentDisposable)
                .doModuleCompile(CompileTask(emptyList(), outputDir, CompileStatusHolder.DEFAULT), businessGift)

            assertEquals(featureApk.apkFile.path, result.outputs.single().apkPath)
            assertEquals(listOf(featureApk.apkFile.path), result.outputs.single().targetApkPaths)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun module(root: File, name: String, type: ModuleInfo.Type): ModuleInfo {
        val moduleDir = File(root, name)
        val manifest = File(moduleDir, "src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            writeText("<manifest />")
        }
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = type,
            moduleRootDir = moduleDir,
            projectRootDir = root,
            manifestFile = manifest,
            buildPathInfo = ModuleBuildPathInfo(root, moduleDir, "debug", buildDirRelativePath = ""),
        )
    }
}
