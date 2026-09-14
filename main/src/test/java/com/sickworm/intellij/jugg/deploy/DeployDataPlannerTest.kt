package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import com.sickworm.intellij.jugg.deploy.data.ResourceApkGenerator
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File

class DeployDataPlannerTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `compose resource compile state survives deploy data rebuild for retry`() {
        val tracker = DeployFileStateTracker()
        val resourceFile = temporaryFolder.newFile("strings.xml")
        val changedFile = ChangedFile(
            type = CompileFile.Type.ComposeResource,
            file = resourceFile.absoluteFile,
            baseDir = temporaryFolder.root.absoluteFile,
            module = ModuleInfo.virtualModule,
        )
        tracker.addChangedFiles(listOf(changedFile))
        tracker.updateUncompiledFiles(
            successFiles = listOf(
                CompileFile(
                    type = CompileFile.Type.ComposeResource,
                    file = resourceFile.absoluteFile,
                    baseDir = temporaryFolder.root.absoluteFile,
                    module = ModuleInfo.virtualModule,
                ),
            ),
            failedFiles = emptyList(),
        )
        val generator = Mockito.mock(DeployDataGenerator::class.java)
        Mockito.`when`(
            generator.buildDeployData(
                Mockito.anyList(),
                Mockito.eq(false),
                Mockito.eq(false),
                Mockito.eq(false),
                Mockito.eq(false),
                Mockito.anyList(),
            ),
        ).thenReturn(emptyDeployData())
        val planner = DeployDataPlanner(
            pathManager = Mockito.mock(JuggPathManager::class.java),
            deployDataGenerator = generator,
            resourceApkGenerator = Mockito.mock(ResourceApkGenerator::class.java),
            stateTracker = tracker,
            logger = Mockito.mock(Logger::class.java),
        )

        val firstDeploy = planner.buildDeployData(isWarmUp = false, isEnableCompatDeploy = false)
        val retryDeploy = planner.buildDeployData(isWarmUp = false, isEnableCompatDeploy = false)

