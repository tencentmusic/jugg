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
    private val logger: Logger,
) {
    companion object {
        private const val MAX_DEPLOYED_DEX_COUNT = 500
    }

    /**
     * Build deploy data from current staging outputs and historical deployed dex statistics.
     */
    fun buildDeployData(
        stagingOutputs: List<CompileOutput>,
        historyDexCountWithoutMerged: Int,
        deployedFiles: Map<String, CompileOutput>,
        isWarmUp: Boolean,
        isEnableCompatDeploy: Boolean,
    ): DeployDataPlanResult {
        val deployItems = stagingOutputs.map { it.toDeployItem() }
        val originDeployData = deployDataGenerator.buildDeployData(deployItems, isWarmUp, isNeedCheckRecompile = false)
        val mergedDexResult = tryConvertToMergedDexDeployData(originDeployData, stagingOutputs, historyDexCountWithoutMerged)
        val finalDeployData = if (isEnableCompatDeploy) {
            appendCompatDeployFiles(mergedDexResult.deployData, deployedFiles)
        } else {
            mergedDexResult.deployData
        }
        return mergedDexResult.copy(deployData = finalDeployData)
    }

    fun appendCompatDeployFiles(
        deployData: JuggDeployData,
        deployedFiles: Map<String, CompileOutput>,
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
            val resourceApks = resourceApkGenerator.getResourceApkDeployItem(deployData.overlays, deployedFiles)
            compatDeployData = compatDeployData.copy(overlays = compatDeployData.overlays + resourceApks)
        }
        return compatDeployData
    }

    private fun tryConvertToMergedDexDeployData(
        deployData: JuggDeployData,
        stagingOutputs: List<CompileOutput>,
        historyDexCountWithoutMerged: Int,
    ): DeployDataPlanResult {
        val stagingDexOutputs = stagingOutputs.filter { it.type == CompileOutput.Type.Dex }
        if (stagingDexOutputs.isEmpty()) {
            return DeployDataPlanResult(deployData)
        }

        val totalDexCount = stagingDexOutputs.size + historyDexCountWithoutMerged
        if (totalDexCount <= MAX_DEPLOYED_DEX_COUNT) {
            return DeployDataPlanResult(deployData)
        }

        logger.info("Current dex count($totalDexCount) exceeds threshold($MAX_DEPLOYED_DEX_COUNT), trigger dex merge.")
        val mergeOutputDir = File(pathManager.tmpDir, "deploy_merged_dex")
        val mergedOutputs = mergeDex(stagingOutputs, mergeOutputDir)
        if (mergedOutputs == null) {
            logger.warn("Dex merge failed, continue with original dex outputs.")
            return DeployDataPlanResult(deployData)
        }

        val mergedDexDeployItems = mergedOutputs
            .filter { it.type == CompileOutput.Type.Dex }
            .map { it.toDeployItem() }
        if (mergedDexDeployItems.isEmpty()) {
            logger.warn("Dex merge finished but merged dex is empty, continue with original deploy data.")
            return DeployDataPlanResult(deployData)
        }

        val updateApkFiles = deployData.updateApkFiles + mergedDexDeployItems
        logger.info("Dex merge success, staging dex: ${stagingDexOutputs.size}, merged dex: ${mergedDexDeployItems.size}")
        return DeployDataPlanResult(
            deployData = deployData.copy(
                newClasses = emptyList(),
                hotFixModifiedClasses = emptyList(),
                hotReloadModifiedClasses = emptyList(),
                updateApkFiles = updateApkFiles,
            ),
            mergedDexSourcePaths = stagingDexOutputs.map { it.file.stdAbsPath },
        )
    }

    private fun mergeDex(outputs: List<CompileOutput>, outputDir: File): List<CompileOutput>? {
        val compileResult = CompileResult.empty(CompileStatusHolder.DEFAULT).copy(outputs = outputs)
        return IncrementalCompilerHelper.mergeDex(logger, compileResult, outputDir)?.outputs
    }
}

data class DeployDataPlanResult(
    val deployData: JuggDeployData,
    val mergedDexSourcePaths: List<String> = emptyList(),
)
