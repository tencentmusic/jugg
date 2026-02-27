package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.IncrementalCompilerHelper
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.ResourceApkGenerator
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.project.JuggPathManager
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
        private const val MAX_DEPLOYED_DEX_COUNT = 800
    }

    /**
     * Build deploy data from current staging outputs and historical deployed dex statistics.
     */
    fun buildDeployData(
        isWarmUp: Boolean,
        isEnableCompatDeploy: Boolean,
    ): JuggDeployData {
        val stagingOutputs = stateTracker.getStagingFiles()
        val notStagingDeployedFiles = stateTracker.getNotStagingDeployedFiles()
        val deployItems = stagingOutputs.map { it.toDeployItem() }
        var deployData = deployDataGenerator.buildDeployData(deployItems, isWarmUp, isNeedCheckRecompile = false)

        val allDex = (stagingOutputs + notStagingDeployedFiles)
            .filter { it.type == CompileOutput.Type.Dex }
        if (allDex.size > MAX_DEPLOYED_DEX_COUNT) {
            logger.info("Current dex count(${allDex.size}) exceeds threshold($MAX_DEPLOYED_DEX_COUNT), trigger dex merge.")
            deployData = convertToMergedDexDeployData(deployData, stagingOutputs, notStagingDeployedFiles)
            stateTracker.markMergedDexFilePaths(allDex)
        }
        if (isEnableCompatDeploy) {
            deployData = appendCompatDeployFiles(deployData, notStagingDeployedFiles)
        }
        return deployData
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
    ): JuggDeployData {
        val stagingDexOutputs = stagingFiles.filter { it.type == CompileOutput.Type.Dex }
        if (stagingDexOutputs.isEmpty()) {
            return deployData
        }

        val mergeOutputDir = File(pathManager.tmpDir, "deploy_merged_dex")
        val deployedDexOutputs = notStagingDeployedFiles.filter { it.type == CompileOutput.Type.Dex }
        val mergedOutputs = mergeDex(stagingFiles + deployedDexOutputs, mergeOutputDir)
        if (mergedOutputs == null) {
            logger.warn("Dex merge failed, continue with original dex outputs.")
            return deployData
        }

        val mergedDexDeployItems = mergedOutputs
            .filter { it.type == CompileOutput.Type.Dex }
            .map { it.toDeployItem() }
        if (mergedDexDeployItems.isEmpty()) {
            logger.warn("Dex merge finished but merged dex is empty, continue with original deploy data.")
            return deployData
        }

        val isHasDuplicate = deployData.updateApkFiles.any { mergedDex ->
            mergedDexDeployItems.any { it.name == mergedDex.name }
        }
        if (isHasDuplicate) {
            logger.debug("Dex merge failed, mergedDexDeployItems: ${mergedDexDeployItems.map { it.name }}, " +
                    "deployData.updateApkFiles: ${deployData.updateApkFiles.map { it.name }}")
            logger.warn("Dex merge failed, updateApkFiles has duplicate entry. Continue with original dex outputs.")
            return deployData
        }
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