        assertTrue(firstDeploy.isComposeResourceCompiled)
        assertTrue(retryDeploy.isComposeResourceCompiled)
    }

    @Test
    fun `flutter jit runtime staging outputs are tracked for cache invalidation`() {
        val tracker = DeployFileStateTracker()
        tracker.addStagingFiles(
            listOf(
                stagingAsset("assets/flutter_assets/kernel_blob.bin", FLUTTER_MODULE),
                stagingAsset("assets/flutter_assets/vm_snapshot_data", FLUTTER_MODULE),
                stagingAsset("assets/flutter_assets/isolate_snapshot_data", FLUTTER_MODULE),
                // Other Flutter assets are overlay only and do not own the extraction cache.
                stagingAsset("assets/flutter_assets/AssetManifest.json", FLUTTER_MODULE),
            ),
        )

        val deployData = plannerWith(tracker, emptyDeployData())
            .buildDeployData(isWarmUp = false, isEnableCompatDeploy = false)

        assertEquals(
            listOf(
                "assets/flutter_assets/kernel_blob.bin",
                "assets/flutter_assets/vm_snapshot_data",
                "assets/flutter_assets/isolate_snapshot_data",
            ),
            deployData.flutterJitRuntimeFiles.map { it.name },
        )
        assertTrue(deployData.isNeedRestartApp)
    }

    @Test
    fun `non flutter assets do not trigger flutter cache invalidation`() {
        val tracker = DeployFileStateTracker()
        tracker.addStagingFiles(
            listOf(
                // Same deploy path, but the owning module has no Flutter external build.
                stagingAsset("assets/flutter_assets/kernel_blob.bin", ModuleInfo.virtualModule),
                // Flutter module, but not a JIT runtime file.
                stagingAsset("assets/flutter_assets/AssetManifest.json", FLUTTER_MODULE),
            ),
        )

        val deployData = plannerWith(tracker, emptyDeployData())
            .buildDeployData(isWarmUp = false, isEnableCompatDeploy = false)

        assertTrue(deployData.flutterJitRuntimeFiles.isEmpty())
        assertFalse(deployData.isNeedRestartApp)
    }

    @Test
    fun `apk baseline kernel in a full overlay does not trigger flutter cache invalidation`() {
        val tracker = DeployFileStateTracker()
        tracker.addStagingFiles(listOf(stagingAsset("assets/config.json", ModuleInfo.virtualModule)))
        val baselineKernel = DeployItem(
            name = "assets/flutter_assets/kernel_blob.bin",
            type = CompileOutput.Type.Asset,
            checksum = 1L,
            content = byteArrayOf(1),
            apkPath = APK_PATH,
        )

        val deployData = plannerWith(tracker, emptyDeployData(overlays = listOf(baselineKernel), isFullRes = true))
            .buildDeployData(isWarmUp = false, isEnableCompatDeploy = false)

        assertTrue(deployData.flutterJitRuntimeFiles.isEmpty())
        assertFalse(deployData.isNeedRestartApp)
    }

    @Test
    fun `warm up deploy does not track flutter jit runtime files`() {
        val tracker = DeployFileStateTracker()
        tracker.addStagingFiles(listOf(stagingAsset("assets/flutter_assets/kernel_blob.bin", FLUTTER_MODULE)))

        val deployData = plannerWith(tracker, emptyDeployData())
            .buildDeployData(isWarmUp = true, isEnableCompatDeploy = false)

        assertTrue(deployData.flutterJitRuntimeFiles.isEmpty())
    }

    /**
     * Builds one staging asset the way [com.sickworm.intellij.jugg.compiler.overlay.AssetOverlayCompiler]
     * does: the deploy path is relative to the overlay output directory.
     */
    private fun stagingAsset(relativePath: String, module: ModuleInfo): CompileOutput {
        val overlayRoot = temporaryFolder.newFolder()
        val file = File(overlayRoot, relativePath).apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        return CompileOutput(CompileOutput.Type.Asset, file, overlayRoot, APK_PATH, relativeModule = module)
    }

    private fun plannerWith(tracker: DeployFileStateTracker, generated: JuggDeployData): DeployDataPlanner {
        val generator = Mockito.mock(DeployDataGenerator::class.java)
        Mockito.`when`(
            generator.buildDeployData(
                Mockito.anyList(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyList(),
            ),
        ).thenReturn(generated)
        return DeployDataPlanner(
            pathManager = Mockito.mock(JuggPathManager::class.java),
            deployDataGenerator = generator,
            resourceApkGenerator = Mockito.mock(ResourceApkGenerator::class.java),
            stateTracker = tracker,
            logger = Mockito.mock(Logger::class.java),
        )
    }

    private fun emptyDeployData(
        overlays: List<DeployItem> = emptyList(),
        isFullRes: Boolean = false,
    ): JuggDeployData {
        return JuggDeployData(
            apks = emptyList(),
            newClasses = emptyList(),
            hotFixModifiedClasses = emptyList(),
            hotReloadModifiedClasses = emptyList(),
            effectedClassNodes = emptyList(),
            overlays = overlays,
            parsedDex = ParsedDex.EMPTY,
            isFullRes = isFullRes,
            isWarmUp = false,
        )
    }

    companion object {
        private const val APK_PATH = "/base.apk"

        private val FLUTTER_MODULE = ModuleInfo.virtualModule.copy(
            name = "flutter_module",
            externalBuildInfos = listOf(
                ExternalBuildInfo(
                    type = ExternalBuildType.Flutter,
                    inputDirs = emptyList(),
                    taskPath = ":flutter:compileFlutterBuildDebug",
                    assetsOutputDir = File("/flutter/build/assets"),
                    nativeOutput = null,
                ),
            ),
        )
    }
}
