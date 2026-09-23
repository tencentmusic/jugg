package com.sickworm.intellij.jugg.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.overlay.RDexForSubmoduleCompiler
import com.sickworm.intellij.jugg.compiler.withDependencyName
import com.sickworm.intellij.jugg.compiler.withRPackageName
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.mock.mockParentDisposable
import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleDependency
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `temp module generates one R dex group per external namespace`() {
        val root = Files.createTempDirectory("jugg-r-dex-external").toFile()
        try {
            val context = tempModuleContext(root)
            val tempModule = context.tempModule
            val outputDir = File(root, "output")
            copyMainRDexFiles(outputDir)
            val task = CompileTask(
                files = listOf(
                    externalResource(root, tempModule, "com.example.first", "com.example:first:1.0"),
                    // same namespace from another dependency must not generate a second group
                    externalResource(root, tempModule, "com.example.first", "com.example:first-impl:1.0"),
                    externalResource(root, tempModule, "com.example.second", "com.example:second:1.0"),
                ),
                outputDir = outputDir,
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )

            val result = RDexForSubmoduleCompiler(context, mockParentDisposable)
                .doModuleCompile(task, tempModule)

            assertEquals(
                listOf(
                    "com/example/first/R\$dimen.dex",
                    "com/example/first/R.dex",
                    "com/example/second/R\$dimen.dex",
                    "com/example/second/R.dex",
                ),
                result.outputs.map { it.file.relativeTo(outputDir).invariantSeparatorsPath }.sorted(),
            )
            result.outputs.forEach {
                assertTrue(it.file.isFile)
                assertEquals(baseApkFile(root).path, it.apkPath)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `temp module skips application namespace and keeps other external namespaces`() {
        val root = Files.createTempDirectory("jugg-r-dex-main-package").toFile()
        try {
            val context = tempModuleContext(root)
            val tempModule = context.tempModule
            val outputDir = File(root, "output")
            copyMainRDexFiles(outputDir)
            val task = CompileTask(
                files = listOf(
                    externalResource(root, tempModule, "com.example.first", "com.example:first:1.0"),
                    externalResource(root, tempModule, APPLICATION_ID, "com.example:main-namespace:1.0"),
                ),
                outputDir = outputDir,
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )

            val result = RDexForSubmoduleCompiler(context, mockParentDisposable)
                .doModuleCompile(task, tempModule)

            assertEquals(
                listOf("com/example/first/R\$dimen.dex", "com/example/first/R.dex"),
                result.outputs.map { it.file.relativeTo(outputDir).invariantSeparatorsPath }.sorted(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `temp module fails when an external namespace can not be resolved`() {
        val root = Files.createTempDirectory("jugg-r-dex-missing-namespace").toFile()
        try {
            val context = tempModuleContext(root)
            val tempModule = context.tempModule
            val outputDir = File(root, "output")
            copyMainRDexFiles(outputDir)
            val task = CompileTask(
                files = listOf(
                    externalResource(root, tempModule, null, "com.example:missing:1.0"),
                ),
                outputDir = outputDir,
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )

            val exception = assertFailsWith<JuggException> {
                RDexForSubmoduleCompiler(context, mockParentDisposable).doModuleCompile(task, tempModule)
            }

            assertTrue(exception.message!!.contains("com.example:missing:1.0"))
            assertTrue(exception.message!!.contains("full Gradle build"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun tempModuleContext(root: File): SimpleCompileContext {
        val app = module(root, "app", ModuleInfo.Type.Application).copy(
            applicationId = APPLICATION_ID,
            runtimeModuleDependencies = emptyList(),
        )
        val baseApk = ApkFileUnit(APPLICATION_ID, "", true, File(root, "base.apk"))
        return SimpleCompileContext(
            logger = Logger.getInstance("test"),
            tempCompileDir = File(root, "compile"),
            tempModuleDir = File(root, "temp"),
            androidHome = File(root, "android-home"),
            androidJar = File(root, "android.jar"),
            modules = mapOf(app.name to app),
            apkInfos = listOf(ApkInfo(listOf(baseApk), APPLICATION_ID)),
            projectDir = root,
            deployedFiles = mutableListOf(),
            incrementalDataDir = File(root, "incremental"),
        )
    }

    private fun baseApkFile(root: File) = File(root, "base.apk")

    /** Main R dex of the host build, all of them must be renamed for an external namespace. */
    private fun copyMainRDexFiles(outputDir: File) {
        val mainRDexDir = File(outputDir, APPLICATION_ID.replace('.', '/'))
        mainRDexDir.mkdirs()
        listOf("R.dex", "R\$dimen.dex").forEach { name ->
            File("src/test/assets/dex/com/example/myapplication/$name").copyTo(File(mainRDexDir, name))
        }
    }

    private fun externalResource(
        root: File,
        tempModule: ModuleInfo,
        rPackageName: String?,
        dependencyName: String,
    ): CompileFile {
        val resDir = File(root, "deps/${dependencyName.replace(':', '_')}/res")
        val strings = File(resDir, "values/strings.xml").apply {
            parentFile.mkdirs()
            writeText("<resources><string name=\"external\">value</string></resources>")
        }
        return CompileFile(CompileFile.Type.Resource, strings, resDir, tempModule)
            .withDependencyName(dependencyName)
            .withRPackageName(rPackageName)
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

    private companion object {
        const val APPLICATION_ID = "com.example.myapplication"
    }
}
