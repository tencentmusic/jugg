package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ResourceApkModifier
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.deployItemName
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.toDeployItem
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File

/**
 * generates resource.ap_
 */
class ResourceApkGenerator(
    private val deployDataDatabase: IDeployDataDatabase,
    databaseDir: File,
    logger: Logger,
) {
    private val logger = logger.getInstance("ResourceApkGenerator")

    private var resourceApkFile = File(databaseDir, BuildConfig.RESOURCE_APK_NAME)

    fun getResourceApkDeployItem(changedOverlays: List<DeployItem>, deployedFiles: Map<String, CompileOutput>): List<DeployItem> {
        TimeLogger.start("update resource apk")
        val resourceModifier = ResourceApkModifier(resourceApkFile, logger)
        if (!resourceApkFile.exists()) {
            val nameSet = mutableSetOf<String>()
            val deployedItems = mutableListOf<DeployItem>()
            changedOverlays.forEach {
                deployedItems.add(it)
                nameSet.add(it.name)
            }
            deployedFiles.forEach { (_, output) ->
                if (output.type != CompileOutput.Type.Asset && output.type != CompileOutput.Type.Res) {
                    return@forEach
                }
                val name = output.deployItemName
                if (nameSet.contains(name)) {
                    return@forEach
                }
                val deployItem = output.toDeployItem()
                deployedItems.add(deployItem)
                nameSet.add(name)
            }

            val fullResAndAssets = deployDataDatabase.addFullRes(deployedItems, isNeedRes = true, isNeedAsset = true)
            resourceModifier.createResourceApk(fullResAndAssets)
            resourceModifier.toDeployItems()
        } else {
            resourceModifier.incrementalUpdateResourceApk(changedOverlays)
        }
        TimeLogger.end("update resource apk", logger)

        return resourceModifier.toDeployItems()
    }

    fun deleteResourceApk() {
        resourceApkFile.delete()
    }
}