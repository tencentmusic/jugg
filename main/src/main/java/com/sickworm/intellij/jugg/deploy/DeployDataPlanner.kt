package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.IncrementalCompilerHelper
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.ResourceApkGenerator
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.project.info.ExternalBuildType
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import java.io.File
import java.util.zip.CRC32

/**
 * Plans deploy payload from staging outputs and deployed history.
 */
class DeployDataPlanner(
    private val pathManager: JuggPathManager,
    private val deployDataGenerator: DeployDataGenerator,
    private val resourceApkGenerator: ResourceApkGenerator,
    private val stateTracker: DeployFileStateTracker,
    private val logger: Logger,
) {
    companion object {
        // dex count to trigger dex merge, dex initialize may get OOM if dex count is too large e.g. > 2000
        const val MAX_DEPLOYED_DEX_COUNT = 1000

        /** Deploy paths of the Flutter JIT runtime files extracted by the Flutter Android embedding. */
        private val FLUTTER_JIT_RUNTIME_PATHS = setOf(
            "assets/flutter_assets/kernel_blob.bin",
            "assets/flutter_assets/vm_snapshot_data",
            "assets/flutter_assets/isolate_snapshot_data",
        )
    }

    /**
     * Build deploy data from current staging outputs and historical deployed dex statistics.
     */
    fun buildDeployData(
        isWarmUp: Boolean,
        isEnableCompatDeploy: Boolean,
    ): JuggDeployData {
        logger.trace("[PERF] DeployDataPlanner.buildDeployData start, thread=${Thread.currentThread().name}")
        val plannerStart = System.currentTimeMillis()
        val stagingOutputs = stateTracker.getStagingFiles(isFilterMergedDex = true)
        val notStagingDeployedFiles = stateTracker.getNotStagingDeployedFiles()
        logger.trace("[PERF] DeployDataPlanner.getStagingFiles end, cost=${System.currentTimeMillis() - plannerStart}ms, thread=${Thread.currentThread().name}")
        // Flutter JIT runtime assets must be collected from this round's staging outputs: the full
        // resource overlay also carries the old kernel from the APK baseline, which must not
        // invalidate the Flutter extraction cache.
        val flutterJitRuntimeFiles = mutableListOf<DeployItem>()
        val deployItems = stagingOutputs.map { output ->
            val deployItem = output.toDeployItem()
            if (!isWarmUp && isFlutterJitRuntimeOutput(output)) {
                flutterJitRuntimeFiles += deployItem
            }
            deployItem
        }
        logger.trace("[PERF] DeployDataPlanner.deployDataGenerator.buildDeployData start, thread=${Thread.currentThread().name}, deployItemsSize=${deployItems.size}")
        val buildDataStart = System.currentTimeMillis()
        var deployData = deployDataGenerator.buildDeployData(deployItems, isWarmUp, isNeedCheckRecompile = false).copy(
            isComposeResourceCompiled = !isWarmUp && stateTracker.getCompiledFiles().any {
                it.type == CompileFile.Type.ComposeResource
            },
            flutterJitRuntimeFiles = flutterJitRuntimeFiles,
        )
        logger.trace("[PERF] DeployDataPlanner.deployDataGenerator.buildDeployData end, cost=${System.currentTimeMillis() - buildDataStart}ms, thread=${Thread.currentThread().name}")

        val allDex = (stagingOutputs + notStagingDeployedFiles)
            .filter { it.type == CompileOutput.Type.Dex }
        logger.debug("buildDeployData: " +
                "allStagingOutputs ${stateTracker.getStagingFiles().size}, " +
                "stagingOutputs ${stagingOutputs.size}, " +
                "notStagingDeployedFiles ${notStagingDeployedFiles.size}, " +
                "allDex ${allDex.size}")
        val stagingDexOutputs = stagingOutputs.filter { it.type == CompileOutput.Type.Dex }
        if (stagingDexOutputs.isNotEmpty() && allDex.size > MAX_DEPLOYED_DEX_COUNT) {
            logger.info("Current dex count(${allDex.size}) exceeds threshold($MAX_DEPLOYED_DEX_COUNT), trigger dex merge.")
            val mergedDeployData = convertToMergedDexDeployData(deployData, stagingOutputs, notStagingDeployedFiles)
            if (mergedDeployData != null) {
                deployData = mergedDeployData
                stateTracker.markMergedDexFilePaths(allDex)
            }
        }
        if (isEnableCompatDeploy) {
            deployData = appendCompatDeployFiles(deployData, notStagingDeployedFiles)
        }
        return deployData
    }

    /**
     * True when the staging output is one of the Flutter JIT runtime assets extracted by the Flutter
     * Android embedding. Only assets produced by a Flutter external build qualify.
     */
    private fun isFlutterJitRuntimeOutput(output: CompileOutput): Boolean {
        if (output.type != CompileOutput.Type.Asset) {
            return false
        }
        val isFlutterBuild = output.relativeModule?.externalBuildInfos
            ?.any { it.type == ExternalBuildType.Flutter } == true
        return isFlutterBuild && output.deployItemName in FLUTTER_JIT_RUNTIME_PATHS
    }

    fun appendCompatDeployFiles(
        deployData: JuggDeployData,
        notStagingDeployedFiles: List<CompileOutput>,
    ): JuggDeployData {
        var compatDeployData = deployData.copy(isCompatDeploy = true, isPushOverlayOnly = true)
        compatDeployData = compatDeployData.copy(overlays = compatDeployData.overlays.filter {
            it.type != CompileOutput.Type.Res && it.type != CompileOutput.Type.Asset
        })

        if (!deployData.isEmpty) {
            val enableFlag = DeployItem(
                name = BuildConfig.ENABLE_COMPAT_DEPLOY_FLAG_FILE,
                type = CompileOutput.Type.Asset,
                checksum = CRC32().let {
                    it.update(ByteArray(0))
                    it.value
                },
                content = ByteArray(0),
                apkPath = DeployItem.FLAG_BASE_APK,
            )
            compatDeployData = compatDeployData.copy(overlays = compatDeployData.overlays + enableFlag)
        }

        if (deployData.overlays.isNotEmpty()) {
            val resourceApks = resourceApkGenerator.getResourceApkDeployItem(deployData.overlays, notStagingDeployedFiles)
            compatDeployData = compatDeployData.copy(overlays = compatDeployData.overlays + resourceApks)
        }
        return compatDeployData
    }

    private fun convertToMergedDexDeployData(
        deployData: JuggDeployData,
        stagingFiles: List<CompileOutput>,
        notStagingDeployedFiles: List<CompileOutput>,
    ): JuggDeployData? {
        val mergeOutputDir = File(pathManager.tmpDir, "deploy_merged_dex")
        val deployedDexOutputs = notStagingDeployedFiles.filter { it.type == CompileOutput.Type.Dex }
        val mergedOutputs = mergeDex(stagingFiles + deployedDexOutputs, mergeOutputDir)
        if (mergedOutputs == null) {
            logger.warn("Dex merge failed, continue with original dex outputs.")
            return null
        }

        val mergedDexDeployItems = mergedOutputs
            .filter { it.type == CompileOutput.Type.Dex }
            .map { it.toDeployItem() }
        if (mergedDexDeployItems.isEmpty()) {
            logger.warn("Dex merge finished but merged dex is empty, continue with original deploy data.")
            return null
        }

        val isHasDuplicate = deployData.updateApkFiles.any { mergedDex ->
            mergedDexDeployItems.any { it.name == mergedDex.name }
        }
        if (isHasDuplicate) {
            logger.debug("Dex merge failed, mergedDexDeployItems: ${mergedDexDeployItems.map { it.name }}, " +
                    "deployData.updateApkFiles: ${deployData.updateApkFiles.map { it.name }}")
            logger.warn("Dex merge failed, updateApkFiles has duplicate entry. Continue with original dex outputs.")
            return null
        }
        val stagingDexOutputs = stagingFiles.filter { it.type == CompileOutput.Type.Dex }
        logger.info("Dex merge success, staging dex: ${stagingDexOutputs.size}, merged dex: ${mergedDexDeployItems.size}")

        val updateApkFiles = deployData.updateApkFiles + mergedDexDeployItems
        return deployData.copy(
                newClasses = emptyList(),
                hotFixModifiedClasses = emptyList(),
                hotReloadModifiedClasses = emptyList(),
                updateApkFiles = updateApkFiles,
        )
    }

    private fun mergeDex(outputs: List<CompileOutput>, outputDir: File): List<CompileOutput>? {
        val compileResult = CompileResult.empty(CompileStatusHolder.DEFAULT).copy(outputs = outputs)
        return IncrementalCompilerHelper.mergeDex(logger, compileResult, outputDir)?.outputs
    }
}
