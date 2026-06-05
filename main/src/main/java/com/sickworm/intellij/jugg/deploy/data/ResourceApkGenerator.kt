package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ResourceApkModifier
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.toDeployItem
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File

/**
 * generates resource.ap_
 */
class ResourceApkGenerator(
    private val deployDataDatabase: IDeployDataDatabase,
    private val resourceApkDir: File,
    logger: Logger,
) {
    private val logger = logger.getInstance("ResourceApkGenerator")

    fun getResourceApkDeployItem(changedOverlays: List<DeployItem>, notStagingDeployedFiles: List<CompileOutput>): List<DeployItem> {
        resourceApkDir.mkdirs()
        val changedOverlaysMap = changedOverlays.flatMap { item ->
            item.targetApkPaths.ifEmpty { listOf(item.apkPath) }.map { it to item }
        }.groupBy({ it.first }, { it.second })
        return changedOverlaysMap.flatMap { (apkPath, deployItems) ->
            if (apkPath == DeployItem.FLAG_BASE_APK || apkPath == DeployItem.FLAG_CLASS) {
                throw JuggInternalException.flagApkPathNotAllowed(deployItems.joinToString { it.name })
            }
            val resourceApkName = resourceApkDir.resolve(File(apkPath).name).resolve(BuildConfig.RESOURCE_APK_NAME)
            getResourceApkDeployItem(apkPath, resourceApkName, deployItems, notStagingDeployedFiles)
        }
    }

    private fun getResourceApkDeployItem(
        originApkPath: String,
        resourceApkFile: File,
        changedOverlays: List<DeployItem>,
        notStagingDeployedFiles: List<CompileOutput>,
    ): List<DeployItem> {
        val isApkExists = resourceApkFile.exists()
        logger.debug("getResourceApkDeployItem, resourceApkFile: ${resourceApkFile}, isApkExists: $isApkExists")
        TimeLogger.start("getResourceApkDeployItem")

        val resourceModifier = ResourceApkModifier(originApkPath, resourceApkFile, logger)
        if (!isApkExists) {
            val deployedItems = buildScopedFullResInputs(originApkPath, changedOverlays, notStagingDeployedFiles)

            val fullResAndAssets = deployDataDatabase.addFullRes(deployedItems, isNeedRes = true, isNeedAsset = true)
            resourceModifier.createResourceApk(fullResAndAssets.scopedDistinctByName(originApkPath))
            resourceModifier.toDeployItems()
        } else {
            resourceModifier.incrementalUpdateResourceApk(changedOverlays)
        }

        TimeLogger.end("getResourceApkDeployItem", logger)
        return resourceModifier.toDeployItems()
    }

    private fun buildScopedFullResInputs(
        originApkPath: String,
        changedOverlays: List<DeployItem>,
        notStagingDeployedFiles: List<CompileOutput>,
    ): List<DeployItem> {
        val nameSet = mutableSetOf<String>()
        val deployedItems = mutableListOf<DeployItem>()
        fun addScopedItem(item: DeployItem) {
            if (!item.belongsTo(originApkPath) || !nameSet.add(item.name)) {
                return
            }
            deployedItems.add(item)
        }

        changedOverlays.forEach(::addScopedItem)
        notStagingDeployedFiles.forEach { output ->
            if (output.type != CompileOutput.Type.Asset && output.type != CompileOutput.Type.Res) {
                return@forEach
            }
            addScopedItem(output.toDeployItem())
        }
        return deployedItems
    }

    private fun List<DeployItem>.scopedDistinctByName(originApkPath: String): List<DeployItem> {
        val nameSet = mutableSetOf<String>()
        return filter { it.belongsTo(originApkPath) && nameSet.add(it.name) }
    }

    fun deleteResourceApk() {
        resourceApkDir.deleteRecursively()
    }
}
